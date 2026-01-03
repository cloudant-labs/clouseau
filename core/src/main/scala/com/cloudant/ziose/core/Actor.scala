package com.cloudant.ziose.core

import com.cloudant.ziose.macros.CheckEnv
import zio.{Cause, Duration, Trace, UIO, ZIO}
import java.util.concurrent.atomic.AtomicBoolean
import zio.Promise
import zio.logging.LogAnnotation
import zio.Exit
import zio.StackTrace

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
    extends ForwardWithId[Address, MessageEnvelope]
    with WithProcessInfo[Address] {
  type Actor   = A
  type Context = C
  val NUMBER_OF_FIBERS                    = 3
  val id                                  = ctx.id // FIXME
  val name                                = ctx.name
  val self                                = ctx.self
  private val isFinalized: AtomicBoolean  = new AtomicBoolean(false)
  def ctx                                 = context
  def status()                            = context.status()
  def getTags                             = ctx.getTags
  def setTag(tag: String): Unit           = ctx.setTag(tag)
  def getMeters(): List[Metrics.Meter[_]] = ctx.getMeters()
  def findMeter(name: String)             = ctx.findMeter(name)
  def isRunningZIO = status().map(_.values.collect {
    case status if !status.isDone => true
  }.size == NUMBER_OF_FIBERS)
  def isStoppedZIO = status().map(_.values.collect {
    case status if status.isDone => true
  }.size == NUMBER_OF_FIBERS)

  def onInit(): UIO[ActorResult] = {
    def onFunc = actor.onInit(ctx)
    for {
      _   <- ctx.worker.register(this)
      res <- callHandler(onFunc, ActorCallback.OnInit)
    } yield res
  }

  val formatAddress = name match {
    case Some(name) => s"${name}@${id.asInstanceOf[PID].pid}"
    case None       => s"${id.asInstanceOf[PID].pid}"
  }

  def onTermination(result: ActorResult): UIO[ActorResult] = {
    if (!isFinalized.getAndSet(true)) {
      val reason = resultToReason(result)
      def onFunc = actor.onTermination(reason, ctx).as(ActorResult.Stop())
      for {
        _   <- ctx.worker.unregister(self)
        res <- callHandler(onFunc, ActorCallback.OnTermination)
      } yield res
    } else {
      ZIO.succeed(ActorResult.Stop())
    }
  }

  def onMessage(message: MessageEnvelope)(implicit trace: Trace): UIO[ActorResult] = {
    def onFunc = actor.onMessage(message, ctx)
    callHandler(onFunc, ActorCallback.OnMessage)
  }

  def awaitShutdown(implicit trace: Trace): UIO[Unit] = {
    ctx.awaitShutdown
  }

  /*
   * shutdown is called by exchange when it terminates
   */
  def shutdown(implicit trace: Trace): UIO[Unit] = {
    ctx.onExit(Exit.succeed(ActorResult.Shutdown()))
  }

  def forward(msg: MessageEnvelope)(implicit trace: zio.Trace): UIO[Boolean] = {
    ctx.forward(msg)
  }

  def messageQueueLength()(implicit trace: zio.Trace): UIO[Int] = {
    ctx.messageQueueLength()
  }

  def start(continue: Promise[Nothing, Unit]) = {
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
    val handleMessage = handleActorMessage(continue)
    def loop(): ZIO[Any, Nothing, Boolean] = {
      ZIO.iterate(true)(res => res) { _ =>
        (for {
          event <- ctx.nextEvent
          shouldContinue <- event match {
            case Some(event) if !isFinalized.get => handleMessage(event)
            case Some(event)                     => ZIO.succeed(false)
            case None                            => ZIO.succeed(true)
          }
        } yield shouldContinue).onTermination(cause => {
          val result = ActorResult.recoverFromCause(cause).getOrElse(ActorResult.Shutdown())
          onTermination(result) *> ctx.onExit(Exit.fail(result))
        })
      }
    }
    for {
      fiber <- ctx
        .forkScopedWithFinalizerExit(
          loop(),
          exit => {
            onTermination(
              ActorResult.recoverFromExit(exit).getOrElse(ActorResult.Shutdown())
            )
          }
        ) @@ AddressableActor.addressLogAnnotation(ctx.id) @@ AddressableActor
        .actorTypeLogAnnotation(
          actor.getClass.getSimpleName
        )
      _ <- forward(MessageEnvelope.Init(id))
      _ <- ctx.start(fiber)
    } yield ()
  }

  def handleActorMessage(
    continue: Promise[Nothing, Unit]
  ): MessageEnvelope => ZIO[Any, Nothing, Boolean] = {
    case MessageEnvelope.Exit(_from, _to, Codec.EAtom("shutdown"), _workerId) =>
      handleActorResult(ActorResult.Shutdown())
    case MessageEnvelope.Exit(_from, _to, reason, _workerId) =>
      handleActorResult(ActorResult.StopWithReasonTerm(reason))
    case MessageEnvelope.Link(Some(from), _myself, _base) =>
      ctx.link(from).orElse(ZIO.unit).as(true)
    case MessageEnvelope.Unlink(Some(from), _myself, _id, _base) =>
      ctx.unlink(from).as(true)
    case _: MessageEnvelope.Init =>
      for {
        _              <- continue.succeed(())
        shouldContinue <- onInit().flatMap(handleActorResult)
      } yield shouldContinue
    case message =>
      onMessage(message).flatMap(handleActorResult)
  }

  def handleActorResult(result: ActorResult): UIO[Boolean] = {
    result match {
      case ActorResult.Continue() =>
        ZIO.succeed(result.shouldContinue)
      case ActorResult.StopWithCause(_callback, _cause) =>
        onTermination(result) *> ctx.onExit(Exit.fail(result)).as(result.shouldContinue)
      case _ =>
        onTermination(result) *> ctx.onStop(result).as(result.shouldContinue)
    }
  }

  def resultToReason(result: ActorResult) = {
    result.asReasonOption match {
      case Some(reason) => reason
      case None         => Codec.fromScala(result.toString())
    }
  }

  private def callHandler(
    onFunc: => ZIO[Any, _ <: Throwable, ActorResult],
    callback: ActorCallback
  ): UIO[ActorResult] = {
    for {
      res <- (try {
        onFunc.sandbox
          .foldZIO(
            failure => ZIO.succeed(ActorResult.onCallbackError(failure, callback)),
            success => ZIO.succeed(success)
          )
      } catch {
        case failure: Throwable =>
          // recover the trace stack
          val cause = ActorResult.failureToCause(failure)
          ZIO.succeed(ActorResult.onCallbackError(cause, callback))
      }) @@ AddressableActor.actorCallbackLogAnnotation(callback)
      _ <- res match {
        case result: ActorResult.StopWithCause =>
          ZIO.logError(s"${callback.name} ${ctx.name}: ${result}") @@
            AddressableActor.actorCallbackLogAnnotation(callback) @@
            AddressableActor.actorTypeLogAnnotation(actor.getClass.getSimpleName)
        case _ => ZIO.unit
      }
    } yield res
  }

  /*
   * Use it for tests only
   */
  def doTestCall(payload: Codec.ETerm) = for {
    node <- ZIO.service[Node]
    ref  <- node.makeRef()
    message = MessageEnvelope
      .makeCall(
        id,
        Codec.ETuple(self.pid, ref),
        payload,
        None
      )
      .get
    result <- ctx.call(message)
  } yield result

  /*
   * Use it for tests only
   */
  def sendTestCall(payload: Codec.ETerm) = for {
    node <- ZIO.service[Node]
    ref  <- node.makeRef()
    message = MessageEnvelope
      .makeCall(
        id,
        Codec.ETuple(self.pid, ref),
        payload,
        None
      )
      .get
    result <- ctx.forward(message)
  } yield result

  /*
   * Use it for tests only
   */
  def send(payload: Codec.ETerm) = {
    val message = MessageEnvelope.makeSend(id, payload, id)
    ctx.forward(message)
  }

  /*
   * Use it for tests only
   */
  def doTestCallTimeout(payload: Codec.ETerm, timeout: Duration) = for {
    node <- ZIO.service[Node]
    ref  <- node.makeRef()
    message = MessageEnvelope
      .makeCall(
        id,
        Codec.ETuple(self.pid, ref),
        payload,
        Some(timeout)
      )
      .get
    result <- ctx.call(message)
  } yield result

  /*
   * Use it for tests only
   */
  def doTestCast(payload: Codec.ETerm) = for {
    node <- ZIO.service[Node]
    message = MessageEnvelope
      .makeCast(
        Codec.EAtom("$gen_cast"),
        self.pid,
        id,
        payload,
        self
      )
    result <- ctx.cast(message)
  } yield result

  /*
   * Use it for tests only
   */
  def exit(reason: Codec.ETerm): UIO[Unit] = ctx.exit(reason)

  /*
   * Use it for tests only
   */
  def lookUpName(name: String) = ctx.lookUpName(name)

  @CheckEnv(System.getProperty("env"))
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
  def onMessage[C <: ProcessContext](msg: MessageEnvelope, ctx: C)(implicit
    trace: Trace
  ): ZIO[Any, Throwable, _ <: ActorResult]
  def onTermination[C <: ProcessContext](reason: Codec.ETerm, ctx: C): ZIO[Any, Throwable, Unit]
}

