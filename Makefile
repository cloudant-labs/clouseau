.PHONY: build
# target: build - Build package, run tests and create distribution
build: .asdf
	@mvn

# target: tools - Bootstrap all build tools
PHONY: tools
tools: .asdf
	@touch .envrc

.PHONY: clouseau1
# target: clouseau1 - Start local inistance of clouseau1 node
clouseau1: .asdf
	@mvn scala:run -Dlauncher=$@

.PHONY: clouseau2
# target: clouseau2 - Start local inistance of clouseau2 node
clouseau2: .asdf
	@mvn scala:run -Dlauncher=$@

.PHONY: clouseau3
# target: clouseau3 - Start local inistance of clouseau3 node
clouseau3: .asdf
	@mvn scala:run -Dlauncher=$@

.PHONY: clean
# target: clean - Remove build artifacts
clean:
	@mvn clean

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
