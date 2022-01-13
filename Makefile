.PHONY: build
# target: build - Build package, run tests and create distribution
build: .asdf gradle/wrapper/gradle-wrapper.jar
	@./gradlew build --offline -Pskip_tests=trues

.PHONY: deps
# target: deps - Download all dependencies for offline development
deps: .asdf gradle/wrapper/gradle-wrapper.jar
	@echo "==> downloading dependencies..."
	@./gradlew deps --refresh-dependencies

.PHONY: test
# target: test - Run all tests
test: build
	@epmd &
	@./gradlew checkAll -i --offline

# target: tools - Bootstrap all build tools
PHONY: tools
tools: .asdf
	@touch .envrc

.PHONY: gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.jar: .tool-versions
	@gradle wrapper --gradle-version \
		$$(cat .tool-versions | grep gradle | cut -d' ' -f2)


# target: clean - Clean Java/Scala artifacts
clean:
	@./gradlew clean


# target: clean-all - Clean up the project to start afresh
clean-all:
	@rm -rf gradlew gradlew.bat .gradle .gradletasknamecache gradle
	@find . -name .gradle | xargs rm -rf
	@find . -name build | xargs rm -rf
	@echo '==> keep in mind that some state is stored in ~/.gradle/caches/'


.PHONY: clouseau1
# target: clouseau1 - Start local inistance of clouseau1 node
clouseau1: .asdf
	@./gradlew run -Pnode=$@

.PHONY: clouseau2
# target: clouseau2 - Start local inistance of clouseau2 node
clouseau2: .asdf
	@./gradlew run -Pnode=$@

.PHONY: clouseau3
# target: clouseau3 - Start local inistance of clouseau3 node
clouseau3: .asdf
	@./gradlew run -Pnode=$@

.PHONY: help
# target: help - Print this help
help:
	@egrep "^# target: " Makefile \
		| sed -e 's/^# target: //g' \
		| sort \
		| awk '{printf("    %-20s", $$1); $$1=$$2=""; print "-" $$0}'

.asdf: .tool-versions
	@cat $< | cut -d' ' -f 1 | xargs -I {} sh -c '\
		asdf list {} > /dev/null 2>&1 \
		|| asdf plugin-add {}'
	@asdf install
	@touch $@