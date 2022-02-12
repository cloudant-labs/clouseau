# CLI helpers for ziose project

cli <command> [args]

Use `cli help <command>` to get more help.

## commands

* `help`      - display this help message
* `commands`  - list all commands
* `verify`    - verify developer setup
* `deps`      - group of dependency management commands
* `bootstrap` - a step-by-step guide to help set up environment
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

### `deps`: A set of dependency management commands

* `cli deps tree` - display dependency tree of Java/Scala packages

---

### `bootstrap`: A step-by-step guide to help set up environment

* `cli bootstrap` - provide shell dependent guidance to do setup

---

### `fmt`: Reformat scala code

* `cli fmt` - Format all scala code which specified in the `.scalafmt.conf` file.

---

### `gh`: Low level access to GitHub related commands

* `cli gh login`         - Login to GitHub
* `cli gh browse`        - Open the GitHub repository in the web browser
* `cli gh pr`            - Work with GitHub PRs

---

### `issue`: Issue management

* `cli issue create`  - Create new issue
* `cli issue list`    - List all issues of the project
* `cli issue view`    - View given issue
* `cli issue comment` - Comment on issue

---
