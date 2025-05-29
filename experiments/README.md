# Experiments

During the development of the project few experiments were conducted.

<!-- no toc -->
- [ `Hello` ](#hello) - Hello world in ZIO with metrics and logging.
- [ `ReadConfig` ](#readconfig) - Reading and parsing config declaratively.
- [ `ReceiveExperiment` ](#receiveexperiment) - Receive messages from Erlang.
- [ `EchoExperiment` ](#echoexperiment) - Reply to messages received from Erlang.
- [ `NodeExperiment` ](#nodeexperiment) - Hide jinterface's `node` behind actor.
- [ `OtpStatusExperiment` ](#otpstatusexperiment) - Attempt to get additional statistics from jinterface's `node`.
- [ `NodeStreamExperiment` ](#nodestreamexperiment) - Using ZStream to construct receive loop.
- [ `ActorFactoryExperiment` ](#actorfactoryexperiment) - Construction of class instances with arbitrary arguments and dependency injection.
- [ `ClouseauEchoExperiment` ](#clouseauechoexperiment) - Simple illustration how to implement a Service (as in Clouseau).

# Hello

The `Hello` experiment can be found [here](src/main/scala/com/cloudant/ziose/experiments/Hello.scala).

## Goals of the experiment

1. demonstrate that building infrastructure is provisioned correctly
2. demonstrate how we can integrate metrics into stack
3. demonstrate how to use logging

## Context

The metrics and logging libraries used by current version of Clouseau are outdated and cannot be run on modern scala.
So we need to find an approach how to replace them.

## Constrains

1. We need to avoid modifications of the call sites because we don't want to touch the Clouseau source code.
2. The metrics should report values in the same format as metrics in Clouseau.

## Solution

1. Use the accompanying test suite to test how logging works
2. Use `zio.metrics.dropwizard` package

# ReadConfig

The `ReadConfig` experiment can be found [here](src/main/scala/com/cloudant/ziose/experiments/ReadConfig.scala).

## Goals of the experiment

1. Demonstrate how we can parse configuration

## Context

We need to configure properties such as node name and cookie.

## Constrains

1. We need to be able to use different sources of configuration to read Clouseau config and facilitate testing.
2. The user of the config shouldn't need to know the location of the configuration object in the tree.
So we can have different sections for different nodes.

## Solution

The solution is rather simple. The `ZIO.config` supports `nested` combinator which allows us to avoid "poisoning" of the consumer with the knowledge about the location of a section.

The `nested` usage is tested in the accompanying spec file.


# ReceiveExperiment

The `ReceiveExperiment` experiment can be found [here](src/main/scala/com/cloudant/ziose/experiments/ReceiveExperiment.scala).

## Goals of the experiment

1. Implement simplest ZIO effect to be able to receive messages from remote Erlang node

## Context

Before we would go to elaborate solution we should try the simplest possible structure to gain knowledge about:

1. ZIO effects
2. jinterface `mbox` API
3. Running multiple effects in parallel

## Constrains

1. Make it simple and easy to understand
2. Rely on `jinterface` library for communication with Erlang

## Solution

Chain together two effects producer and consumer.
The producer calls `node.receiveMessage()` in the loop and adds received messages to the queue.
The consumer reads the messages from the queue and output them to terminal using `.debug()`

# EchoExperiment

The `EchoExperiment` experiment can be found [here](src/main/scala/com/cloudant/ziose/experiments/EchoExperiment.scala).

## Goals of the experiment

1. Learn how to run concurrently multiple main loops (receiver and responder).
2. To test end-to-end delivery of the message through ZIO stack.
3. Use the example as a teaching aid to demonstrate how messages are flowing without unnecessary abstractions.

## Context

This is one of the very first experiments. With this experiment we would be able to prove to ourselves that ZIO is capable of receiving messages asynchronously and provides sufficient abstractions to do it ergonomically.

## Constrains

Since the goal of the experiment is to demonstrate how things can be done in pure ZIO, we need to stick to the basics and avoid unnecessary abstractions.


# NodeExperiment

The `NodeExperiment` experiment can be found [here](src/main/scala/com/cloudant/ziose/experiments/NodeExperiment.scala).

## Goals of the experiment

1. Learn how to implement Event-Driven programming pattern using ZIO
2. Learn how to define commands and responses in strongly typed language.
3. Understand how to drive the main loop of the effect.
4. Figure out how to return error to the caller.
5. Understand how finalizer works

## Context

It is unknown how reliable jinterface node class is in multithreaded setting.
To protect us from concurrency problems we want to prevent concurrent access to jinterface's `node` object.
There are two ways of doing it. First we can use mutex and second we can use event driven programming pattern.
In event driven programming pattern the main loop receives and processes incoming events in order,
taking appropriate actions based on their type and content. The `dbcore` team is very familiar with this pattern since it is the main idea of Erlang and Scalang.

## Constrains

1. Attempt to simplify commands matching using `match` statement.
2. Call `node.close()` from finalizer to allow `epmd` to remove the node from its dictionary of known nodes.

## Solution

1. Event driven programming pattern is implemented using effect defining `commandLoop` which handles commands received from the queue.
2. The commands and responses are implemented as case classes extending from dedicated NodeCommand and NodeResponse trait. Each NodeCommand specify the expected response type as a generic type argument.
3. The main loop is driven by `consumer(a).forever zipPar a.loop.forever`
4. We return errors using new `Result[E, T]` type which implements `Success` and `Failure` alternatives using `case class` which has unapply and therefore can be used in pattern matching.
5. We connect finalizer using `ZIO.acquireRelease(...)(node => ZIO.attempt(node.close()).orDie.unit)`

# OtpStatusExperiment

The `OtpStatusExperiment` experiment can be found [here](src/main/scala/com/cloudant/ziose/experiments/OtpStatusExperiment.scala).

## Goals of the experiment

1. Test how inheritance from Java class works
2. Test how we can implement a metric on connection attempts

## Results

This experiment failed. The code compiles, but I don't observe any debugging printouts when attempting to ping the node or send messages to it.

# NodeStreamExperiment

The `NodeStreamExperiment` experiment can be found [here](src/main/scala/com/cloudant/ziose/experiments/NodeStreamExperiment.scala).

## Goals of the experiment

1. Learn how to create ZStream out of a generic `receive` function
2. Establish a pattern for implementing an actor pattern (a main loop which handles events received via message queue)
3. Find an approach to return either result or an error.
4. Learn how we can bind a response type to request type.
5. Find a decent way to start multiple zio effects in parallel (main loop and stream receiver)

## Context

In the EchoExperiment we've learned how to use low level functions of jinterface to communicate with Erlang node. However we don't know the thread safety guaranties of jinterface abstractions. Therefore we should manage access to `mbox`.

There are two common ways of managing access.
1. Using locking primitives
2. Dedicated message receiver
We choose second option since it provides better safety.

Building of the results achieved in EchoExperiment we want to abstract away the calls to `mbox.receiveMessage`. In this particular experiment we are trying to create a ZIO stream (ZStream) of received messages.

## Constrains

1. To avoid additional complexity do not attempt to convert `OtpErlangObject` events.
2. Assume every command is expressed using its own dedicated type and that it has a corresponding response type.
3. Allow returning either the result or an error.
4. Try to rely on pattern matching in the main loop of an actor.

## Solutions

1. Use the following to create stream (pay attention to `.collect { ... }`)
```scala
ZStream
  .fromZIO(ZIO.attemptBlocking {
    try {
      Some(mbox.receiveMsg().getMsg()) ///<-------- our receive function
    } catch {
      case e: java.lang.InterruptedException => {
        None
      }
    }
  })
  .collect { case Some(msg) => msg }
```
2. To implement actor pattern use combination of
- case classes for Request and Response
- `promise` to return the result
- use `Queue` as a message box
3. To return either result or error introduce new Result[Error, Response] type
4. Use generics and associative types to bind response type to the request
```scala
trait NodeCommand[R <: NodeResponse] {
  type Response = R
}

case class MyFirstCommand()  extends NodeCommand[MyFirstCommandResponse]

trait NodeResponse {}

case class MyFirstCommandResponse(result: Unit) extends NodeResponse
```
5. In this experiment we start multiple concurrent effects using the following
```scala
  for {
    _ <- ((nodeFiber.fork *> connect) zipPar (for {
      a <- node.spawn(Some("mbox")).debug("actor")
      _ <- actor(a, 16).forever
    } yield ())).fork
    _ <- ZIO.never
  } yield ()
```
This is not great and we need to find better way

# ActorFactoryExperiment

The `ActorFactoryExperiment` experiment can be found [here](src/main/scala/com/cloudant/ziose/experiments/ActorFactoryExperiment.scala).

## Goal of the experiment

The primary goal of this experiment is to find an approach how to instantiate actors. The secondary goal is to figure out how to wire different classes involved in the creation of the actor object together.

## Context

The actors are spawned by the node which means that node somehow need to know the arguments required to construct the actor object. The problem is that we don't have any constraints on the number and types of arguments needed to construct the actor object.

```scala
case class MyActor private (foo: String, counter: Int = 0) extends Actor {}
```

How we can construct object like the above? How we would do it lazily so only node would finish building of the object?

## Constraints

1. We should allow different node types OTPNode/GRPCNode.
2. The number and type of arguments for actor class is arbitrary.
3. Allow lazy construction of an actor object so it can be build by node.

## Solution

We use type safe builder pattern to implement lazy construction. We add an ActorFactory concept to abstract the creation of an object, so we can use different procedures for different types of nodes. We define Node, ActorFactory and EngineWorker as ZIO services and use `ZIO.serviceWithZIO[ServiceTrait]` to get concrete implementation of a trait.

# ClouseauEchoExperiment

The `ClouseauEchoExperiment` experiment can be found [here](src/main/scala/com/cloudant/ziose/experiments/ClouseauEchoExperiment.scala).

## Goals of the experiment

The goal of the experiment is to make sure we can extend of a Service and appropriate `handleInfo`/`handleCall` callbacks are called.

1. Be a testing ground to verify wire-up of zio services.
2. Act as a coordinator for ziotest to do initial performance experiments.
3. Proof the approach involving ActorBuilder works for purposes of constructing an arbitrary object.
4. Serve as an illustration to explain how things work.

## Context

In order to satisfy the constraints of the project such as 1) avoid code changes in Clouseau and 2) preserve the Lucene version we need to provide a Service class which is implementing the same API as a corresponding class in scalang. There is a rather complex inheritance chain (although temporary) of abstractions to go from `AddressableActor`` to a class which extends the Service. The Service itself uses few hard to understand scala features, such as:
- implicit context
- implicit type conversion functions
- apply/unapply
- function override

The use of these advanced features was motivated by the need to satisfy the requirement to prevent modification of Clouseau files. However as a result the understandability of the project was severely impacted. This particular experiment is a bare bones service which can be used as an illustration during the knowledge transfer sessions.

## Constrains

1. Avoid code changes in Clouseau
2. Avoid complexity in the code of the experiment (current file)
3. As a consequence of constraint #1 we have to mirror the API of the Service as closely as possible
