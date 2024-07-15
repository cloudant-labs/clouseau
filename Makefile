PROJECT_NAME=ziose
CACHE?=true
BUILD_DIR=$(shell pwd)
ARTIFACTS_DIR?=$(BUILD_DIR)/artifacts
CI_ARTIFACTS_DIR=$(BUILD_DIR)/ci-artifacts
GIT_COMMIT?=$(shell git rev-parse HEAD)
GIT_REPOSITORY?=$(shell git config --get remote.origin.url)
DIRENV_VERSION := $(shell grep -F 'direnv' .tool-versions | awk '{print $$2}')
REBAR?=rebar3
ifeq ($(PROJECT_VERSION),)
# technically we could use 'sbt -Dsbt.supershell=false -error "print version"'
# but it takes 30 seconds to run it. So we go with direct access
PROJECT_VERSION := $(shell cat $(BUILD_DIR)/build.sbt | sed -e \
	'/ThisBuild[[:space:]]*[/][[:space:]]*version[[:space:]]*[:]=[[:space:]]*/!d' \
	-e "s///g" \
	-e 's/\"//g' \
)
endif
SCALA_VERSION := $(shell cat $(BUILD_DIR)/build.sbt | sed -e \
	'/ThisBuild[[:space:]]*[/][[:space:]]*scalaVersion[[:space:]]*[:]=[[:space:]]*/!d' \
	-e "s///g" \
	-e 's/\"//g' \
)

SCALA_VERSION_PARTS      := $(subst ., ,$(SCALA_VERSION))

SCALA_MAJOR              := $(word 1,$(SCALA_VERSION_PARTS))
SCALA_MINOR              := $(word 2,$(SCALA_VERSION_PARTS))
SCALA_MICRO              := $(word 3,$(SCALA_VERSION_PARTS))

SCALA_SHORT_VERSION := $(SCALA_MAJOR).$(SCALA_MINOR)

SUBPROJECTS := \
	benchmarks \
	clouseau \
	core \
	experiments \
	otp \
	scalang \
	vendor
BUILD_DATE?=$(shell date -u +"%Y-%m-%dT%TZ")
ERL_EPMD_ADDRESS?=127.0.0.1
# tput in docker require TERM variable
TERM?=xterm

node_name?=clouseau1
cookie=
# Rebar options
suites=
tests=

# We use `suites` instead of `module` to be compatible with CouchDB
EUNIT_OPTS := "--setcookie=$(cookie) --module=$(suites) --test=$(tests)"

UNAME_S := $(shell uname -s)
ifeq ($(UNAME_S),Linux)
	OS=linux
endif
ifeq ($(UNAME_S),Darwin)
	OS=darwin
endif

ENCODED_GHE_USR=$(shell echo ${GHE_USR} | sed 's/@/%40/g' )
GHE_AUTH_URL=https://${ENCODED_GHE_USR}:${GHE_PSW}@github.ibm.com

ENCODED_ARTIFACTORY_USR=$(shell echo ${ARTIFACTORY_USR} | sed 's/@/%40/g' )

KNOWN_CVEs = \


define extract
echo "$(1)  $(2) $(3)" && \
CONTAINER_ID=`docker create --read-only $(1) dummy` \
&& echo "Copying binary $(2) from container $$CONTAINER_ID" \
&& docker cp $$CONTAINER_ID:$(2) $(3) 2> /dev/null \
&& docker rm -f $$CONTAINER_ID 2> /dev/null
endef

define to_artifacts
	find $(SUBPROJECTS) -name '$(1)' -print0 | while IFS= read -r -d '' pathname; \
	do \
		project=$$(echo "$${pathname}" | cut -d "/" -f1) ; \
		mkdir -p "$(ARTIFACTS_DIR)/$${project}"; \
		cp -r "$${pathname}" "$(ARTIFACTS_DIR)/$${project}" ; \
	done
endef

ifeq ($(JENKINS_URL),)
	# Local invocation
	UBI_OPENJDK17_DIGEST?=$(shell docker manifest inspect registry.access.redhat.com/ubi8/openjdk-17:latest \
	| jq -r '.manifests |  to_entries[] | select(.value.platform.architecture == "amd64") | .value.digest')
endif

ifeq ($(CACHE),true)
	DOCKER_ARGS=--pull
else
	DOCKER_ARGS=--pull --no-cache --rm
endif

.PHONY: build
# target: build - Build package, run tests and create distribution
build: epmd
	@sbt compile

