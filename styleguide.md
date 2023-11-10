# Style Guide for search modernization code base

The Clouseau classes are not subject to this style guide. Since search modernization project is about replacing the `scalang` library and keeping the Clouseau classes intact.

# Text formatting

- no trailing spaces at the end of the line
- one empty line at the end of the file
- the comment describing the content of the file goes after package or module directive (`package xxx` for Scala, `module(xxx).` for Erlang).

# Scala conventions

1. Use argumentless form (`def foo: String`) of function only for pure functions. Keep in mind that in some cases it might not work (see (here)[https://github.ibm.com/cloudant/ziose/pull/62#pullrequestreview-14401685]). At the call site follow the same convention. Don't add parentheses if the method was defined without them.
2. Use empty list of arguments for non-pure functions `def foo(): Unit`
3. Use `import _root_.com.cloudant.ziose.core` to resolve importing problem (see [here](https://stackoverflow.com/questions/21701452/scala-import-not-working-object-name-is-not-a-member-of-package-sbt-preppen)). However before doing so check your files to make sure you don't have multiple  `package` directives in any of them.
4. Don't use `Any` type. The exceptions are: `clouseau`/`scalang` packages and `ZIO[Any, Error, Return]` where `Any` means we accept `Any` environment. In all other cases think twice before using `Any`.
5. Avoid using `asInstanceOf[Type]`. Use defensive programming or better type-safe patterns in the surrounding code to prevent accidental invocation of `asInstanceOf[Type]` line for the type you are not expecting. Please add a comment explaining why it is safe to use in each particular case.
6. Avoid introducing implicit parameters or type convertors. Add documentation and comments if you do.
7. Avoid wildcard imports `import package._`. Use it only if you need more than 5+ items from that package or if package injects behavior on import.
8. Prefer implementing `apply` method to be able to use `Something("foo")` syntax instead of using `new Something("foo")`.
9. Always pay attention to mutability and use `val` for immutable variables.
10. If you have private fields or want to prevent direct construction of a class instance, use private constructor pattern.
  ```scala
  class Foo private (private val bar: Bar) {
    ...
  }

  object Foo {
    def make(name: String) = {
      new Foo(Bar(name))
    }
  }
  ```
11. Consider regular class as your first choice. Use case classes only when you model immutable data or need pattern matching. The case class was designed for that use case.

# Experiments package

Experiments are self-contained one-off Scala files and therefore there are additional requirements to them.

1. They shouldn't leak classes out of an experiment file (except `main`).
  You can achieve that by including experiment id into package name.
  ```scala
  /*
   * Running from sbt
   * experiments/runMain com.cloudant.ziose.experiments.actorFactory.Main
   */

  package com.cloudant.ziose.experiments.actorFactory

  object Main extends ZIOAppDefault {
    ...
  }
  ```

2. Every experiment file should contain description of all manual steps that need to be taken to reproduce experiment. Assume that they would need to be reproduced by someone not experienced with the code base and tooling.
3. Use logging facility to print debugging information onto terminal
4. Inside of experiments package you can violate normally applicable style guide rules (modulo rules defined specifically for experiments package).

## Using log facility

```scala
object MyExperiment extends ZIOAppDefault {
  private val logger = Runtime.removeDefaultLoggers >>> console(LogFormat.colored)

  def program =
    for {
        _ <- myzio.debug("myzio")
        r <- myzio.doSomething
        _ <- ZIO.debug(s"my debug ${r}")
    } yield ()

  def run: Task[Unit] =
    ZIO
      .scoped(program)
      .provide(
        logger,
        ZLayer.Debug.tree
      )
}

```

# Test suites

- They shouldn't leak auxiliary classes out of a test file
- Every test suite should include a comment describing how to run this particular test file from `sbt`.

# Returning either result or failure

In Scala's core library, there is currently no dedicated mechanism for returning either an error or a result. Many projects have resorted to using the `Either` type, which returns either `A` or `B`. However for this particular project use the `Result` type described in the next section.

## Using Either type for encoding result

The `Either` type in Scala is a generic container that can hold either a value of type `A` or an error message of type `E`. It is commonly used in functional programming to represent the possible outcomes of an operation. The `Either` type has two cases, `Left` and `Right`, which are used to indicate whether an operation was successful (returned as a `Right`) or not (returned as a `Left`). The `Either` type is often used in conjunction with the `match` statement to handle different outcomes of an operation.

Following example demonstrates the use of `Either` for error handling:

```scala
def handleCommand(command: Command[_]): Either[Error, Response] =
  ...
  // return result
  Right(MyResponse())
  ...
  // return error
  Left(MyError())
```

However, the `Either` type can be overly generic because the labels for `Right` and `Left` are not descriptive enough. In order to ensure consistency in code, a convention such as the **"error goes first"** rule should be followed. This means that the order of arguments for the `Either` type will be `Either[E, R]`, which is consistent with the `Promise[E, R]` type.

To help the reader please define helper functions as in following example.

```scala
    def succeed(result: Response): Either[Error, Response] = Right(result)
    def fail(reason: Error): Either[Error, Response]       = Left(reason)
    def handleCommand(command: Command[_]): Either[Error, Response] =
      ...
      // return result
      succeed(MyResponse())
      ...
      // return error
      fail(MyError())
```

## Using `Result` type for signaling the result

The `com.cloudant.ziose.core` package contains the [`Result[E, T]` type](core/src/main/scala/com/cloudant/ziose/core/Result.scala) which is a more specific version of the `Either` type from Scala. It contains a value of type `T` or an error message of type `E`. Like the `Either` type, it is used to represent the possible outcomes of an operation and has two cases: `Success(T)` and `Failure(E)`. The `Result[E, T]` type is similar to the `Either` type but has better labels which improves readability at the call site.
This makes it more useful in scenarios where it's important to differentiate between a successful result and an error message.

The `Result[E, T]` can be used as follows:

```scala
def handleCommand(command: Command[_]): Result[Error, Response] = {
  case CreateMbox(Some(name)) => {
    node.createMbox(name) match {
      case null          => Failure(NameInUseError(name))
      case mbox: OtpMbox => Success(CreateMboxResponse(mbox))
    }
  }
}
...
def loop = {
  ...
  handleCommand(event.command) match {
    case Success(response) => event.succeed(response)
    case Failure(reason)  => event.fail(reason)
  }
}
```

Therefore please do not use scala's `Either` and use `Result` instead.

# Naming conventions

- `make` - standard goto name for a constructor
- `live` - standard name for `ZLayer` constructor to be used for production
  - `mock` - mocked version of `ZLayer`
- `create` - kind of like `make` but not supposed to be directly called and to be used from a `builder`
- `Something.State` - Define a `State` of a builder pattern (if you use Phantom types based builder) inside builder object/trait/class to form nice hierarchy. Examples
  - `ActorBuilder[A, ActorBuilder.State.Spawnable]`
- Use `CamelCase` convention for class/trait/object/types
- Use `camelCase` convention (method names starts with lower case letter) for method names
- Try to use [subject–object–verbLike (SOV)](https://en.wikipedia.org/wiki/Subject%E2%80%93object%E2%80%93verb_word_order) order to define complex names.Examples:
  - `ActorFactoryBuilder`
  - `NodeConstructor`
- Always include units of measurement in the names when operating with quantities
- use `withSomething` convention for builder pattern steps

# Ordering conventions

## Order of types

When you define complex types follow the order:

1. Requirements (if applicable)
2. Error (if applicable)
3. Result (if applicable)

Here are a few examples of how complex types can be defined:

- `ZIO[Node, Node.Error, Actor]`
- `Result[Error, String]`
- `Promise[Error, Integer]`

## Order of arguments

Place arguments in following order (you can make some of them implicit).

1. Subject
2. Multiple objects (arguments)
3. Context or Environment (if needed)
4. Closure (if needed)

Here are few examples:

- `def register(actor: Actor, name: String)`
- `def reduce[T, Acc](elements: List[T], acc: Acc, fn (T, Acc) => Acc): Acc`

# Encapsulation

## Favor private constructor

Try to make default constructors `private`
(by using `class <Name> private (...)`) and add `build` constructor
for cases when there is a need to dynamically derive field values.
```scala
class NodeActor private(node: OtpNode, queue: Queue[Envelope]) {}
object NodeActor {
  def make(name: String) {
    ...
    queue = ...
    node = OtpNode(name, queue)
    ...
    node
  }
}
```

# Construction

## Default values

1. If you have `Option[_]` type in your argument, please specify default value `name: Option[String] = None`

## Multiple steps constructors

Sometimes it is beneficial to build elements step by step. In this case quite often the builder pattern is used. Here is how builder pattern is looking at the call site:

```scala
ActorBuilder()
  .withCapacity(capacity)
  .withName(name)
  .withMaker(MyActor(foo)(_))
  .build(this)
```

The main benefits of the builder pattern:

1. You can build object step by step (few steps in one function few other steps somewhere else).
2. You can easily add new arguments to your object without the need to update all call sites.
3. You can mention arguments in any order. Which is beneficial for objects whose constructor require more than three arguments. Because humans are bad at remembering things.

When you implement builder pattern and your final object has to be completely specified use Phantom type based builder (see [ActorBuilder](core/src/main/scala/com/cloudant/ziose/core/ActorBuilder.scala))

# Type safety

1. Do not use `Any` type ever. The only exception is value for ZIO environment. Another words `ZIO[Any, Nothing, Actor]` is ok.
2. Avoid using `.asInstanceOf[_]` try to use generics instead.
3. Favor Phantom type based builder pattern when implementing a builder or factory.
4. Do not use magic values with special meaning. For example when you need to pass a capacity for a `Queue` it is tempting to use `0` to signal that you need an unbounded version of the `Queue`. Nine times out of ten the `Option[_]` type is better. In the case of `Queue` it would look like `make(capacity: Option[Integer] = None)`.

# Performance related considerations

1. When writing a `match` statement put the handling of the most likely outcome at the beginning of the match statement. The order of statements should be the same as the order of probability of the outcomes for each case from the most probable to the least probable.