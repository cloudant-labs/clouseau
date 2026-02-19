# Use bash to enable `read -d ''` option
SHELL := /bin/bash

BUILD_DIR=$(shell pwd)
ARTIFACTS_DIR=$(BUILD_DIR)/artifacts
CI_ARTIFACTS_DIR=$(BUILD_DIR)/ci-artifacts

COUCHDB_REPO?=https://github.com/apache/couchdb
COUCHDB_COMMIT?=3.5.1
COUCHDB_ROOT?=deps/couchdb
COUCHDB_CONFIGURE_ARGS?=--dev --disable-spidermonkey

OWASP_NVD_UPDATE?=false

ifneq "$(wildcard /opt/homebrew/var/dependency-check)" ""
OWASP_NVD_DATA_DIR?=/opt/homebrew/var/dependency-check/data
else
OWASP_NVD_DATA_DIR?=/usr/share/dependency-check/data
endif

TIMEOUT?=timeout --foreground
TIMEOUT_MANGO_TEST?=20m
TIMEOUT_ELIXIR_SEARCH?=20m

REBAR?=rebar3

ERLANG_COOKIE?= #
ifneq ($(ERLANG_COOKIE),)
	_DEVRUN_COOKIE=--erlang-cookie=$(ERLANG_COOKIE)
	_ERLCALL_COOKIE=-c $(ERLANG_COOKIE)
	_JAVA_COOKIE=-Dcookie=$(ERLANG_COOKIE)
	_REBAR_COOKIE=--setcookie=$(ERLANG_COOKIE)
else
	_DEVRUN_COOKIE=
	_ERLCALL_COOKIE=
	_JAVA_COOKIE=
	_REBAR_COOKIE=
endif

ifeq ($(PROJECT_VSN),)
	# technically we could use 'sbt -Dsbt.supershell=false -error "print version"'
	# but it takes 30 seconds to run it. So we go with direct access
	PROJECT_VSN := $(shell cat $(BUILD_DIR)/version.sbt | sed -e \
		'/ThisBuild[[:space:]]*[/][[:space:]]*version[[:space:]]*[:]=[[:space:]]*/!d' \
		-e "s///g" \
		-e 's/\"//g')
endif

SCALA_VSN := $(shell cat $(BUILD_DIR)/build.sbt | sed -e \
	'/ThisBuild[[:space:]]*[/][[:space:]]*scalaVersion[[:space:]]*[:]=[[:space:]]*/!d' \
	-e "s///g" \
	-e 's/\"//g')

SCALA_VSN_PARTS := $(subst ., ,$(SCALA_VSN))

SCALA_MAJOR := $(word 1,$(SCALA_VSN_PARTS))
SCALA_MINOR := $(word 2,$(SCALA_VSN_PARTS))
SCALA_MICRO := $(word 3,$(SCALA_VSN_PARTS))

SCALA_SHORT_VSN := $(SCALA_MAJOR).$(SCALA_MINOR)

SCALA_SUBPROJECTS := clouseau core otp scalang
ALL_SUBPROJECTS := $(SCALA_SUBPROJECTS) test

node_name ?= clouseau1
# Rebar options
suites=
tests=
# Use `suites` instead of `module` to keep consistency with CouchDB
EUNIT_OPTS := "$(_REBAR_COOKIE) --module=$(suites) --test=$(tests)"

JAR_PROD := clouseau_$(SCALA_VSN)_$(PROJECT_VSN).jar
JAR_TEST := clouseau_$(SCALA_VSN)_$(PROJECT_VSN)_test.jar

RELEASE_FILES := $(JAR_PROD) \
	clouseau-$(PROJECT_VSN)-dist.zip

JAR_ARTIFACTS := $(addprefix $(ARTIFACTS_DIR)/, $(JAR_PROD))
RELEASE_ARTIFACTS := $(addprefix $(ARTIFACTS_DIR)/, $(RELEASE_FILES))

CHECKSUM_FILES := $(foreach file, $(RELEASE_FILES), $(file).chksum)

JMX_EXPORTER_VSN ?= 1.5.0
JMX_EXPORTER ?= jmx_prometheus_javaagent-$(JMX_EXPORTER_VSN).jar
JMX_EXPORTER_CFG ?= prometheus/jmx_exporter.yaml
JMX_EXPORTER_PORT ?= 8080
JMX_EXPORTER_URL := https://github.com/prometheus/jmx_exporter/releases/download/$(JMX_EXPORTER_VSN)/$(JMX_EXPORTER)

