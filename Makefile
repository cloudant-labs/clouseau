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
	@./gradlew check -i --offline

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
