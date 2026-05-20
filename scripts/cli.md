# CLI helpers for ziose project

cli <command> [args]

Use `cli help <command>` to get more help.

## commands

* `help`      - display this help message
* `commands`  - list all commands
* `verify`    - verify developer setup
* `bootstrap` - a step-by-step guide to help set up environment
* `start`     - start clouseau node
* `stop`      - stop clouseau node
* `tdump`     - capture a Java thread dump
* `await`     - await the clouseau node
* `logs`      - get recent logs file for terminated clouseau node
* `processId` - get clouseau PID
* `zeunit`    - run `ZEUnit` tests
* `fmt`       - reformat scala code
* `gh`        - GitHub related commands
* `issue`     - Issues management

---

### `help`: Show help for all commands

Displays the help message.

---

### `verify`: Verify development dependencies

Execute set of check which would verify the environment and print out hints what to do to finish setup.

---

### `commands`: List all commands

---

### `bootstrap`: A step-by-step guide to help set up environment

* `cli bootstrap` - provide shell dependent guidance to do setup

---

### `start`: Start clouseau node

* `cli start [name] [command]` - start clouseau node

```bash
cli start "clouseau" "java -jar clouseau.jar"
```

---

### `stop`: Stop clouseau node

* `cli stop [name]` - stop clouseau node

```bash
cli stop "clouseau"
```

---

### `tdump`: Capture a Java thread dump

* `cli tdump [name]` - capture a Java thread dump for clouseau node

```bash
cli tdump "clouseau"
cli tdump "clouseau" tmp/thread_dump.log
```

---

### `await`: Await clouseau node to finish start up

* `cli await [name] [cookie]` - await clouseau node to finish start up

```bash
cli await "clouseau" "myCookie"
```

---

### `logs`: Get recent logs filename for terminated clouseau node

* `cli logs [name]` - get recent logs for terminated clouseau node

```bash
cli logs "clouseau"
```

---

### `processId`: Get clouseau PID

* `cli processId [name]` - get clouseau PID

```bash
cli processId "clouseau1"
```

---

### `zeunit`: Run `ZEUnit` tests

* `cli zeunit [name] [--setcookie=cookie] [--module=] [--test=]` - Run zeunit tests with specified `cookie`, `module` and `test`

```bash
cli zeunit clouseau                     # Run all ZEUnit tests
cli zeunit clouseau --setcookie=secret  # Use specified cookie
cli zeunit clouseau --module=echo_tests # Run specified tests only
```

**Note**:
1. Please start CouchDB and Clouseau first, then run `cli zeunit`. Alternatively, simply run `make zeunit`, which will automatically start CouchDB and Clouseau.

2. If you encounter `*** context setup failed ***`, please check your cookies.

---

### `fmt`: Reformat scala code

* `cli fmt` - Format all scala code which specified in the `.scalafmt.conf` file.

---

### `gh`: Low level access to GitHub related commands

* `cli gh login` - Login to GitHub

---

### `issue`: Issue management

* `cli issue create`  - Create new issue
* `cli issue list`    - List all issues of the project
* `cli issue view`    - View given issue
* `cli issue comment` - Comment on issue

---