comma := ,
empty :=
space := $(empty) $(empty)
apps ?= clouseau,core,macros,otp,scalang,vendor
skip ?= vendor
COMMON_PATH := /target/scala-$(SCALA_SHORT_VSN)/classes
SPOTBUGS_OPTS = $(foreach app,$(filter-out \
		$(subst $(comma),$(space),$(skip)),\
		$(subst $(comma),$(space),$(apps))\
	),$(app)$(COMMON_PATH))

define to_artifacts
	find $(1) -name '$(2)' -print0 | while IFS= read -r -d '' pathname; \
	do \
		project=$$(echo "$${pathname}" | cut -d "/" -f1) ; \
		mkdir -p "$(ARTIFACTS_DIR)/$${project}"; \
		echo "Copying $${pathname} to $(ARTIFACTS_DIR)/$${project}" ; \
		cp -r "$${pathname}" "$(ARTIFACTS_DIR)/$${project}" ; \
	done
endef

.PHONY: build
# target: build - Build package, run tests and create distribution
build: epmd
	@sbt compile

ERL_EPMD_ADDRESS?=127.0.0.1

.PHONY: epmd
epmd:
	@ERL_EPMD_ADDRESS=$(ERL_EPMD_ADDRESS) epmd -daemon

.PHONY: clouseau1 clouseau2 clouseau3
# target: clouseau1 - Start local instance of clouseau1 node
# target: clouseau2 - Start local instance of clouseau2 node
# target: clouseau3 - Start local instance of clouseau3 node
clouseau1 clouseau2 clouseau3: epmd
	@sbt run -Dnode=$@ $(_JAVA_COOKIE)

$(ARTIFACTS_DIR):
	@mkdir -p $@

$(CI_ARTIFACTS_DIR):
	@mkdir -p $@

ERL_SRCS?=$(shell git ls-files -- "*/rebar.config" "*.[e,h]rl" "*.app.src" "*.escript")

.PHONY: check-fmt
# target: check-fmt - Check mis-formatted code
check-fmt: $(ARTIFACTS_DIR)
	@set -o pipefail; scalafmt --test | tee $(ARTIFACTS_DIR)/scalafmt.log
	@set -o pipefail; ec | tee $(ARTIFACTS_DIR)/editor-config.log
	@set -o pipefail; erlfmt --verbose --check -- $(ERL_SRCS) | tee $(ARTIFACTS_DIR)/erlfmt.log

.PHONY: erlfmt-format
# target: erlfmt-format - Format Erlang code automatically
erlfmt-format:
	@erlfmt --write -- $(ERL_SRCS)

.PHONY: scalafmt-format
# target: scalafmt-format - Format Scala code automatically
scalafmt-format:
	@scalafmt --quiet

.PHONY: format-code
# target: format-code - Format source code automatically
format-code: erlfmt-format scalafmt-format

.PHONY: check-deps
# target: check-deps - Detect publicly disclosed vulnerabilities
check-deps: build $(ARTIFACTS_DIR)
	@sbt dependencyCheck \
		-Dnvd_update=$(OWASP_NVD_UPDATE) \
		-Dnvd_data_dir=$(OWASP_NVD_DATA_DIR) \
		-Dlog4j2.level=info
	@$(call to_artifacts,$(SCALA_SUBPROJECTS),dependency-check-report.*)

.PHONY: check-spotbugs
# target: check-spotbugs - Inspect bugs in Java bytecode
check-spotbugs: build $(ARTIFACTS_DIR)
	@spotbugs -textui -quiet -html=$(ARTIFACTS_DIR)/spotbugs.html \
		-xml=$(ARTIFACTS_DIR)/spotbugs.xml $(SPOTBUGS_OPTS)

.PHONY: docs
# target: docs - Generate documentation
docs: $(ARTIFACTS_DIR)/book.pdf

$(ARTIFACTS_DIR)/book.pdf: docs/book $(ARTIFACTS_DIR)
	@cd docs/book/ && typst compile book.typ $@

.PHONY: jar
# target: jar - Generate JAR files for production
jar: $(ARTIFACTS_DIR)/$(JAR_PROD)

.PHONY: jartest
# target: jartest - Generate JAR files containing tests
jartest: $(ARTIFACTS_DIR)/$(JAR_TEST)

.PHONY: jmx-prometheus
# target: jmx-prometheus - Export metrics to Prometheus
jmx-prometheus: download-jmx-exporter epmd $(JAR_ARTIFACTS)
	@java -javaagent:$(JMX_EXPORTER)=$(JMX_EXPORTER_PORT):$(JMX_EXPORTER_CFG) \
		-jar $(JAR_ARTIFACTS)

download-jmx-exporter:
	@wget -nc $(JMX_EXPORTER_URL) -O $(JMX_EXPORTER) >/dev/null 2>&1 || true

