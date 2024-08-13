package com.cloudant.ziose.core

import com.cloudant.ziose.macros.checkEnv
import zio.{Duration, Trace, UIO, ZIO}

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

  def onInit(): ZIO[Any, Throwable, Unit] = {
    ctx.worker.register(this) *> actor.onInit(ctx)
  }

  def stream = ctx.stream
    // TODO make it configurable
    .groupedWithin(3, Duration.fromMillis(50))
    .flattenChunks
    // .tap(x => Console.printLine(s"actor stream: $x"))
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
  // .tap(x => printLine(s"actor stream after onMessage: $x"))

  def onTermination(reason: Codec.ETerm): ZIO[Any, Throwable, Unit] = for {
    _ <- ctx.worker.unregister(self)
    _ <- actor.onTermination(reason, ctx)
  } yield ()
  def onMessage(message: MessageEnvelope): ZIO[Any, Throwable, Unit] = {
    actor.onMessage(message, ctx)
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
  def start() = ctx.start()

  /*
   * Use it for tests only
   */
  def doTestCall(payload: Codec.ETerm) = {
    val message = MessageEnvelope.makeCall(
      Codec.EAtom("$gen_call"),
      self.pid,
      id,
      payload,
      None,
      id
    )
    ctx.call(message)
  }

  /*
   * Use it for tests only
   */
  def doTestCallTimeout(payload: Codec.ETerm, timeout: Duration) = {
    val message = MessageEnvelope.makeCall(
      Codec.EAtom("$gen_call"),
      self.pid,
      id,
      payload,
      Some(timeout),
      id
    )
    ctx.call(message)
  }

  @checkEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"id=${ctx.id}",
    s"name=${ctx.name}",
    s"self=${ctx.self}",
    s"actor=${actor.getClass.getSimpleName}@${Integer.toHexString(hashCode)}"
  )
}

object AddressableActor {
  def apply[A <: Actor, C <: ProcessContext](actor: A, ctx: C): AddressableActor[A, C] = {
    new AddressableActor[A, C](actor, ctx)
  }
}

trait Actor {
  def onInit[C <: ProcessContext](ctx: C): ZIO[Any, Throwable, Unit]
  def onMessage[C <: ProcessContext](msg: MessageEnvelope, ctx: C): ZIO[Any, Throwable, Unit]
  def onTermination[C <: ProcessContext](reason: Codec.ETerm, ctx: C): UIO[Unit]
}

trait ActorConstructor[A] {
  type AType <: A
}
