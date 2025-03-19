# Use bash to enable `read -d ''` option
SHELL := /bin/bash
PROJECT_NAME=ziose
CACHE?=true
BUILD_DIR=$(shell pwd)
ARTIFACTS_DIR?=$(BUILD_DIR)/artifacts
CI_ARTIFACTS_DIR=$(BUILD_DIR)/ci-artifacts
GIT_COMMIT?=$(shell git rev-parse HEAD)
GIT_REPOSITORY?=$(shell git config --get remote.origin.url)
DIRENV_VERSION := $(shell grep -F 'direnv' .tool-versions | awk '{print $$2}')
REBAR?=rebar3
ERLFMT?=erlfmt

COUCHDB_REPO?=https://github.com/apache/couchdb
COUCHDB_COMMIT?=main
COUCHDB_ROOT?=deps/couchdb
COUCHDB_CONFIGURE_ARGS?=--js-engine=quickjs --disable-docs --disable-fauxton --disable-spidermonkey

TIMEOUT_CLOUSEAU_SEC?=120
TIMEOUT_MANGO_TEST?=20m
TIMEOUT_ELIXIR_SEARCH?=20m

ERLANG_COOKIE?=	#

ifneq ($(JENKINS_URL),)
# CI invocation
	REGISTRY?=docker-na.artifactory.swg-devops.com/wcp-cloudant-registry-hub-docker-remote
	REQUIRE_ARTIFACTORY=true
else
# Local invocation
	REGISTRY?=docker.io
	REQUIRE_ARTIFACTORY=false
endif

ERL_SRCS?=$(shell git ls-files -- "*/rebar.config" "*.erl" "*.hrl" "*.app.src" "*.escript")
ifeq ($(PROJECT_VERSION),)
# technically we could use 'sbt -Dsbt.supershell=false -error "print version"'
# but it takes 30 seconds to run it. So we go with direct access
PROJECT_VERSION := $(shell cat $(BUILD_DIR)/version.sbt | sed -e \
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
	otp \
	scalang \
	vendor
BUILD_DATE?=$(shell date -u +"%Y-%m-%dT%TZ")
ERL_EPMD_ADDRESS?=127.0.0.1

node_name?=clouseau1
cookie=$(ERLANG_COOKIE)
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

KNOWN_CVEs = \

JAR_FILES := \
	clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION).jar

RELEASE_FILES := \
	$(JAR_FILES) \
	clouseau-$(PROJECT_VERSION)-dist.zip

JAR_ARTIFACTS := $(addprefix $(ARTIFACTS_DIR)/, $(JAR_FILES))
RELEASE_ARTIFACTS := $(addprefix $(ARTIFACTS_DIR)/, $(RELEASE_FILES))

CHECKSUM_FILES := $(foreach file, $(RELEASE_FILES), $(file).chksum)

comma := ,
empty :=
space := $(empty) $(empty)

apps ?= clouseau,core,macros,otp,scalang,vendor
skip ?= vendor
COMMON_PATH := /target/scala-$(SCALA_SHORT_VERSION)/classes
SPOTBUGS_OPTS = $(foreach app,$(filter-out $(subst $(comma),$(space),$(skip)),$(subst $(comma),$(space),$(apps))),$(app)$(COMMON_PATH))

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
		echo "Copying $${pathname} to $(ARTIFACTS_DIR)/$${project}" ; \
		cp -r "$${pathname}" "$(ARTIFACTS_DIR)/$${project}" ; \
	done
endef

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
all-tests: test zeunit couchdb-tests metrics-tests syslog-tests concurrent-zeunit-tests

.PHONY: test
# target: test - Run all Scala tests
test: build $(ARTIFACTS_DIR)
	@sbt shutdownall
	@sbt clean test
	@sbt shutdownall
	@$(call to_artifacts,test-reports)

$(ARTIFACTS_DIR):
	@mkdir -p $@

