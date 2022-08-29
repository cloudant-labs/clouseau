PROJECT_NAME=ziose
BUILD_DIR=$(shell pwd)
GIT_COMMIT?=$(shell git rev-parse HEAD)
GIT_REPOSITORY?=$(shell git config --get remote.origin.url)
ifeq ($(PROJECT_VERSION),)
PROJECT_VERSION := $(shell cat $(BUILD_DIR)/build.gradle | sed -e '/[[:space:]]*project[.]ext[.]version[[:space:]]*=[[:space:]]*/!d' -e "s///g" -e "s/\'//g")
endif
BUILD_DATE?=$(shell date -u +"%Y-%m-%dT%TZ")
# tput in docker require TERM variable
TERM?=xterm
TEST?=experiments

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

ifeq ($(JENKINS_URL),)
  # Local invocation
  UBI_OPENJDK17_DIGEST?=$(shell docker manifest inspect registry.access.redhat.com/ubi8/openjdk-17:latest \
	| jq -r '.manifests |  to_entries[] | select(.value.platform.architecture == "arm64") | .value.digest' \
  )
endif

.PHONY: build
# target: build - Build package, run tests and create distribution
build: gradle/wrapper/gradle-wrapper.jar
	@./gradlew build -x test

.PHONY: deps
# target: deps - Download all dependencies for offline development
# this target is not working correctly yet
# It fails with 'Could not download compiler-bridge_2.13-1.3.5-sources.jar'
# when we try to build with `--offline` flag
deps: gradle/wrapper/gradle-wrapper.jar
	@echo "==> downloading dependencies..."
	@./gradlew deps --refresh-dependencies

.PHONY: test
# target: test - Run all tests
test: build
	@epmd &
	@./gradlew check -i

.PHONY: cover
# target: cover - Generate code coverage report
cover: build
	@./gradlew :${TEST}:reportScoverage
	@open ${TEST}/build/reports/scoverage/index.html

.PHONY: jar
# target: jar - Generate JAR files for production
jar: gradle/wrapper/gradle-wrapper.jar
	@./gradlew jar

.PHONY: jartest
# target: jartest - Generate a JAR file containing tests
jartest: gradle/wrapper/gradle-wrapper.jar
	@./gradlew jar -Ptype=test

.PHONY: gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.jar: .tool-versions
	@gradle wrapper --gradle-version \
		$$(cat .tool-versions | grep gradle | cut -d' ' -f2)

# target: clean - Clean Java/Scala artifacts
clean:
	@./gradlew clean
	@rm -f gradle/manifest_gradle.json

# target: clean-all - Clean up the project to start afresh
clean-all:
	@rm -rf gradlew gradlew.bat .gradle .gradletasknamecache gradle
	@gradle --stop
	@find . -name .gradle | xargs rm -rf
	@find . -name build | xargs rm -rf
	@echo '==> keep in mind that some state is stored in ~/.gradle/caches/'

.PHONY: clouseau1
# target: clouseau1 - Start local instance of clouseau1 node
clouseau1:
	@./gradlew run -Pnode=$@

.PHONY: clouseau2
# target: clouseau2 - Start local instance of clouseau2 node
clouseau2:
	@./gradlew run -Pnode=$@

.PHONY: clouseau3
# target: clouseau3 - Start local instance of clouseau3 node
clouseau3:
	@./gradlew run -Pnode=$@

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
	@tree -I '.gradle' -I 'build' --matchdirs


# CI Pipeline

build-in-docker: login-artifactory-docker
	@DOCKER_BUILDKIT=0 BUILDKIT_PROGRESS=plain docker build \
		--build-arg UBI_OPENJDK17_DIGEST=${UBI_OPENJDK17_DIGEST} \
		--build-arg ARTIFACTORY_USR=${ARTIFACTORY_USR} \
		--build-arg ARTIFACTORY_PSW=${ARTIFACTORY_PSW} \
		--build-arg TERM=${TERM} \
		--pull --no-cache --rm \
		-t ${PROJECT_NAME}:${GIT_COMMIT} \
		.
	@$(call extract,${PROJECT_NAME}:${GIT_COMMIT},/artifacts,.)
	@mkdir -p $(BUILD_DIR)/ci-artifacts/
	@cp -R artifacts/* $(BUILD_DIR)/ci-artifacts/

.PHONY: ci-copy-gradle-dependencies-metadata
ci-copy-gradle-dependencies-metadata:
	mkdir -p $(BUILD_DIR)/gradle/
	@cp ci-artifacts/manifest_gradle.json $(BUILD_DIR)/gradle/
	@cp ci-artifacts/verification-metadata.xml $(BUILD_DIR)/gradle/

# Authenticate with our Artifactory Docker registry before pulling any images
login-artifactory-docker: check-env-artifactory
	# For UBI images
	@echo "Docker login Artifactory (ubi)"
	@docker login -u "${ARTIFACTORY_USR}" -p "${ARTIFACTORY_PSW}" wcp-cloudant-registry-access-redhat-docker-remote.artifactory.swg-devops.com

	# For all other (public) images
	@echo "Docker login Artifactory (docker hub)"
	@docker login -u "${ARTIFACTORY_USR}" -p "${ARTIFACTORY_PSW}" wcp-cloudant-registry-hub-docker-remote.artifactory.swg-devops.com

check-env-artifactory:
	@if [ -z "$${ARTIFACTORY_USR}" ]; then echo "Error: ARTIFACTORY_USR is undefined"; exit 1; fi
	@if [ -z "$${ARTIFACTORY_PSW}" ]; then echo "Error: ARTIFACTORY_PSW is undefined"; exit 1; fi

# Required by CI's releng-pipeline-library
.PHONY: version
# target: version - Print current version
version:
	@echo $(PROJECT_VERSION)
