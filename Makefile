# Use bash to enable `read -d ''` option
SHELL := /bin/bash
PROJECT_NAME=ziose
CACHE?=true
BUILD_DIR=$(shell pwd)
ARTIFACTS_DIR=$(BUILD_DIR)/artifacts
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

SCALA_SUBPROJECTS := clouseau core otp scalang
ALL_SUBPROJECTS := $(SCALA_SUBPROJECTS) test

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

ENCODED_GH_USR=$(shell echo ${GH_USR} | sed 's/@/%40/g' )
GH_AUTH_URL=https://${ENCODED_GH_USR}:${GH_PSW}@github.com

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
	find $(1) -name '$(2)' -print0 | while IFS= read -r -d '' pathname; \
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
all-tests: test zeunit couchdb-tests metrics-tests syslog-tests compatibility-tests concurrent-zeunit-tests restart-test

.PHONY: test
# target: test - Run all Scala tests
test: build $(ARTIFACTS_DIR)
	@sbt clean test
	@$(call to_artifacts,$(ALL_SUBPROJECTS),test-reports)

$(ARTIFACTS_DIR):
	@mkdir -p $@

$(CI_ARTIFACTS_DIR):
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
	@$(call to_artifacts,$(SCALA_SUBPROJECTS),dependency-check-report.*)

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
_JAVA_COOKIE=-Dcookie=$(ERLANG_COOKIE)
_DEVRUN_COOKIE=--erlang-cookie=$(ERLANG_COOKIE)
_ERLCALL_COOKIE=-c $(ERLANG_COOKIE)
else
_JAVA_COOKIE=
_DEVRUN_COOKIE=
_ERLCALL_COOKIE=
endif

.PHONY: clouseau1
# target: clouseau1 - Start local instance of clouseau1 node
clouseau1:
	@sbt run -Dnode=$@ $(_JAVA_COOKIE)

.PHONY: clouseau2
# target: clouseau2 - Start local instance of clouseau2 node
clouseau2:
	@sbt run -Dnode=$@ $(_JAVA_COOKIE)

.PHONY: clouseau3
# target: clouseau3 - Start local instance of clouseau3 node
clouseau3:
	@sbt run -Dnode=$@ $(_JAVA_COOKIE)

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