.PHONY: check-fmt
# target: check-fmt - Check mis-formatted code
check-fmt: $(ARTIFACTS_DIR)
	@scalafmt --test | tee $(ARTIFACTS_DIR)/scalafmt.log
	@ec | tee $(ARTIFACTS_DIR)/editor-config.log
	@$(ERLFMT) --verbose --check -- $(ERL_SRCS) | tee $(ARTIFACTS_DIR)/erlfmt.log

.PHONY: check-deps
# target: check-deps - Detect publicly disclosed vulnerabilities
check-deps: build $(ARTIFACTS_DIR)
	@sbt dependencyCheck
	echo "Finished dependency check"
	@find .
	@$(call to_artifacts,dependency-check-report.*)

.PHONY: check-spotbugs
# target: check-spotbugs - Inspect bugs in Java bytecode
check-spotbugs: build $(ARTIFACTS_DIR)
	@spotbugs -textui -quiet -html=$(ARTIFACTS_DIR)/spotbugs.html -xml=$(ARTIFACTS_DIR)/spotbugs.xml $(SPOTBUGS_OPTS)

.PHONY: meta
meta: build $(ARTIFACTS_DIR)
	@sbt makeBom

.PHONY: jar
# target: jar - Generate JAR files for production
jar: $(ARTIFACTS_DIR)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION).jar

.PHONY: jartest
# target: jartest - Generate JAR files containing tests
jartest: $(ARTIFACTS_DIR)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION)_test.jar

