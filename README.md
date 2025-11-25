# clouseau

Expose Lucene features to CouchDB via Erlang RPC.

## Build status

[![Build 3.x](https://github.com/cloudant-labs/clouseau/actions/workflows/build.yaml/badge.svg)](https://github.com/cloudant-labs/clouseau/actions/workflows/build.yaml)

## Clouseau 3.x

Version `3.x` replaces the foundation of [`clouseau`](https://github.com/cloudant-labs/clouseau/tree/master) with
a [`ZIO`](https://github.com/zio/zio) as an asynchronous scheduler. This improves the stability and performance,
enables Clouseau to run on modern JVMs and brings all non-Lucene dependencies up-to-date.

## Upgrading from 2.x

Clouseau 3.x is intended to be a drop-in replacement for 2.x in that it remains compatible with existing Clouseau 2.x indexes. 
However, there are some notable changes to the deployment:

 * Java 21 is required.
 * `clouseau.ini` is replaced by [app.conf](./app.conf).
 * CouchDB 3.??? required.

## Dependency management

This project uses  a combination of [`asdf`](https://github.com/asdf-vm/asdf) tool
management and [`direnv`](https://github.com/direnv/direnv/). The `direnv` tool is brought by [`asdf-direnv`](https://github.com/asdf-community/asdf-direnv) plugin.

All tools managed by `asdf` are configured in `.tool-versions` which looks somewhat like the following:

```
# pre-requisite
direnv 2.33.0

# build tools
java openjdk-21.0.2
scala 2.13.16
# erlang needs java so it should be after it in the list
erlang 26.2.5.13
```

Additional dependencies you may need to install manually on MacOS:

- [Homebrew](https://brew.sh/)
- [asdf](https://asdf-vm.com/guide/getting-started.html) via `brew install asdf`
- [coreutils](https://www.gnu.org/software/coreutils/coreutils.html) via `brew install coreutils`
- [git](https://git-scm.com/) via `brew install git`
- [xcode](https://developer.apple.com/xcode/resources/)

## Setting up a development environment

If you don't have `asdf` + `asdf-direnv` combination on your system already, there are extra steps that need to be done.
The steps are documented in full details [here](./scripts/bootstrap.md). Essentially the steps are:

1. Install [`asdf`](https://github.com/asdf-vm/asdf) with e.g. `brew install asdf`
2. Verify your OS has all tools we need using `scripts/cli verify`
3. Use step-by-step guide script to finish installation (you might need to call it multiple times) `scripts/cli bootstrap`
4. Restart your shell and `cd` into project directory
5. Enable configuration by calling `direnv allow`

Please refer to the [styleguide](./styleguide.md) for details on development style.

## The `cli` tool

In order to simplify project maintenance we provide a `cli` command. This command becomes available in your terminal
when you `cd` into project directory.

Currently, `cli` provides following commands:

* `await`: Await clouseau node to finish start up
* `bootstrap`: A step-by-step guide to help set up environment
* `commands`: List all commands
* `deps`: A set of dependency management commands
* `fmt`: Reformat scala code
* `gh`: Low level access to GitHub related commands
* `help`: Show help for all commands
* `issue`: Issue management
* `logs`: Get recent logs filename for terminated clouseau node
* `processId`: Get clouseau PID
* `start`: Start clouseau node
* `stop`: Stop clouseau node
* `tdump`: Do a java tread dump
* `verify`: Verify development dependencies
* `zeunit`: Run zeunit tests

You can find detailed documentation here [scripts/cli.md](./scripts/cli.md).

## Configuration options

Unlike previous versions, Clouseau 3.x is configured via a [HOCON](https://github.com/lightbend/config/blob/master/HOCON.md) formatted `app.conf` file, in addition to the numerous [JVM command line options](https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html#overview-of-java-options) available. The top level [app.conf](./app.conf) file in this project briefly documents the various options, but those relevant to performance and scalability are discussed in more detail below.

### `max_indexes_open`

This option specifies the maximum number of indexes that can be open at a given time. Since each Lucene index opened by Clouseau has an overhead, if they are allowed to open without bounds, then the JVM will run out of memory. By default this is set to 100, but for large deployments with many active indexes, this number will increase significantly. Once this limit is reached, Clouseau will close the index that has been open the longest time.

### `close_if_idle` & `idle_check_interval_secs`

These options allow closing search indexes if there is no activity within a specified interval. As mentioned above, when number of open indexes reaches the `max_indexes_open` limit, Clouseau will close the index that was opened first, even if there is an activity on that index (which can be problematic). Hence this option was created to close the idle indexes first, to hopefully avoid reaching the limit specified in `max_indexes_open`.

If `close_if_idle` is true, then Clouseau will monitor the activity its indexes, and close any with no activity in two consecutive idle check intervals. By default `idle_check_interval_secs` is 300 seconds. which will close an index if it has no activity between 301 to 600 seconds.

### `-Xmx`

The command line option `-Xmx` sets the maximum heap size for the JVM. The amount of heap usage depends on the number of  open search indexes and also the search load (sorting etc). The amount of heap required usually correlates with the `max_indexes_open` settings, so more indexes open requires more memory.

The recommendation is to set this value to a maximum of one third of the available memory and to never allocate more than 50% of the total available memory. So if the nodes on cluster have 30GB of memory available, then limit `-Xmx` to 10GB and if that's not enough and the user workload still requires more memory then try increasing it to 15GB (50% of available). But be cautious when exceeding 1/3 of the available memory as it could result in less memory available for Erlang runtime and the OS.

### `-Xms`

This option configures the minimum JVM memory, and is recommended to set in cases of higher maximum heap size (> 8GB). If set, do so at 80% of `-Xmx`. This allows the JVM to set initial memory when Clouseau is started, to avoid dynamic heap resizing and lags.

## How to monitor metrics using `jconsole`

JConsole can be connected to a running Clouseau 3.x instance through the standard JMX interface as follows:

1. Run Clouseau first, `make clouseau1`
2. Open another terminal and type `make jconsole`
3. Select MBeans -> `com.cloudant.clouseau`

![jmx.png](assets/jmx.png)

## Using `sbt`

The Scala Built Tool `sbt` can be used directly to compile and start up the service, or to run individual unit tests, e.g.

```scala
sbt console
sbt "testOnly com.cloudant.ziose.clouseau.ClouseauTypeFactorySpec"
```

It can also be used for interactive experimentation as the following console session demonstrates:

```scala
❯ sbt
...
[info] started sbt server
sbt:ziose> console
[info] Starting scala interpreter...
Welcome to Scala 2.13.16 (OpenJDK 64-Bit Server VM, Java 21.0.2).
Type in expressions for evaluation. Or try :help.

scala> import zio._
import zio._
scala> import zio.Console._
import zio.Console._
scala> import zio.stream.ZStream
import zio.stream.ZStream
scala> val stream = ZStream(1,2,3,4).merge(ZStream(9,8,7,6))
val stream: zio.stream.ZStream[Any,Nothing,Int] = zio.stream.ZStream@aad94db
scala> val tapped = stream.tap(x => printLine(s"${x}"))
val tapped: zio.stream.ZStream[Any,java.io.IOException,Int] = zio.stream.ZStream@7f983cdd
scala> Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.run(tapped.runDrain) }
9
8
7
6
1
2
3
4
val res0: zio.Exit[java.io.IOException,Unit] = Success(())
scala> :q
```