.PHONY: bin/clouseau_ctrl
bin/clouseau_ctrl: clouseau-ctrl/_build/default/bin/clouseau_ctrl
	@cp $? $@

clouseau-ctrl/_build/default/bin/clouseau_ctrl: clouseau-ctrl/src
	@cd clouseau-ctrl/ && $(REBAR) escriptize

$(ARTIFACTS_DIR)/$(JAR_PROD): $(ARTIFACTS_DIR)
	@sbt assembly
	@cp clouseau/target/scala-$(SCALA_SHORT_VSN)/$(@F) $@
	@javap -classpath $@ com.cloudant.ziose.clouseau.EchoService \
		| grep -q 'public boolean isProduction' \
		|| ( echo '>>>>> incorrect override EchoService' ; exit 1 )

$(ARTIFACTS_DIR)/$(JAR_TEST): $(ARTIFACTS_DIR)
	@sbt assembly -Djartest=true
	@cp clouseau/target/scala-$(SCALA_SHORT_VSN)/$(@F) $@
	@javap -classpath $@ com.cloudant.ziose.clouseau.EchoService \
		| grep -q 'public boolean isTest' \
		|| ( echo '>>>>> incorrect override EchoService' ; exit 1 )

# target: clean - Clean Java/Scala artifacts
clean:
	@rm -rf tmp $(ARTIFACTS_DIR)/*
	@rm -f collectd/*.class collectd/*.out
	@rm -rf bin/clouseau_ctrl ; cd clouseau-ctrl && $(REBAR) clean
	@sbt clean

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

.PHONY: help
# target: help - Print this help
help:
	@egrep "^# target: " Makefile \
		| sed -e 's/^# target: //g' \
		| sort \
		| awk '{printf("    %-20s", $$1); $$1=$$2=""; print "-" $$0}'


############### Tests ###############
.PHONY: all-tests
# target: all-tests - Run all test suites
all-tests: test zeunit concurrent-zeunit-tests restart-test syslog-tests
all-tests: couchdb-tests metrics-tests compatibility-tests

.PHONY: test
# target: test - Run all Scala tests
test: build $(ARTIFACTS_DIR)
	@sbt clean test
	@$(call to_artifacts,$(ALL_SUBPROJECTS),test-reports)

FORCE: # https://www.gnu.org/software/make/manual/html_node/Force-Targets.html

.PHONY: zeunit
# target: zeunit - Run integration tests: `<ERLANG_COOKIE=cookie> make zeunit`
zeunit: $(ARTIFACTS_DIR)/$(JAR_TEST) epmd FORCE
	@cli start $@ "java $(_JAVA_COOKIE) -jar $<"
	@sleep 5
	@cli await $(node_name) "$(ERLANG_COOKIE)"
	@cli zeunit $(node_name) "$(EUNIT_OPTS)" || $(MAKE) test-failed ID=$@
	@$(call to_artifacts,zeunit,test-reports)
	@cli stop $@

concurrent-zeunit-tests: $(ARTIFACTS_DIR)/$(JAR_TEST) epmd FORCE
	@cli start $@ "java $(_JAVA_COOKIE) -jar $< concurrent.app.conf"
	@sleep 5
	@cli await $(node_name) "$(ERLANG_COOKIE)"
	@cli zeunit $(node_name) "$(EUNIT_OPTS)" || $(MAKE) test-failed ID=$@
	@cli stop $@

.PHONY: restart-test
# target: restart-test - Test Clouseau terminates properly by repeatedly starting and stopping it (RETRY=30)
restart-test: $(JAR_ARTIFACTS) epmd FORCE
	@restart-test $<

syslog-test: $(JAR_ARTIFACTS) epmd FORCE
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

COUCHDB_DIR = $(COUCHDB_ROOT).$(COUCHDB_COMMIT)

$(COUCHDB_DIR)/.checked_out:
	@git clone $(COUCHDB_REPO) $(COUCHDB_DIR)
	@cd $(COUCHDB_DIR) && git checkout $(COUCHDB_COMMIT)
	@touch $(COUCHDB_DIR)/.checked_out

$(COUCHDB_DIR)/.configured: $(COUCHDB_DIR)/.checked_out
	@cd $(COUCHDB_DIR) && ./configure $(COUCHDB_CONFIGURE_ARGS)
	@if [ ! -f $(COUCHDB_DIR)/bin/rebar3 ]; then \
		REBAR3_PATH=$$(which rebar3); \
		if [ -x "$$REBAR3_PATH" ]; then \
			ln -s $$REBAR3_PATH $(COUCHDB_DIR)/bin/rebar3; \
		else \
			echo "rebar3 not found or not executable. Please install rebar3."; \
		fi; \
	fi
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
	@echo ">>>> FAILED: The test failed. Below are the process logs:"
	@cat $(shell cli logs $(ID))
	@exit 1

.PHONY: couchdb-tests
# target: couchdb-tests - Run test suites from upstream CouchDB that use Clouseau
couchdb-tests: $(JAR_ARTIFACTS) couchdb epmd FORCE
	@cli start $@ "java $(_JAVA_COOKIE) -jar $<"
	@cli await $(node_name) "$(ERLANG_COOKIE)"
	@$(TIMEOUT) $(TIMEOUT_MANGO_TEST) $(MAKE) mango-test || $(MAKE) test-failed ID=$@
	@$(TIMEOUT) $(TIMEOUT_ELIXIR_SEARCH) $(MAKE) elixir-search || $(MAKE) test-failed ID=$@
	@cli stop $@

define collect_and_compare
	echo "Collecting $(1) metrics"
	$(2)
	echo "Comparing $(1) metrics with expectations:"
	DIFF=$$(diff -u $(1)/metrics.out $(1)/metrics.expected); \
	if [[ -z $$DIFF ]]; then \
		echo "Everything is in order"; \
	else \
		echo '>>>> FAILED: Metrics is different from "$(1)/metrics.expected"!'; \
		echo "$$DIFF"; \
		exit 1; \
	fi
endef

collectd/clouseau.class: collectd/clouseau.java
	javac -source 8 -target 8 "$<"

.PHONY: metrics-tests
# target: metrics-tests - Run JMX metrics collection tests
metrics-tests: $(JAR_ARTIFACTS) collectd/clouseau.class download-jmx-exporter epmd
	@cli stop $@ > /dev/null 2>&1 || true
	@chmod 600 jmxremote.password
	@cli start $@ \
		java \
			-Dcom.sun.management.jmxremote.port=9090 \
			-Dcom.sun.management.jmxremote.ssl=false \
			-Dcom.sun.management.jmxremote.password.file=jmxremote.password \
			-javaagent:$(JMX_EXPORTER)=$(JMX_EXPORTER_PORT):$(JMX_EXPORTER_CFG) \
			-jar $(JAR_ARTIFACTS) metrics.app.conf > /dev/null
	@sleep 5
	@cli await $(node_name) "$(ERLANG_COOKIE)"
	@echo "Warming up Clouseau to expose all the metrics"
	@$(TIMEOUT) $(TIMEOUT_MANGO_TEST) $(MAKE) mango-test || $(MAKE) test-failed ID=$@
	@$(call collect_and_compare,\
			collectd,\
			java -cp collectd clouseau "service:jmx:rmi:///jndi/rmi://localhost:9090/jmxrmi" monitorRole password | \
			sort > collectd/metrics.out)
	@$(call collect_and_compare,\
			prometheus,\
			curl -s "http://localhost:$(JMX_EXPORTER_PORT)/metrics" | \
				sed -n 's/^\(_com_cloudant_clouseau.*\)[[:space:]].*/\1/p' | \
				sort > prometheus/metrics.out)

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
compatibility-tests: $(JAR_ARTIFACTS) couchdb epmd FORCE
	@cli start $@ "java $(_JAVA_COOKIE) -jar $<"
	@cli await $(node_name) "$(ERLANG_COOKIE)"
	@$(MAKE) sup-test || $(MAKE) test-failed ID=$@
	@cli stop $@

