.PHONY: build
# target: build - Build package, run tests and create distribution
build: gradle/wrapper/gradle-wrapper.jar
	@./gradlew build --offline -x test

.PHONY: deps
# target: deps - Download all dependencies for offline development
deps: gradle/wrapper/gradle-wrapper.jar
	@echo "==> downloading dependencies..."
	@./gradlew deps --refresh-dependencies

.PHONY: test
# target: test - Run all tests
test: build
	@epmd &
	@./gradlew check -i --offline

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
