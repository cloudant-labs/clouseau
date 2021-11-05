.PHONY: build
# target: build - Build package, run tests and create distribution
build: .asdf
	@mvn

# target: tools - Bootstrap all build tools
PHONY: tools
tools: .asdf
	@touch .envrc

.PHONY: run
# target: run - Start local inistance of clouseau
run: .asdf
	@mvn scala:run -Dlauncher=clouseau1

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