############### Dev tools ###############
.PHONY: eshell
# target: eshell - Start erlang shell: `<ERLANG_COOKIE=cookie> make eshell`
eshell:
	@[ $(_REBAR_COOKIE) ] \
	&& (cd zeunit && $(REBAR) shell --name eshell@127.0.0.1 $(_REBAR_COOKIE)) \
	|| (cd zeunit && $(REBAR) shell --name eshell@127.0.0.1)

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
tdump: $(ARTIFACTS_DIR)/$(JAR_TEST)
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

.PHONY: tree
# target: tree - Print project source tree
tree:
	@tree -I 'build' --matchdirs

.PHONY: visualVM
# target: visualVM - Attach to running clouseau instance with VisualVM tool
visualVM: visualVM := $(shell mdfind -name 'VisualVM' -onlyin /Applications 2>/dev/null)
visualVM: CLOUSEAU_PID := $(shell $(clouseauPid))
visualVM:
	@[ "${CLOUSEAU_PID}" ] || ( echo '>>>>> clouseau is not running' ; exit 1 )
	@[ "$(visualVM)" ] || ( echo '>>>>> "VisualVM" is not installed' ; exit 1 )
	@${visualVM}/Contents/MacOS/visualvm --jdkhome $(JAVA_HOME) --openpid $(CLOUSEAU_PID)


