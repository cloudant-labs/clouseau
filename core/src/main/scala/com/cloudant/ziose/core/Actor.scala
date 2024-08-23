package com.cloudant.ziose.core

import com.cloudant.ziose.macros.checkEnv
import zio.{Cause, Duration, Trace, UIO, ZIO}
import java.util.concurrent.atomic.AtomicBoolean
import zio.Promise
import zio.logging.LogAnnotation

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
  val id                                 = ctx.id // FIXME
  val name                               = ctx.name
  val self                               = ctx.self
  private val isFinalized: AtomicBoolean = new AtomicBoolean(false)
  def ctx                                = context

  def onInit(): ZIO[Any, Nothing, ActorResult] = for {
    _ <- ctx.worker.register(this)
    res <- (actor
      .onInit(ctx)
      .foldZIO(
        failure => ZIO.succeed(ActorResult.onInitError(failure)),
        success => ZIO.succeed(success)
      )) @@ AddressableActor.actorCallbackLogAnnotation(ActorCallback.OnInit)
  } yield res

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

  def onTermination(result: ActorResult): ZIO[Any, Nothing, ActorResult] = for {
    _ <- ctx.worker.unregister(self)
    res <- (actor
      .onTermination(resultToReason(result), ctx)
      .foldZIO(
        failure => ZIO.succeed(ActorResult.onTerminationError(failure)),
        _success => ZIO.succeed(ActorResult.Stop())
      )) @@ AddressableActor.actorCallbackLogAnnotation(ActorCallback.OnTermination)
  } yield res

  def onMessage(message: MessageEnvelope): ZIO[Any, Nothing, ActorResult] = {
    (actor
      .onMessage(message, ctx)
      .foldZIO(
        failure => ZIO.succeed(ActorResult.onMessageError(failure)),
        success => ZIO.succeed(success)
      )) @@ AddressableActor.actorCallbackLogAnnotation(ActorCallback.OnMessage)
  }
  def capacity: Int = ctx.capacity
  override def awaitShutdown(implicit trace: Trace): UIO[Unit] = {
    ctx.awaitShutdown
  }
  def isShutdown(implicit trace: Trace): UIO[Boolean] = {
    ctx.isShutdown
  }
  def shutdown(implicit trace: Trace): UIO[Unit] = {
    if (!isFinalized.getAndSet(true)) { ctx.shutdown }
    else { ZIO.unit }
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

  def start() = for {
    /*
     * The use of `continue` makes sure we don't return to the caller of the spawn before
     * we start handling the `MessageEnvelope.Init` to prevent the caller from sending the
     * messages to not fully initialized actor.
     *
     * ```mermaid
     * sequenceDiagram
     *   Note right of nodeFiber: make "continue" promise
     *   nodeFiber-x+actorFiber: create actorFiber
     *   critical
     *     nodeFiber->>+actorFiber: actor.start()
     *     Note right of actorFiber: Actor.start does ctx.offer(MessageEnvelope.Init(id))
     *     actorFiber->>+nodeFiber: resolve "continue" promise
     *     Note right of nodeFiber: await on "continue" promise
     *   end
     * Note right of actorFiber: call Actor.onInit
     * ```
     */
    continue <- Promise.make[Nothing, Unit]
    _ <- ctx.forkScoped(
      stream
        .runForeachWhileScoped(handleActorMessage(continue))
    ) @@ AddressableActor.addressLogAnnotation(ctx.id) @@ AddressableActor.actorTypeLogAnnotation(
      actor.getClass.getSimpleName
    )
    _ <- offer(MessageEnvelope.Init(id))
    _ <- ctx.start()
    _ <- continue.await
  } yield ()

  def handleActorMessage(
    continue: Promise[Nothing, Unit]
  ): MessageEnvelope => ZIO[Any, Nothing, Boolean] = {
    case MessageEnvelope.Exit(_from, _to, reason, _workerId) =>
      val result = ActorResult.StopWithReasonTerm(reason)
      for {
        _ <- onTermination(result) *>
          ctx.onStop(result) *>
          ZIO.succeed(result.shouldContinue)
      } yield false
    case _: MessageEnvelope.Init =>
      for {
        _ <- continue.succeed(())
        shouldContinue <- onInit()
          .flatMap(result => {
            result match {
              case ActorResult.Continue() => ZIO.succeed(result.shouldContinue)
              case ActorResult.StopWithCause(callback, cause) =>
                onTermination(result) *>
                  ctx.onExit(zio.Exit.fail(result)) *>
                  ZIO.succeed(result.shouldContinue)
              case _ =>
                onTermination(result) *>
                  ctx.onStop(result) *>
                  ZIO.succeed(result.shouldContinue)
            }
          })
      } yield shouldContinue

    case message =>
      for {
        shouldContinue <- onMessage(message)
          .flatMap(result => {
            result match {
              case ActorResult.Continue() => ZIO.succeed(result.shouldContinue)
              case ActorResult.StopWithCause(callback, cause) =>
                onTermination(result) *>
                  ctx.onExit(zio.Exit.fail(result)) *>
                  ZIO.succeed(result.shouldContinue)
              case _ =>
                onTermination(result) *>
                  ctx.onStop(result) *>
                  ZIO.succeed(result.shouldContinue)
            }
          })
      } yield shouldContinue
  }

  def resultToReason(result: ActorResult) = {
    // TODO we need to handle each sub-type differently
    Codec.fromScala(result.toString())
  }

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

  /*
   * Use it for tests only
   */
  def exit(reason: Codec.ETerm): UIO[Unit] = ctx.exit(reason)

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

  val addressLogAnnotation = LogAnnotation[Address](
    name = "address",
    combine = (_, r) => r,
    render = _.toString
  )

  val actorTypeLogAnnotation = LogAnnotation[String](
    name = "actor",
    combine = (_, r) => r,
    render = _.toString
  )

  val actorCallbackLogAnnotation = LogAnnotation[ActorCallback](
    name = "callback",
    combine = (_, r) => r,
    render = _.toString
  )
}