# target: clean - Clean Java/Scala artifacts
clean:
	@rm -rf tmp $(ARTIFACTS_DIR)/*
	@rm -f collectd/*.class collectd/*.out
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

ifneq ($(ERLANG_COOKIE),)
_COOKIE=-Dcookie=$(ERLANG_COOKIE)
else
_COOKIE=
endif

.PHONY: clouseau1
# target: clouseau1 - Start local instance of clouseau1 node
clouseau1:
	@sbt run -Dnode=$@ $(_COOKIE)

.PHONY: clouseau2
# target: clouseau2 - Start local instance of clouseau2 node
clouseau2:
	@sbt run -Dnode=$@ $(_COOKIE)

.PHONY: clouseau3
# target: clouseau3 - Start local instance of clouseau3 node
clouseau3:
	@sbt run -Dnode=$@ $(_COOKIE)

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
		--build-arg REGISTRY=${REGISTRY} \
		--build-arg TERM=${TERM} \
		--build-arg CMDS="$(1)" \
		$(DOCKER_ARGS) \
		-t ${PROJECT_NAME}:${GIT_COMMIT} \
		.
	@$(call extract,${PROJECT_NAME}:${GIT_COMMIT},/artifacts,.)
	@mkdir -p $(CI_ARTIFACTS_DIR)
endef

linter-in-docker: login-image-registry
	@$(call docker_func,check-fmt)
	@cp $(ARTIFACTS_DIR)/*.log $(CI_ARTIFACTS_DIR)

build-in-docker: login-image-registry
	@$(call docker_func,all-tests $(addprefix /artifacts/, $(RELEASE_FILES)))
	@cp -R $(ARTIFACTS_DIR)/* $(CI_ARTIFACTS_DIR)

bom-in-docker: login-image-registry
	@$(call docker_func,bom)
	find $(ARTIFACTS_DIR)
	find $(ARTIFACTS_DIR)/ -name '*.bom.xml' -exec cp '{}' $(CI_ARTIFACTS_DIR) ';'
	find $(CI_ARTIFACTS_DIR)

check-deps-in-docker: login-image-registry
	@$(call docker_func,check-deps)
	@$(call to_artifacts,*dependency-check-report.json)
	@$(call to_artifacts,*dependency-check-report.xml)

check-spotbugs-in-docker: login-image-registry
	@$(call docker_func,check-spotbugs)
	@cp $(ARTIFACTS_DIR)/spotbugs.* $(CI_ARTIFACTS_DIR)

# Required by CI's releng-pipeline-library
.PHONY: version
# target: version - Print current version
version:
	@echo $(PROJECT_VERSION)

.PHONY: zeunit
# target: zeunit - Run integration tests with ~/.erlang.cookie: `make zeunit`; otherwise `make zeunit cookie=<cookie>`
zeunit: $(ARTIFACTS_DIR)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION)_test.jar epmd
	@cli start $(node_name) "java -jar $<"
	@cli zeunit $(node_name) "$(EUNIT_OPTS)"
	@$(call to_artifacts,test-reports)

.PHONY: eshell
# target: eshell - Start erlang shell
eshell:
	@[ -z $(cookie) ] \
	&& (cd zeunit && $(REBAR) shell --name eshell@127.0.0.1) \
	|| (cd zeunit && $(REBAR) shell --name eshell@127.0.0.1 --setcookie $(cookie))

define clouseauPid
	sh -c "jps -l | grep -F com.cloudant.ziose.clouseau.Main | cut -d' ' -f1"
endef

.PHONY: jconsole
# target: jconsole - Connect jconsole to running Clouseau
jconsole: CLOUSEAU_PID := $(shell $(clouseauPid))
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

.PHONY: erlfmt-format
# target: erlfmt-format - Format Erlang code automatically
erlfmt-format:
	@$(ERLFMT) --write -- $(ERL_SRCS)

.PHONY: release
# target: release - Push release to github
release: $(RELEASE_ARTIFACTS) $(ARTIFACTS_DIR)/checksums.txt
	GH_DEBUG=1 GH_HOST=github.ibm.com gh release list --repo github.ibm.com/cloudant/ziose
	GH_DEBUG=1 GH_HOST=github.ibm.com gh release create "$(PROJECT_VERSION)" \
		--repo github.ibm.com/cloudant/ziose \
		--title "Release $(PROJECT_VERSION)" \
		--generate-notes $(RELEASE_ARTIFACTS) $(ARTIFACTS_DIR)/checksums.txt

$(ARTIFACTS_DIR)/clouseau-$(PROJECT_VERSION)-dist.zip: $(JAR_ARTIFACTS)
	@mkdir -p $(ARTIFACTS_DIR)/clouseau-$(PROJECT_VERSION)
	@cp $(ARTIFACTS_DIR)/*.jar $(ARTIFACTS_DIR)/clouseau-$(PROJECT_VERSION)
	@zip --junk-paths -r $@ $(ARTIFACTS_DIR)/clouseau-$(PROJECT_VERSION)

$(ARTIFACTS_DIR)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION).jar:
	@sbt assembly
	@cp clouseau/target/scala-$(SCALA_SHORT_VERSION)/$(@F) $@

$(ARTIFACTS_DIR)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION)_test.jar:
	@sbt assembly -Djartest=true
	@cp clouseau/target/scala-$(SCALA_SHORT_VERSION)/$(@F) $@

$(ARTIFACTS_DIR)/%.jar.chksum: $(ARTIFACTS_DIR)/%.jar
	@cd $(ARTIFACTS_DIR) && shasum -a 256 $(<F) > $(@F)

$(ARTIFACTS_DIR)/%.zip.chksum: $(ARTIFACTS_DIR)/%.zip
	@cd $(ARTIFACTS_DIR) && shasum -a 256 $(<F) > $(@F)

$(ARTIFACTS_DIR)/checksums.txt: $(addprefix $(ARTIFACTS_DIR)/, $(CHECKSUM_FILES))
	@cat $? > $@
	@cd $(ARTIFACTS_DIR)/ && shasum -a 256 -c checksums.txt

# Authenticate with our image registry before pulling any images
login-image-registry: check-env-docker
ifeq ($(REQUIRE_ARTIFACTORY),true)
	@echo "Docker login Artifactory"
	@docker login -u "${ARTIFACTORY_USR}" -p "${ARTIFACTORY_PSW}" "${REGISTRY}"
endif

check-env-docker:
ifeq ($(REQUIRE_ARTIFACTORY),true)
	@if [ -z "$${ARTIFACTORY_USR}" ]; then echo "Error: ARTIFACTORY_USR is undefined"; exit 1; fi
	@if [ -z "$${ARTIFACTORY_PSW}" ]; then echo "Error: ARTIFACTORY_PSW is undefined"; exit 1; fi
endif

.PHONY: ci-release
ci-release:
	@find .
	@make release

.PHONY: bom
bom:
	@sbt makeBom
	@$(call to_artifacts,*.bom.xml)

.PHONY: visualVM
# target: visualVM - Attach to running clouseau instance with VisualVM tool
visualVM: visualVM := $(shell mdfind -name 'VisualVM' -onlyin /Applications 2>/dev/null)
visualVM: CLOUSEAU_PID := $(shell $(clouseauPid))
visualVM:
	@[ "${CLOUSEAU_PID}" ] \
		|| ( echo '>>>>> clouseau is not running' ; exit 1 )
	@[ "$(visualVM)" ] \
		|| ( echo '>>>>> 'VisualVM' is not installed' ; exit 1 )
	@${visualVM}/Contents/MacOS/visualvm --jdkhome $(JAVA_HOME) --openpid $(CLOUSEAU_PID)

COUCHDB_DIR = $(COUCHDB_ROOT).$(COUCHDB_COMMIT)

$(COUCHDB_DIR)/.checked_out:
	@git clone $(COUCHDB_REPO) $(COUCHDB_DIR)
	@cd $(COUCHDB_DIR) && git checkout $(COUCHDB_COMMIT)
	@touch $(COUCHDB_DIR)/.checked_out

$(COUCHDB_DIR)/.configured: $(COUCHDB_DIR)/.checked_out
	@cd $(COUCHDB_DIR) && ./configure $(COUCHDB_CONFIGURE_ARGS)
	@touch $(COUCHDB_DIR)/.configured

$(COUCHDB_DIR)/.compiled: $(COUCHDB_DIR)/.configured
	@$(MAKE) -C $(COUCHDB_DIR)
	@touch $(COUCHDB_DIR)/.compiled

.PHONY: couchdb

couchdb: $(COUCHDB_DIR)/.compiled

.PHONY: couchdb-clean
couchdb-clean:
	@rm -rf $(COUCHDB_DIR)

start-clouseau: CLOUSEAU_PID := $(shell $(clouseauPid))
start-clouseau: couchdb
	@if [ -n "${CLOUSEAU_PID}" ]; then echo '>>>>>> Clouseau is already running'; exit 1; fi
	@mkdir -p $(COUCHDB_DIR)/dev/logs
	@echo '>>>>> Starting Clouseau...'
	@$(MAKE) clouseau1 > $(COUCHDB_DIR)/dev/logs/clouseau1.log 2>&1 &
	@for i in $$(seq 1 ${TIMEOUT_CLOUSEAU_SEC}); do \
		printf ">>>>>> Waiting... (%d seconds left)\n" $$(expr ${TIMEOUT_CLOUSEAU_SEC} - $$i); \
		sleep 1; \
		pid=$$($(value clouseauPid)); \
		[ -n "$$pid" ] && break; \
	done
	@echo '>>>>>> Clouseau started'

stop-clouseau: CLOUSEAU_PID := $(shell $(clouseauPid))
stop-clouseau:
	@echo '>>>>> Stopping Clouseau...'
	@if [ -z "${CLOUSEAU_PID}" ]; then echo '>>>>>> Clouseau is not running'; exit 1; fi
	@kill $(CLOUSEAU_PID)
	@for i in $$(seq 1 ${TIMEOUT_CLOUSEAU_SEC}); do \
		printf ">>>>>> Waiting... (%d seconds left)\n" $$(expr ${TIMEOUT_CLOUSEAU_SEC} - $$i); \
		sleep 1; \
		pid=$$($(value clouseauPid)); \
		if [ -z "$$pid" ]; then \
			echo '>>>>>> Clouseau stopped'; \
			break; \
		fi; \
	done

mango-test: couchdb
	@$(MAKE) -C $(COUCHDB_DIR) mango-test

elixir-search: couchdb
	@#                                       v-this is a hack
	@$(MAKE) -C $(COUCHDB_DIR) elixir-search _WITH_CLOUSEAU=-q

.PHONY: couchdb-tests-failed
couchdb-tests-failed:
	@$(MAKE) stop-clouseau
	@exit 1

.PHONY: couchdb-tests
# target: couchdb-tests - Run test suites from upstream CouchDB that use Clouseau
couchdb-tests: couchdb
	@$(MAKE) start-clouseau
	@timeout $(TIMEOUT_MANGO_TEST) $(MAKE) mango-test || $(MAKE) couchdb-tests-failed
	@timeout $(TIMEOUT_ELIXIR_SEARCH) $(MAKE) elixir-search || $(MAKE) couchdb-tests-failed
	@$(MAKE) stop-clouseau

collectd/clouseau.class: collectd/clouseau.java
	javac -source 1.7 -target 1.7 "$<"

.PHONY: metrics-tests-failed
metrics-tests-failed:
	@cli stop $(node_name)
	@exit 1

.PHONY: metrics-tests
# target: metrics-tests - Run JMX metrics collection tests
metrics-tests: $(ARTIFACTS_DIR)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION).jar collectd/clouseau.class
	@chmod 600 jmxremote.password
	@cli start $(node_name) \
		"java \
       -Dcom.sun.management.jmxremote.port=9090 \
       -Dcom.sun.management.jmxremote.ssl=false \
       -Dcom.sun.management.jmxremote.password.file=jmxremote.password \
       -jar $(ARTIFACTS_DIR)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION).jar" > /dev/null
	@echo "Warming up Clouseau to expose all the metrics"
	@timeout $(TIMEOUT_MANGO_TEST) $(MAKE) mango-test || $(MAKE) metrics-tests-failed
	@echo "Collecting metrics"
	@java -cp collectd clouseau "service:jmx:rmi:///jndi/rmi://localhost:9090/jmxrmi" monitorRole password > collectd/metrics.out
	@cli stop $(node_name)
	@echo "Comparing collected metrics with expectations:"
	@if diff -u collectd/metrics.out collectd/metrics.expected; then \
		echo "Everything is in order"; \
	fi

FORCE: # https://www.gnu.org/software/make/manual/html_node/Force-Targets.html

syslog-test: $(ARTIFACTS_DIR)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION).jar FORCE
	@sed \
	  -e "s/%%FORMAT%%/$(FORMAT)/" \
	  -e "s/%%PROTOCOL%%/$(PROTOCOL)/" \
	  -e "s/%%HOST%%/$(HOST)/" \
	  -e "s/%%PORT%%/$(PORT)/" \
	  -e "s/%%FACILITY%%/$(FACILITY)/" \
	  -e "s/%%LEVEL%%/$(LEVEL)/" \
	  syslog.app.conf.templ > syslog.app.conf
	@cli start $(node_name) "java -jar $< syslog.app.conf"
	@echo ">>> Waiting for Clouseau to generate logs (5 seconds)"
	@sleep 5
	@cli stop $(node_name)
	@if grep -Fq "Clouseau running as clouseau1@127.0.0.1" syslog.out; then \
		echo ">>> Log events received!"; \
	else \
		echo ">>> FAILED to receive log events!"; \
		exit 1; \
	fi

.PHONY: syslog-tests
# target: syslog-tests - Run syslog output tests
syslog-tests:
	@echo "Syslog test case: TCP/PlainText"
	@nc -l 127.0.0.1 2000 > syslog.out &
	@echo ">>> Receiver started"
	@$(MAKE) syslog-test FORMAT=PlainText PROTOCOL=TCP HOST=127.0.0.1 PORT=2000 FACILITY=LOCAL5 LEVEL=info

	@echo "Syslog test case: UDP/JSON"
	@nc -lu 127.0.0.1 2000 > syslog.out &
	@echo ">>> Receiver started"
	@$(MAKE) syslog-test FORMAT=JSON PROTOCOL=UDP HOST=127.0.0.1 PORT=2000 FACILITY=LOCAL5 LEVEL=info

concurrent-zeunit-tests: $(ARTIFACTS_DIR)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION)_test.jar epmd FORCE
	@cli start $(node_name) "java -jar $< concurrent.app.conf"
	@cli zeunit $(node_name) "$(EUNIT_OPTS)"