############### Jenkins CI ###############
# Required by CI's releng-pipeline-library
.PHONY: version
# target: version - Print current version
version:
	@echo $(PROJECT_VSN)

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

ci-concurrent-zeunit: concurrent-zeunit-tests

ci-mango: $(JAR_ARTIFACTS) couchdb epmd FORCE
	@cli stop $@ || true
	@cli start $@ "java $(_JAVA_COOKIE) -jar $<"
	@sleep 5
	@cli await $(node_name) "$(ERLANG_COOKIE)"
	@$(TIMEOUT) $(TIMEOUT_MANGO_TEST) $(MAKE) mango-test || $(MAKE) test-failed ID=$@
	@cli stop $@

ci-elixir: $(JAR_ARTIFACTS) couchdb epmd FORCE
	@cli stop $@ || true
	@cli start $@ "java $(_JAVA_COOKIE) -jar $<"
	@cli await $(node_name) "$(ERLANG_COOKIE)"
	@$(TIMEOUT) $(TIMEOUT_ELIXIR_SEARCH) $(MAKE) elixir-search || $(MAKE) test-failed ID=$@
	@cli stop $@

ci-metrics: metrics-tests
ci-restart: restart-test
ci-syslog: syslog-tests
ci-verify: check-deps check-spotbugs

.PHONY: artifacts
# target: artifacts - Generate release artifacts
artifacts: $(ARTIFACTS_DIR) $(RELEASE_ARTIFACTS) $(ARTIFACTS_DIR)/checksums.txt $(ARTIFACTS_DIR)/clouseau_ctrl

.PHONY: release
# target: release - Push release to github
release: MAYBE_PRERELEASE := $(shell [[ "$(PROJECT_VSN)" =~ ^.*-[rR][cC][0-9]*$$ ]] && echo "--prerelease")
release: $(RELEASE_ARTIFACTS) $(ARTIFACTS_DIR)/checksums.txt
	GH_DEBUG=1 GH_HOST=github.com gh release list --repo github.com/cloudant-labs/clouseau
	GH_DEBUG=1 GH_HOST=github.com gh release create "$(PROJECT_VSN)" \
		$(MAYBE_PRERELEASE) --target "$$(git rev-parse HEAD)" \
		--repo github.com/cloudant-labs/clouseau \
		--title "Release $(PROJECT_VSN)" \
		--generate-notes $(RELEASE_ARTIFACTS) $(ARTIFACTS_DIR)/checksums.txt

$(ARTIFACTS_DIR)/clouseau-$(PROJECT_VSN)-dist.zip: $(JAR_ARTIFACTS) $(ARTIFACTS_DIR)/clouseau_ctrl
	@mkdir -p $(ARTIFACTS_DIR)/clouseau-$(PROJECT_VSN)/bin
	@cp $(JAR_ARTIFACTS) $(ARTIFACTS_DIR)/clouseau-$(PROJECT_VSN)
	@cp $(ARTIFACTS_DIR)/clouseau_ctrl $(ARTIFACTS_DIR)/clouseau-$(PROJECT_VSN)/bin
	@zip --junk-paths -r $@ $(ARTIFACTS_DIR)/clouseau-$(PROJECT_VSN)

$(ARTIFACTS_DIR)/%.jar.chksum: $(ARTIFACTS_DIR)/%.jar
	@cd $(ARTIFACTS_DIR) && sha256sum $(<F) > $(@F)

$(ARTIFACTS_DIR)/%.zip.chksum: $(ARTIFACTS_DIR)/%.zip
	@cd $(ARTIFACTS_DIR) && sha256sum $(<F) > $(@F)

$(ARTIFACTS_DIR)/checksums.txt: $(addprefix $(ARTIFACTS_DIR)/, $(CHECKSUM_FILES))
	@cat $? > $@
	@cd $(ARTIFACTS_DIR)/ && sha256sum -c checksums.txt

$(ARTIFACTS_DIR)/clouseau_ctrl: bin/clouseau_ctrl
	@cp $? $@

.PHONY: ci-release
ci-release:
	@make release

.PHONY: changes
# target: changes - List PRs since last release (to paste in the github.com comment)
changes:
	@git log --oneline $$(git describe --tags --abbrev=0)..HEAD \
		| grep Merge \
		| cut -d' ' -f1 \
		| xargs git show --format="%s" \
		| cut -d' ' -f4 \
		| xargs -I {} echo - {}