trait ActorConstructor[A] {
  type AType <: A
}

trait ActorCallback {
  val name: String
}

object ActorCallback {
  case object OnInit extends ActorCallback {
    val name = "OnInit"
  }
  case object OnMessage extends ActorCallback {
    val name = "OnMessage"
  }
  case object OnTermination extends ActorCallback {
    val name = "OnTermination"
  }
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
  private trait Exit extends Throwable {
    def toResult: ActorResult
  }

  private case class ExitWithReasonTerm(reason: Codec.ETerm) extends Exit {
    def toResult = reason match {
      case Codec.EAtom("normal") => Stop()
      case _                     => StopWithReasonTerm(reason)
    }
  }
  private case class ExitWithReasonString(reason: String) extends Exit {
    def toResult = reason match {
      case "normal" => Stop()
      case _        => StopWithReasonString(reason)
    }
  }

  def exit(reason: String): Unit      = throw ExitWithReasonString(reason)
  def exit(reason: Codec.ETerm): Unit = throw ExitWithReasonTerm(reason)

  case class Continue() extends ActorResult {
    val shouldContinue: Boolean             = true
    val asReasonOption: Option[Codec.ETerm] = None
  }
  case class Shutdown() extends ActorResult {
    val shouldContinue: Boolean             = false
    val asReasonOption: Option[Codec.ETerm] = Some(Codec.EAtom("shutdown"))
  }
  case class Stop() extends ActorResult {
    val shouldContinue: Boolean             = false
    val asReasonOption: Option[Codec.ETerm] = Some(Codec.EAtom("normal"))
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
    val asReasonOption: Option[Codec.ETerm] = Some(Codec.EBinary(cause.toString()))
  }

