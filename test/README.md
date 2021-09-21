# Locust Test

Test `Clouseau` using [Locust](https://github.com/locustio/locust) and [Faker](https://github.com/joke2k/faker).

## Configuration options

Locust configuration options.

Command line | Description
--- | ---
--headless | Disable the web interface, and start the test
--only-summary | Only print the summary stats
--host | Host to load test
-u | Peak number of concurrent Locust users
-r | Rate to spawn users at (users per second)
-t | Stop after the specified amount of time
--docs-number | The number of generated documents (default: 10)

```
locust -f locustfile.py --headless --only-summary --docs-number 10 -u 1 -r 1 -t 10
```

## Basic Usage

Run `CouchDB` and `Clouseau` in different terminals, and then run the locust test:

```
# Open 4 different terminals and run the command:
./dev/run --admin=adm:pass
mvn scala:run -Dlauncher=clouseau1
mvn scala:run -Dlauncher=clouseau2
mvn scala:run -Dlauncher=clouseau3
```

### Install dependencies:

```
./run install
```

### Run random_tree_generator tests:

```
./run locust
```

### Cleanup

```
./run clean
```
