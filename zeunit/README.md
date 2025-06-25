zeunit
=====

Ziose Eunit Integration Test

Commands
--------

Run `zeunit` integration tests

```bash
cd ziose
make zeunit
```

Run `zeunit` specified tests

```bash
cd ziose
cli start clouseau1 java -jar clouseau.jar

# Stop clouseau node manually
cd ziose/zeunit
rebar eunit suites=<suites> tests=<tests>

cli stop clouseau1

# Or use `cli zeunit` which will stop Clouseau node after the tests run
cli zeunit clouseau1 suites=<suites> tests=<tests>
```