trait Actor {
  def onInit[C <: ProcessContext](ctx: C): ZIO[Any, Throwable, _ <: ActorResult]
  def onMessage[C <: ProcessContext](msg: MessageEnvelope, ctx: C): ZIO[Any, Throwable, _ <: ActorResult]
  def onTermination[C <: ProcessContext](reason: Codec.ETerm, ctx: C): ZIO[Any, Throwable, Unit]
}

trait ActorConstructor[A] {
  type AType <: A
}

trait ActorCallback

object ActorCallback {
  case object OnInit        extends ActorCallback
  case object OnMessage     extends ActorCallback
  case object OnTermination extends ActorCallback
}

trait ActorResult {
  val shouldContinue: Boolean
  val shouldStop: Boolean = !shouldContinue
  val asReasonOption: Option[Codec.ETerm]
}

/*
 * Used by the Actor trait only
 */
object ActorResult {
  case class Continue() extends ActorResult {
    val shouldContinue: Boolean             = true
    val asReasonOption: Option[Codec.ETerm] = None
  }
  case class Stop() extends ActorResult {
    val shouldContinue: Boolean             = false
    val asReasonOption: Option[Codec.ETerm] = None
  }
  case class StopWithReasonString(reason: String) extends ActorResult {
    val shouldContinue: Boolean             = false
    val asReasonOption: Option[Codec.ETerm] = Some(Codec.fromScala(reason))
  }
  case class StopWithReasonTerm(reason: Codec.ETerm) extends ActorResult {
    val shouldContinue: Boolean             = false
    val asReasonOption: Option[Codec.ETerm] = Some(reason)
  }
  case class StopWithCause(callback: ActorCallback, cause: Cause[_]) extends ActorResult {
    val shouldContinue: Boolean             = false
    val asReasonOption: Option[Codec.ETerm] = None
  }

  def onInitError(failure: Throwable) = {
    ActorResult.StopWithCause(ActorCallback.OnInit, Cause.fail(failure)).asInstanceOf[ActorResult]
  }
  def onMessageError(failure: Throwable) = {
    ActorResult.StopWithCause(ActorCallback.OnMessage, Cause.fail(failure)).asInstanceOf[ActorResult]
  }
  def onTerminationError(failure: Throwable) = {
    ActorResult.StopWithCause(ActorCallback.OnTermination, Cause.fail(failure)).asInstanceOf[ActorResult]
  }
}
