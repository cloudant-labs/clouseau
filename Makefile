UNAME_S := $(shell uname -s)
# target: tools - Bootstrap all build tools
ifeq ($(UNAME_S),Linux)
    OS=linux
PHONY: tools
tools: .asdf
	@touch .envrc
endif
ifeq ($(UNAME_S),Darwin)
    OS=darwin
PHONY: tools
tools: .asdf .brew
	@touch .envrc
endif

.asdf: .tool-versions
	@cat $< | cut -d' ' -f 1 | xargs -I {} sh -c '\
		asdf list {} > /dev/null 2>&1 \
		|| asdf plugin-add {}'
	@asdf install
	@touch $@

.brew: .brew-versions
	@cat $< | xargs -L1 -I {} sh -c '\
		brew list --versions $0 > /dev/null 2>&1 \
		|| ( echo ">>>>> brew: please install \"{}\"" ; exit 1 )'
	@cat $< | xargs -L1 bash -c '\
		test -d $$(brew --cellar $$0)/$$1 \
		|| ( echo ">>>>> brew: please install \"$$1\" of \"$$0\"" ; exit 1 )'
	@touch $@

# target: test - Run test suite
.PHONY: test
test: build
	@echo "==> testing..."
	@epmd &
	@./gradlew test -i --offline

.PHONY: gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.jar: .tool-versions
	@gradle wrapper --gradle-version \
		$$(cat .tool-versions | grep gradle | cut -d' ' -f2)

# target: clean - Clean Java/Scala artifacts
clean:
	@./gradlew clean

# target: clean-all - Clean up the project to start afresh
clean-all:
	@rm -rf gradlew gradlew.bat .gradle .gradletasknamecache \
		build test/app/build target deps
	@echo '==> keep in mind that some state is stored in ~/.gradle/caches/'

# target: build - Build the project
build: deps
	@echo "==> building..."
	@./gradlew build --offline -Pskip_tests=true

# target: deps - Download Java and Scala dependencies
deps: build.gradle gradle.properties settings.gradle gradle/wrapper/gradle-wrapper.jar
	@echo "==> downloading dependencies..."
	@./gradlew downloadDependencies --refresh-dependencies
	@touch $@

# target: run - Start single clouseau node as clouseau1@127.0.0.1
run: build
	@echo "==> starting..."
	@epmd &
	@./gradlew run -Pnode=clouseau1

.PHONY: help
# target: help - Print this help
help:
	@egrep "^# target: " Makefile \
		| sed -e 's/^# target: //g' \
		| sort \
		| awk '{printf("    %-20s", $$1); $$1=$$2=""; print "-" $$0}'