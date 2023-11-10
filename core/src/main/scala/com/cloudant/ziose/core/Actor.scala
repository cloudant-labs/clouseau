package com.cloudant.ziose.core

import zio._

/*
 * This is the trait which implements actors. An Actor is a low level construct
 * somewhat similar to erlang process. Normally you don't want to deal with Actor.
 * It is easier to use higher level abstraction such as Service.
 *
 * ```scala
 * trait Actor {
 *  def onMessage[C <: ProcessContext](msg: MessageEnvelope, ctx: C): ZIO[Any, Throwable, Unit]
 *  def onTermination[C <: ProcessContext](reason: Codec.ETerm, ctx: C): UIO[Unit]
 * }
 * ```
 *
 * The actors are defined as follows
 *
 * ```scala
 * class MyActor(foo: String, bar: Int)(implicit ctx: ProcessContext) extends Actor {
 *   def onMessage(msg: MessageEnvelope): UIO[Unit] =
 *     ZIO.succeed(())
 *   def onTermination(reason: Codec.ETerm): UIO[Unit] =
 *     ZIO.succeed(())
 * }
 *
 * object MyActor extends ActorConstructor[MyActor] {
 *   def make(foo: String, bar: Int) = {
 *     def maker[PContext <: ProcessContext](ctx: PContext): MyActor =
 *       new MyActor(foo, bar)(ctx)
 *
 *     ActorBuilder()
 *       .withCapacity(16)
 *       .withMaker(maker)
 *       .build(this)
 *   }
 * }
 *
 * As you might notice the companion object extends ActorConstructor which is defined as follows
 *
 * ```scala
 * trait ActorConstructor[A] {
 *   type AType <: A
 * }
 * ```
 */

class AddressableActor[A <: Actor, C <: ProcessContext](actor: A, context: C)
    extends EnqueueWithId[Address, MessageEnvelope] {
  type Actor   = A
  type Context = C
  val id   = ctx.id // FIXME
  val self = ctx.self
  def ctx  = context
  def stream = ctx.stream
    // TODO make it configurable
    .groupedWithin(3, Duration.fromMillis(50))
    .flattenChunks
    // .tap(x => printLine(s"actor stream before onMessage: $x"))
    .mapZIO(msg => actor.onMessage(msg, ctx))
    .refineOrDie {
      // TODO use Cause.annotate to add extra metainfo about location of a failure
      case e: ArithmeticException => {
        println(s"refining ArithmeticException ${e}")
        e
      }
      case e: Throwable => {
        println(s"refining Throwable ${e}")
        e
      }
    }
    .ensuringWith {
      case Exit.Success(_) => ZIO.succeed(onTermination(Codec.fromScala(Symbol("normal"))))
      case Exit.Failure(cause) if cause.isFailure => {
        ZIO.succeed(onTermination(Codec.fromScala((Symbol("failure"), cause.failures.toString()))))
      }
      case Exit.Failure(cause) if cause.isDie => {
        ZIO.succeed(onTermination(Codec.fromScala((Symbol("die"), cause.dieOption.toString()))))
      }
      case Exit.Failure(cause) => {
        ZIO.succeed(onTermination(Codec.fromScala((Symbol("unknown"), cause.toString()))))
      }
      // TODO - decide how we expose other options to onTermination callback
    }
  // .tap(x => printLine(s"actor stream after onMessage: $x"))

  def onTermination(reason: Codec.ETerm): ZIO[Any, Throwable, Unit] = {
    println(s"AddressableActor.onTermination ${reason}")
    actor.onTermination(reason, ctx)
  }
  def capacity: Int = ctx.capacity
  override def awaitShutdown(implicit trace: Trace): UIO[Unit] = {
    ctx.awaitShutdown
  }
  def isShutdown(implicit trace: Trace): UIO[Boolean] = {
    ctx.isShutdown
  }
  def shutdown(implicit trace: Trace): UIO[Unit] = {
    ctx.shutdown
  }
  def offer(msg: MessageEnvelope)(implicit trace: zio.Trace): UIO[Boolean] = {
    ctx.offer(msg)
  }
  def offerAll[A1 <: MessageEnvelope](as: Iterable[A1])(implicit trace: zio.Trace): UIO[zio.Chunk[A1]] = {
    ctx.offerAll(as)
  }
  def size(implicit trace: zio.Trace): UIO[Int] = {
    ctx.size
  }
  def start(scope: Scope) = ctx.start(scope)
}

object AddressableActor {
  def apply[A <: Actor, C <: ProcessContext](actor: A, ctx: C): AddressableActor[A, C] = {
    new AddressableActor[A, C](actor, ctx)
  }
}

trait Actor {
  def onMessage[C <: ProcessContext](msg: MessageEnvelope, ctx: C): ZIO[Any, Throwable, Unit]
  def onTermination[C <: ProcessContext](reason: Codec.ETerm, ctx: C): UIO[Unit]
}

trait ActorConstructor[A] {
  type AType <: A
}