.PHONY: deps
# target: deps - Download all dependencies for offline development
# this target is not working correctly yet
deps:
	@echo "==> downloading dependencies..."
	@sbt update

.PHONY: all-tests
# target: all-tests - Run all test suites
all-tests: test zeunit

.PHONY: test
# target: test - Run all Scala tests
# coverage is commented out due to conflict in dependencies it can be enabled
# when we update zio-config
test: build mkdir-artifacts
	@#sbt clean coverage test
	@sbt clean test
	@$(call to_artifacts,test-reports)

.PHONY: mkdir-artifacts
mkdir-artifacts:
	@mkdir -p $(ARTIFACTS_DIR)

.PHONY: check-fmt
# target: check-fmt - Check mis-formatted code
check-fmt: mkdir-artifacts
	@scalafmt --test | tee $(ARTIFACTS_DIR)/scalafmt.log
	@ec | tee $(ARTIFACTS_DIR)/editor-config.log

.PHONY: check-deps
# target: check-deps - Detect publicly disclosed vulnerabilities
check-deps: build mkdir-artifacts
	@sbt dependencyCheck
	@$(call to_artifacts,dependency-check-report.*)

.PHONY: check-spotbugs
# target: check-spotbugs - Run SpotBugs analysis for the source code
check-spotbugs: build mkdir-artifacts
	@sbt findbugs
	@$(call to_artifacts,findbugs-report.*)

.PHONY: cover
# target: cover - Generate code coverage report, options: TEST=<sub-project>
cover: build
ifeq ($(TEST),)
	@sbt coverage +test +coverageReport +coverageAggregate
	@open target/scala-$(SCALA_SHORT_VERSION)/scoverage-report/index.html
else
	@sbt coverage +${TEST}/test +${TEST}/coverageReport
	@open ${TEST}/target/scala-$(SCALA_SHORT_VERSION)/scoverage-report/index.html
endif

.PHONY: meta
meta: build mkdir-artifacts
	@sbt makeBom

.PHONY: jar
# target: jar - Generate JAR files for production
jar:
	@sbt assembly

.PHONY: jartest
# target: jartest - Generate JAR files containing tests
jartest:
	@sbt assembly -Djartest=true

# target: clean - Clean Java/Scala artifacts
clean:
	@rm -rf tmp
	@sbt clean

.PHONY: epmd
epmd:
	@ERL_EPMD_ADDRESS=$(ERL_EPMD_ADDRESS) epmd -daemon

# target: clean-all - Clean up the project to start afresh
clean-all:
	@sbt clean
	@echo '==> keep in mind that some state is stored in ~/.ivy2/cache/ and ~/.sbt'
	@echo '     and in  ~/Library/Caches/Coursier/v1/https/'
	@echo '    to fully clean the cache use `make clean-user-cache`'