  def failureToCause(failure: Throwable) = {
    val stackTrace = failure.getStackTrace()
    // Attach the stacktrace to point to actual issue in user's code
    Cause.fail(failure).mapTrace(trace => StackTrace.fromJava(trace.fiberId, stackTrace))
  }

  def onError(failure: Throwable): Option[ActorResult] = failure match {
    case err: ActorResult.Exit =>
      Some(err.toResult)
    case _ =>
      None
  }

  def onCallbackError(failure: Cause[Throwable], callback: ActorCallback) = {
    recoverFromCause(failure) match {
      case Some(exit: ActorResult.Exit) => exit.toResult
      case Some(result)                 => result
      case None                         => ActorResult.StopWithCause(callback, failure)
    }
  }
  def onCallbackError(failure: Throwable, callback: ActorCallback): ActorResult = {
    onError(failure).getOrElse(
      ActorResult.StopWithCause(callback, failureToCause(failure))
    )
  }

  def recoverFromExit(exit: zio.Exit[_, _]): Option[ActorResult] = exit match {
    case Exit.Failure(cause)           => recoverFromCause(cause)
    case Exit.Success(cause: Cause[_]) => recoverFromCause(cause)
    case _                             => None
  }

  def recoverFromCause(cause: Cause[_]): Option[ActorResult] = {
    cause match {
      case Cause.Fail(result: ActorResult, _trace)      => Some(result)
      case Cause.Fail(result: ActorResult.Exit, _trace) => Some(result.toResult)
      case Cause.Die(result: ActorResult, _trace)       => Some(result)
      case Cause.Die(result: ActorResult.Exit, _trace)  => Some(result.toResult)
      case _: Cause.Interrupt                           => Some(ActorResult.Shutdown())
      case _                                            => None
    }
  }
}