ci-lint: check-fmt $(CI_ARTIFACTS_DIR)
	@cp $(ARTIFACTS_DIR)/*.log $(CI_ARTIFACTS_DIR)

ci-build: artifacts $(CI_ARTIFACTS_DIR)
	@echo ci-build
	@cp $(ARTIFACTS_DIR)/*.jar $(CI_ARTIFACTS_DIR)/
	@cp $(ARTIFACTS_DIR)/*.zip $(CI_ARTIFACTS_DIR)/
	@find $(CI_ARTIFACTS_DIR)
	@find . -name scala-2.13 -type d | xargs find

ci-unit: test $(CI_ARTIFACTS_DIR)
	@echo ci-unit
	@for dir in $(ALL_SUBPROJECTS); do \
    cp -R $(ARTIFACTS_DIR)/$$dir/ $(CI_ARTIFACTS_DIR)/ || true; \
  done

ci-zeunit: zeunit $(CI_ARTIFACTS_DIR)
	@cp -R $(ARTIFACTS_DIR)/zeunit/ $(CI_ARTIFACTS_DIR)/

ci-mango: $(ARTIFACTS_DIR)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION).jar couchdb epmd FORCE
	@cli start $@ "java $(_JAVA_COOKIE) -jar $<"
	@cli await $(node_name) "$(ERLANG_COOKIE)"
	@timeout $(TIMEOUT_MANGO_TEST) $(MAKE) mango-test || $(MAKE) test-failed ID=$@
	@cli stop $@

ci-elixir: $(ARTIFACTS_DIR)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION).jar couchdb epmd FORCE
	@cli start $@ "java $(_JAVA_COOKIE) -jar $<"
	@cli await $(node_name) "$(ERLANG_COOKIE)"
	@timeout $(TIMEOUT_ELIXIR_SEARCH) $(MAKE) elixir-search || $(MAKE) test-failed ID=$@
	@cli stop $@

ci-metrics: metrics-tests

ci-verify: check-spotbugs

ci-syslog: syslog-tests

ci-concurrent-zeunit: concurrent-zeunit-tests

ci-restart: restart-test

linter-in-docker: login-image-registry
	@$(call docker_func,check-fmt)
	@cp $(ARTIFACTS_DIR)/*.log $(CI_ARTIFACTS_DIR)

build-in-docker: login-image-registry
	@$(call docker_func,artifacts $(addprefix /artifacts/, $(RELEASE_FILES)))
	@cp -R $(ARTIFACTS_DIR)/* $(CI_ARTIFACTS_DIR)

bom-in-docker: login-image-registry
	@$(call docker_func,bom)
	find $(ARTIFACTS_DIR)
	find $(ARTIFACTS_DIR)/ -name '*.bom.xml' -exec cp '{}' $(CI_ARTIFACTS_DIR) ';'
	find $(CI_ARTIFACTS_DIR)

check-deps-in-docker: login-image-registry
	@$(call docker_func,check-deps)
	@$(call to_artifacts,$(SCALA_SUBPROJECTS),*dependency-check-report.json)
	@$(call to_artifacts,$(SCALA_SUBPROJECTS),*dependency-check-report.xml)

check-spotbugs-in-docker: login-image-registry
	@$(call docker_func,check-spotbugs)
	@cp $(ARTIFACTS_DIR)/spotbugs.* $(CI_ARTIFACTS_DIR)

# Required by CI's releng-pipeline-library
.PHONY: version
# target: version - Print current version
version:
	@echo $(PROJECT_VERSION)

.PHONY: restart-test
# target: restart-test - Test Clouseau terminates properly by repeatedly starting and stopping it (RETRY=30)
restart-test: $(ARTIFACTS_DIR)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION).jar epmd FORCE
	@restart-test $<

.PHONY: zeunit
# target: zeunit - Run integration tests with ~/.erlang.cookie: `make zeunit`; otherwise `make zeunit cookie=<cookie>`
zeunit: $(ARTIFACTS_DIR)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION)_test.jar epmd FORCE
	@cli start $@ "java $(_JAVA_COOKIE) -jar $<"
	@cli zeunit $(node_name) "$(EUNIT_OPTS)" || $(MAKE) test-failed ID=$@
	@$(call to_artifacts,zeunit,test-reports)
	@cli stop $@

.PHONY: eshell
# target: eshell - Start erlang shell
eshell:
	@[ -z $(cookie) ] \
	&& (cd zeunit && $(REBAR) shell --name eshell@127.0.0.1) \
	|| (cd zeunit && $(REBAR) shell --name eshell@127.0.0.1 --setcookie $(cookie))

define clouseauPid
	sh -c "jcmd | grep -F clouseau | cut -d' ' -f1"
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
	@jcmd | grep clouseau || exit 0

.PHONY: jstack
# target: jstack - List of threads for running clouseau
jstack: CLOUSEAU_PID := $(shell $(clouseauPid))
jstack:
	@jstack -l $(CLOUSEAU_PID)

.PHONY: tdump
# target: tdump - Capture thread dumps
tdump: CLOUSEAU_PID := $(shell $(clouseauPid))
tdump: $(ARTIFACTS_DIR)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION)_test.jar
	@cli start $(node_name) "java -jar $<"
	@echo "Generate thread dumps..."
	@rm -f tmp/thread_dump*.log
	@for i in {1..3}; do \
		echo "=== Captured dump $$i at $$(date) ===" >> tmp/thread_dump$$i.log; \
		cli tdump $(node_name) tmp/thread_dump$$i.log; \
		for j in {1..10}; do \
			echo -n "."; \
			sleep 1; \
		done; \
	done
	@cli stop $(node_name)
	@echo ""
	@echo "Thread dump collection completed."
	@echo 'Please check "tmp/thread_dump*.log".'

.PHONY: erlfmt-format
# target: erlfmt-format - Format Erlang code automatically
erlfmt-format:
	@$(ERLFMT) --write -- $(ERL_SRCS)

.PHONY: artifacts
# target: artifacts - Generate release artifacts
artifacts: $(ARTIFACTS_DIR) $(RELEASE_ARTIFACTS) $(ARTIFACTS_DIR)/checksums.txt

.PHONY: release
# target: release - Push release to github
release: $(RELEASE_ARTIFACTS) $(ARTIFACTS_DIR)/checksums.txt
	GH_DEBUG=1 GH_HOST=github.com gh release list --repo github.com/cloudant/ziose
	GH_DEBUG=1 GH_HOST=github.com gh release create "$(PROJECT_VERSION)" \
		--target "$$(git rev-parse HEAD)" \
		--repo github.com/cloudant/ziose \
		--title "Release $(PROJECT_VERSION)" \
		--generate-notes $(RELEASE_ARTIFACTS) $(ARTIFACTS_DIR)/checksums.txt

$(ARTIFACTS_DIR)/clouseau-$(PROJECT_VERSION)-dist.zip: $(JAR_ARTIFACTS)
	@mkdir -p $(ARTIFACTS_DIR)/clouseau-$(PROJECT_VERSION)
	@cp $(ARTIFACTS_DIR)/*.jar $(ARTIFACTS_DIR)/clouseau-$(PROJECT_VERSION)
	@zip --junk-paths -r $@ $(ARTIFACTS_DIR)/clouseau-$(PROJECT_VERSION)

$(ARTIFACTS_DIR)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION).jar: $(ARTIFACTS_DIR)
	@sbt assembly
	@cp clouseau/target/scala-$(SCALA_SHORT_VERSION)/$(@F) $@

$(ARTIFACTS_DIR)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION)_test.jar: $(ARTIFACTS_DIR)
	@sbt assembly -Djartest=true
	@cp clouseau/target/scala-$(SCALA_SHORT_VERSION)/$(@F) $@

$(ARTIFACTS_DIR)/%.jar.chksum: $(ARTIFACTS_DIR)/%.jar
	@cd $(ARTIFACTS_DIR) && sha256sum $(<F) > $(@F)

$(ARTIFACTS_DIR)/%.zip.chksum: $(ARTIFACTS_DIR)/%.zip
	@cd $(ARTIFACTS_DIR) && sha256sum $(<F) > $(@F)

$(ARTIFACTS_DIR)/checksums.txt: $(addprefix $(ARTIFACTS_DIR)/, $(CHECKSUM_FILES))
	@cat $? > $@
	@cd $(ARTIFACTS_DIR)/ && sha256sum -c checksums.txt

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
	@$(call to_artifacts,$(SCALA_SUBPROJECTS),*.bom.xml)

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
	@mkdir -p $(COUCHDB_DIR)/dev/logs

.PHONY: couchdb-clean
couchdb-clean:
	@rm -rf $(COUCHDB_DIR)

$(COUCHDB_DIR)/src/mango/.venv: couchdb
	@python3 -m venv $@
	@$@/bin/pip3 install --upgrade pip wheel setuptools
	@$@/bin/pip3 install -r $(COUCHDB_DIR)/src/mango/requirements.txt
	@$@/bin/pip3 install nose-exclude

mango-test: $(COUCHDB_DIR)/src/mango/.venv
	@$(MAKE) -C $(COUCHDB_DIR) all
	@$(COUCHDB_DIR)/dev/run \
		-n 1 \
		--admin=adm:pass \
		$(_DEVRUN_COOKIE) \
		--no-eval "\
COUCH_USER=adm COUCH_PASS=pass \
$(COUCHDB_DIR)/src/mango/.venv/bin/nose2 -F -s $(COUCHDB_DIR)/src/mango/test -c test/mango/unittest.cfg"

elixir-search: couchdb
	@#                                       v-this is a hack
	@$(MAKE) -C $(COUCHDB_DIR) elixir-search _WITH_CLOUSEAU=-q ERLANG_COOKIE=$(ERLANG_COOKIE)

.PHONY: test-failed
test-failed:
	@echo "The thread dump before attempt to force the shutdown"
	@cli tdump $(ID)
	@epmd -stop $(ID) >/dev/null 2>&1 || true
	@cli stop $(ID)
	@echo ">>>> The test failed below are the process logs"
	@cat $(shell cli logs $(ID))
	@exit 1

.PHONY: couchdb-tests
# target: couchdb-tests - Run test suites from upstream CouchDB that use Clouseau
couchdb-tests: $(ARTIFACTS_DIR)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION).jar couchdb epmd FORCE
	@cli start $@ "java $(_JAVA_COOKIE) -jar $<"
	@cli await $(node_name) "$(ERLANG_COOKIE)"
	@timeout $(TIMEOUT_MANGO_TEST) $(MAKE) mango-test || $(MAKE) test-failed ID=$@
	@timeout $(TIMEOUT_ELIXIR_SEARCH) $(MAKE) elixir-search || $(MAKE) test-failed ID=$@
	@cli stop $@

collectd/clouseau.class: collectd/clouseau.java
	javac -source 8 -target 8 "$<"

.PHONY: metrics-tests
# target: metrics-tests - Run JMX metrics collection tests
metrics-tests: $(ARTIFACTS_DIR)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION).jar collectd/clouseau.class epmd FORCE
	@chmod 600 jmxremote.password
	@cli start $@ \
		"java \
       -Dcom.sun.management.jmxremote.port=9090 \
       -Dcom.sun.management.jmxremote.ssl=false \
       -Dcom.sun.management.jmxremote.password.file=jmxremote.password \
       -jar $(ARTIFACTS_DIR)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION).jar" > /dev/null
	@cli await $(node_name) "$(ERLANG_COOKIE)"
	@echo "Warming up Clouseau to expose all the metrics"
	@timeout $(TIMEOUT_MANGO_TEST) $(MAKE) mango-test || $(MAKE) test-failed ID=$@
	@echo "Collecting metrics"
	@java -cp collectd clouseau "service:jmx:rmi:///jndi/rmi://localhost:9090/jmxrmi" monitorRole password > collectd/metrics.out
	@cli stop $@
	@echo "Comparing collected metrics with expectations:"
	@if diff -u collectd/metrics.out collectd/metrics.expected; then \
		echo "Everything is in order"; \
	fi

FORCE: # https://www.gnu.org/software/make/manual/html_node/Force-Targets.html

syslog-test: $(ARTIFACTS_DIR)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION).jar epmd FORCE
	@sed \
	  -e "s/%%FORMAT%%/$(FORMAT)/" \
	  -e "s/%%PROTOCOL%%/$(PROTOCOL)/" \
	  -e "s/%%HOST%%/$(HOST)/" \
	  -e "s/%%PORT%%/$(PORT)/" \
	  -e "s/%%FACILITY%%/$(FACILITY)/" \
	  -e "s/%%LEVEL%%/$(LEVEL)/" \
	  syslog.app.conf.templ > syslog.app.conf
	@cli start $@ "java -jar $< syslog.app.conf"
	@cli await $(node_name) "$(ERLANG_COOKIE)" || $(MAKE) test-failed ID=$@
	@echo ">>> Waiting for Clouseau to generate logs (5 seconds)"
	@sleep 5
	@cli stop $@
	@if grep -Fq "Clouseau running as clouseau1@127.0.0.1" syslog.out; then \
		echo ">>> Log events received!"; \
	else \
		echo ">>> FAILED to receive log events!"; \
		exit 1; \
	fi

.PHONY: syslog-tests
# target: syslog-tests - Run syslog output tests
syslog-tests:
	@echo "Syslog test case: TCP/Raw"
	@nc -l 127.0.0.1 2000 > syslog.out &
	@echo ">>> Receiver started"
	@$(MAKE) syslog-test FORMAT=Raw PROTOCOL=TCP HOST=127.0.0.1 PORT=2000 FACILITY=LOCAL5 LEVEL=info

	@echo "Syslog test case: UDP/JSON"
	@nc -lu 127.0.0.1 2000 > syslog.out &
	@echo ">>> Receiver started"
	@$(MAKE) syslog-test FORMAT=JSON PROTOCOL=UDP HOST=127.0.0.1 PORT=2000 FACILITY=LOCAL5 LEVEL=info

concurrent-zeunit-tests: $(ARTIFACTS_DIR)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION)_test.jar epmd FORCE
	@cli start $@ "java $(_JAVA_COOKIE) -jar $< concurrent.app.conf"
	@cli zeunit $(node_name) "$(EUNIT_OPTS)" || $(MAKE) test-failed ID=$@
	@cli stop $@

sup-test: couchdb
	@$(COUCHDB_DIR)/dev/run \
		-q \
		-n 1 \
		--admin=adm:pass \
		$(_DEVRUN_COOKIE) \
		--no-eval "\
echo \"Ref = make_ref(), {sup, 'clouseau1@127.0.0.1'} ! {ping, self(), Ref}, receive {pong, Ref} -> success end.\" | erl_call $(_ERLCALL_COOKIE) -timeout 1 -n node1@127.0.0.1 -e"

.PHONY: compatibility-tests
# target: compatibility-tests - Run Clouseau 2.x compatibility tests
compatibility-tests: $(ARTIFACTS_DIR)/clouseau_$(SCALA_VERSION)_$(PROJECT_VERSION).jar couchdb epmd FORCE
	@cli start $@ "java $(_JAVA_COOKIE) -jar $<"
	@cli await $(node_name) "$(ERLANG_COOKIE)"
	@$(MAKE) sup-test || $(MAKE) test-failed ID=$@
	@cli stop $@
