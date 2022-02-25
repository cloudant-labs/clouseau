# ziose

The `ziose` project is an attempt to replace foundation of [`clouseau`](https://github.com/cloudant-labs/clouseau/) with
a [`ZIO`](https://github.com/zio/zio) as an asynchronous scheduler.

## Disclaimer

This project is highly experimental and therefore, NOT SUPPORTED. Moreover, we can and will change the API as necessary.
The integrity of commit history is not guaranteed either. We might decide to clean up the history in the future.

Thus, use it at your own risk.

## Dependency management

This project uses experimental approach to use a combination of [`asdf`](https://github.com/asdf-vm/asdf) tool
management and [`direnv`](https://github.com/direnv/direnv/). The `direnv` tool is brought
by [`asdf-direnv`](https://github.com/asdf-community/asdf-direnv) plugin.

All tools managed by `asdf` are configured in `.tool-versions` which looks somewhat like the following:

```
java openjdk-17
gradle 7.2
scala 2.13.7
erlang 23.3.2
```

The setup also tracks the host dependencies which are required by the project. These dependencies are specified
in `.deps` file which looks like the following:

```
pkgutil:com.apple.pkg.CLTools_Executables::Open the App Store on the Mac, and search for Xcode.
brew:coreutils::brew install coreutils
asdf::brew install asdf
brew::/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

The format of the lines in the file is `{tool}::{hint_how_to_install}`. The `tool` field can be in the one of the
following forms:

* `pkgutil:{package}`: for MacOS packages, where `package` is the package id.
* `brew:{package}`: for [`brew`](https://brew.sh/) packages.
* `{binary}`: for a given binary tool present in the path (we use `type "${binary}"` to check it).

## Setting up the development environment

If you don't have `asdf` + `asdf-direnv` combination on your system already, there are extra steps that need to be done.
The steps are documented in full details [here](./scripts/bootstrap.md). Essentially the steps are:

1. Install [`asdf`](https://github.com/asdf-vm/asdf) with `brew install asdf`
2. Verify your OS has all tools we need using `scripts/cli verify`
3. Use step-by-step guide script to finish installation (you might need to call it multiple
   times) `scripts/cli bootstrap`
4. Restart your shell and `cd` into project directory
5. Enable configuration by calling `direnv allow`

## The `cli` tool

In order to simplify project maintenance we provide a `cli` command. This command becomes available in your terminal
when you `cd` into project directory.

Currently, `cli` provides following commands:

* `help`      - display help message
* `commands`  - list all commands
* `verify`    - verify developer setup
* `bootstrap` - a step-by-step guide to help set up environment

You can find detailed documentation here [scripts/cli.md](./scripts/cli.md).

The plan is to implement things like

* `new exp {name}` - to provision experiment template in `experiments/` folder.
* `git {pr}` - to check out GitHub PR locally ???
* `git tree` - to display commit history as a tree
* `deps update` - to update all Java/Scala dependencies
* `deps fetch` - to fetch all Java/Scala to work offline
* `check format` - run configured linters
* `check all`
* `check <spec>`
* `run exp <Class>`

All the above are just examples and not a firm commitment.

# Some commands

## Running an experiment

*Note*: this would be replaced by `cli run exp Hello`)

```
gradle :experiments:run -PmainClass=Hello
```

## Running test case for experiment

*Note*: this would be replaced by `cli test exp HelloSpec`)

```
gradle :experiments:test --tests 'com.cloudant.ziose.experiments.HelloSpec'
```

# Change Logs:

1. Add blank lines at the end of the files.
2. Add the scala formatter to `experiments` project. `gradle scalafmtAll` or `make fmt` will modify the code. If we want
   to add more rules, please change the `.scalafmt.conf` file.
3. experiments/build.gradle: removed `throw new GradleException("please specify `-PmainClass <class>`")`. After removing
   this line, `make build` and `make test` work now. However, there are some errors on `make deps`.