clean-user-cache:
	@echo 'Removing ivy cache'
	@rm -rfv ~/.ivy2/cache/*
	@echo 'Removing all sbt lock files'
	@find ~/.sbt ~/.ivy2 -name "*.lock" -print -delete
	@find ~/.sbt ~/.ivy2 -name "ivydata-*.properties" -print -delete
	@echo 'Removing all the class files'
	@rm -fvr ~/.sbt/1.0/plugins/target
	@rm -fvr ~/.sbt/1.0/plugins/project/target
	@rm -fvr ~/.sbt/1.0/target
	@rm -fvr ~/.sbt/0.13/plugins/target
	@rm -fvr ~/.sbt/0.13/plugins/project/target
	@rm -fvr ~/.sbt/0.13/target
	@rm -fvr ./project/target
	@rm -fvr ./project/project/target
	@rm -fvr  ~/Library/Caches/Coursier/v1/https

.PHONY: clouseau1
# target: clouseau1 - Start local instance of clouseau1 node
clouseau1:
	@sbt run -Dnode=$@

.PHONY: clouseau2
# target: clouseau2 - Start local instance of clouseau2 node
clouseau2:
	@sbt run -Dnode=$@

.PHONY: clouseau3
# target: clouseau3 - Start local instance of clouseau3 node
clouseau3:
	@sbt run -Dnode=$@

.PHONY: help
# target: help - Print this help
help:
	@egrep "^# target: " Makefile \
		| sed -e 's/^# target: //g' \
		| sort \
		| awk '{printf("    %-20s", $$1); $$1=$$2=""; print "-" $$0}'

.PHONY: tree
# target: tree - Print project source tree
tree:
	@tree -I 'build' --matchdirs


# CI Pipeline
define docker_func
	@DOCKER_BUILDKIT=1 BUILDKIT_PROGRESS=plain docker build \
		--build-arg UBI_OPENJDK17_DIGEST=${UBI_OPENJDK17_DIGEST} \
		--build-arg ARTIFACTORY_USR=${ARTIFACTORY_USR} \
		--build-arg ARTIFACTORY_PSW=${ARTIFACTORY_PSW} \
		--build-arg DIRENV_VERSION=${DIRENV_VERSION} \
		--build-arg TERM=${TERM} \
		--build-arg CMDS=$(1) \
		$(DOCKER_ARGS) \
		-t ${PROJECT_NAME}:${GIT_COMMIT} \
		.
	@$(call extract,${PROJECT_NAME}:${GIT_COMMIT},/artifacts,.)
	@mkdir -p $(CI_ARTIFACTS_DIR)
endef

linter-in-docker: login-artifactory-docker
	@$(call docker_func,check-fmt)
	@cp $(ARTIFACTS_DIR)/*.log $(CI_ARTIFACTS_DIR)

build-in-docker: login-artifactory-docker
	@$(call docker_func,test)
	@find $(ARTIFACTS_DIR)/
	@cp -R $(ARTIFACTS_DIR)/* $(CI_ARTIFACTS_DIR)

check-deps-in-docker: login-artifactory-docker
	@$(call docker_func,check-deps)
	@cp $(ARTIFACTS_DIR)/experiments/dependency-check-report.xml \
		$(CI_ARTIFACTS_DIR)/dependency_check_report.experiments.xml
	@cp $(ARTIFACTS_DIR)/actors/dependency-check-report.xml \
		$(CI_ARTIFACTS_DIR)/dependency_check_report.actors.xml

check-spotbugs-in-docker: login-artifactory-docker
	@$(call docker_func,check-spotbugs)
	@cp $(ARTIFACTS_DIR)/actors/findbugs-report.* \
		$(CI_ARTIFACTS_DIR)/

# Authenticate with our Artifactory Docker registry before pulling any images
login-artifactory-docker: check-env-artifactory
	# For UBI images
	@echo "Docker login Artifactory (ubi)"
	@docker login -u "${ARTIFACTORY_USR}" -p "${ARTIFACTORY_PSW}" docker-na-public.artifactory.swg-devops.com/wcp-cloudant-registry-access-redhat-docker-remote

	# For all other (public) images
	@echo "Docker login Artifactory (docker hub)"
	@docker login -u "${ARTIFACTORY_USR}" -p "${ARTIFACTORY_PSW}" docker-na-public.artifactory.swg-devops.com/wcp-cloudant-registry-hub-docker-remote

check-env-artifactory:
	@if [ -z "$${ARTIFACTORY_USR}" ]; then echo "Error: ARTIFACTORY_USR is undefined"; exit 1; fi
	@if [ -z "$${ARTIFACTORY_PSW}" ]; then echo "Error: ARTIFACTORY_PSW is undefined"; exit 1; fi

# Required by CI's releng-pipeline-library
.PHONY: version
# target: version - Print current version
version:
	@echo $(PROJECT_VERSION)

.PHONY: zeunit
# target: zeunit - Run integration tests with ~/.erlang.cookie: `make zeunit`; otherwise `make zeunit cookie=<cookie>`
zeunit: jar
	@cli start $(node_name) "java -jar clouseau/target/scala-$(SCALA_SHORT_VERSION)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION).jar"
	@cli zeunit $(node_name) "$(EUNIT_OPTS)"

.PHONY: eshell
# target: eshell - Start erlang shell
eshell:
	@[ -z $(cookie) ] \
	&& (cd zeunit && $(REBAR) shell --name eshell@127.0.0.1) \
	|| (cd zeunit && $(REBAR) shell --name eshell@127.0.0.1 --setcookie $(cookie))

.PHONY: jconsole
# target: jconsole - Connect jconsole to running Clouseau
jconsole: CLOUSEAU_PID := $(shell jps -l | grep -F com.cloudant.ziose.clouseau.Main | cut -d' ' -f1)
jconsole:
	@[ "${CLOUSEAU_PID}" ] \
		|| ( echo '>>>>> clouseau is not running' ; exit 1 )
	@[ $(words $(CLOUSEAU_PID)) -eq 1 ] \
		|| ( echo '>>>>> more than one instance of clouseau is running' ; exit 1 )
	@jconsole $(CLOUSEAU_PID)

.PHONY: jlist
# target: jlist - List clouseau related java processes
jlist:
	@jps -l | grep com.cloudant.ziose || exit 0
