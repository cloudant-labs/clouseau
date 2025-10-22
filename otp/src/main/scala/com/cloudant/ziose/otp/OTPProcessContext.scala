package com.cloudant.ziose.otp

import java.util.concurrent.atomic.AtomicBoolean
import collection.mutable.HashMap

import zio._

import com.ericsson.otp.erlang.OtpMbox
import com.cloudant.ziose.core.ProcessContext
import com.cloudant.ziose.core.Engine
import com.cloudant.ziose.core.ActorBuilder
import com.cloudant.ziose.core.Actor
import com.cloudant.ziose.core.Codec
import com.cloudant.ziose.core.EngineWorker
import com.cloudant.ziose.core.PID
import com.cloudant.ziose.core.MessageEnvelope
import com.cloudant.ziose.core.Address
import com.cloudant.ziose.core.Metrics

import com.cloudant.ziose.core.Node
import com.cloudant.ziose.core.ActorResult
import scala.collection.mutable.ListBuffer
import com.cloudant.ziose.core.Name

class OTPProcessContext private (
  val name: Option[String],
  val mailbox: OTPMailbox,
  val meterRegistry: Metrics.Registry[_],
  val scope: Scope.Closeable,
  val worker: EngineWorker,
  private val mbox: OtpMbox
) extends ProcessContext {
  private val inProgressCalls: HashMap[Codec.ERef, Promise[Nothing, Either[Node.Error, MessageEnvelope.Response]]] = {
    HashMap()
  }

  val id                                               = mailbox.id
  val engineId: Engine.EngineId                        = worker.engineId
  val workerId: Engine.WorkerId                        = worker.id
  val nodeName: Symbol                                 = worker.nodeName
  private var fibers: Map[Symbol, Fiber.Runtime[_, _]] = Map()
  val self                                             = PID(new Codec.EPid(mbox.self), worker.id, worker.nodeName)
  private val isFinalized: AtomicBoolean               = new AtomicBoolean(false)
  private var tags: List[String]                       = List()

  def getTags                   = tags
  def setTag(tag: String): Unit = tags = tag :: tags

  def status(): UIO[Map[Symbol, Fiber.Status]] = for {
    ids <- ZIO.foldLeft(fibers)(new ListBuffer[(Symbol, Fiber.Status)]()) { case (state, (id, fiber)) =>
      fiber.status.flatMap(status => ZIO.succeed(state.addOne(id, status)))
    }
  } yield ids.toMap

  def lookUpName(name: String): UIO[Option[Address]] = ZIO.succeedBlocking {
    mbox.whereis(name) match {
      case null => None
      case pid  => Some(Address.fromPid(Codec.EPid(pid), workerId, nodeName))
    }
  }

  private def forwardToExchange(msg: MessageEnvelope): UIO[Boolean] = {
    msg.to match {
      case Name(name, workerId, workerNodeName) =>
        for {
          maybePid <- lookUpName(name.asString)
          msgToPid <- maybePid match {
            case None => ZIO.succeed(msg)
            case Some(address) =>
              ZIO.succeed(
                msg.redirect(to => Address.fromPid(address.asInstanceOf[PID].pid, to.workerId, to.workerNodeName))
              )
          }
          isSent <- worker.forward(msgToPid)
        } yield isSent
      case _ => worker.forward(msg)
    }
  }

  override def toString: String = name match {
    case Some(n) => s"OTPProcessContext(${n}.${worker.id}.${worker.engineId}@${nodeName})"
    case None    => s"OTPProcessContext(${self.pid}.${worker.id}.${worker.engineId}@${nodeName})"
  }

  def capacity: Int = mailbox.capacity

  def shutdown(implicit trace: Trace): UIO[Unit] = {
    if (!isFinalized.getAndSet(true)) { mailbox.shutdown }
    else { ZIO.unit }
  }

  override def awaitShutdown(implicit trace: Trace): UIO[Unit] = {
    mailbox.awaitShutdown
  }

  def handleRespone(msg: MessageEnvelope.Response) = {
    if (inProgressCalls.contains(msg.ref)) {
      inProgressCalls.remove(msg.ref) match {
        case Some(replyChannel) => replyChannel.succeed(Right(msg))
        case None               => ZIO.succeed(true)
      }
    } else {
      ZIO.succeed(true)
    }
  }

  def forward(msg: MessageEnvelope)(implicit trace: zio.Trace): UIO[Boolean] = {
    msg match {
      case response: MessageEnvelope.Response => handleRespone(response)
      case _                                  => mailbox.forward(msg)
    }
  }

  def messageQueueLength()(implicit trace: Trace): UIO[Int] = {
    mailbox.size
  }

  /*
   * Use it for tests only
   */
  def exit(reason: Codec.ETerm): UIO[Unit] = {
    mailbox.exit(MessageEnvelope.Exit(None, id, reason, mailbox.id))
  }

  /*
   * Use it for tests only
   */
  def exit(msg: MessageEnvelope.Exit): UIO[Unit] = {
    if (msg.to.isRemote || msg.to == id) {
      mailbox.exit(msg)
    } else {
      forwardToExchange(msg).unit
    }
  }

  def unlink(to: Codec.EPid) = mailbox.unlink(to)
  def unlink(msg: MessageEnvelope.Unlink) = {
    if (msg.to.isRemote || msg.to == id) {
      mailbox.unlink(msg.from.get)
    } else {
      forwardToExchange(msg.forward).unit
    }
  }

  def link(to: Codec.EPid) = mailbox.link(to)
  def link(msg: MessageEnvelope.Link) = {
    if (msg.to.isRemote && msg.from.get == id.pid) {
      mailbox.link(msg.from.get)
    } else {
      forwardToExchange(msg.forward).unit
    }
  }

  def monitor(monitored: Address) = mailbox.monitor(monitored)
  def demonitor(ref: Codec.ERef)  = mailbox.demonitor(ref)

  def nextEvent: ZIO[Any, Nothing, Option[MessageEnvelope]] = {
    mailbox.nextEvent.flatMap(e => ZIO.succeed(handleGenMsg(e)))
  }

  def handleGenMsg(envelope: MessageEnvelope): Option[MessageEnvelope] = {
    envelope match {
      case MessageEnvelope.Send(
            _,
            to,
            Codec.ETuple(
              Codec.EAtom("$gen_call"),
              // Match on either
              // - {pid(), ref()}
              // - {pid(), [alias | ref()]}
              fromTag @ Codec.ETuple(_: Codec.EPid, _ref),
              payload
            ),
            workerId
          ) =>
        // We matched on fromTag structure already, so it is safe to call `.get`
        Some(MessageEnvelope.makeCall(to, fromTag, payload, None).get)
      case MessageEnvelope.Send(
            Some(from: Codec.EPid),
            to,
            Codec.ETuple(
              tag @ Codec.EAtom("$gen_cast"),
              payload
            ),
            workerId
          ) =>
        Some(MessageEnvelope.makeCast(tag, from, to, payload, self))
      case _ => Some(envelope)
    }
  }

  def forkScoped[R, E, A](effect: ZIO[R, E, A]): URIO[R, Fiber.Runtime[E, A]] = {
    effect.forkIn(scope)
  }

  def forkScopedWithFinalizerExit[R, E, A, R1 <: R](
    effect: ZIO[R, E, A],
    finalizer: Exit[Any, Any] => UIO[Any]
  )(implicit trace: Trace): URIO[R, Fiber.Runtime[E, A]] = for {
    _     <- scope.addFinalizerExit(finalizer)
    fiber <- effect.forkIn(scope)
  } yield fiber

  def call(msg: MessageEnvelope.Call): ZIO[Node, _ <: Node.Error, MessageEnvelope.Response] = for {
    node          <- ZIO.service[Node]
    resultChannel <- Promise.make[Nothing, Either[Node.Error, MessageEnvelope.Response]]
    _             <- ZIO.succeed(inProgressCalls += Tuple2(msg.ref, resultChannel))
    // Return `channel` only if the `msg` was forwarded
    channel <- forwardToExchange(msg)
      .flatMap(isSent => {
        if (isSent) { ZIO.succeed(resultChannel) }
        else { ZIO.fail(Node.Error.NoSuchActor()) }
      })
    // Unify the type of the response for cases with and without timeout
    result <- (msg.timeout match {
      case Some(duration) =>
        channel.await.timeout(duration)
      case None =>
        channel.await.map(Some(_))
    })
  } yield result
    .map(x => {
      x match {
        case Right(value) =>
          // Return `Response`
          value
        case Left(reason) =>
          // Convert `Node.Error` into `Response`
          MessageEnvelope.Response.error(msg, reason)
      }
    })
    // If the result is None, it means `channel`, got timeout.
    .getOrElse(MessageEnvelope.Response.timeout(msg))

  def cast(msg: MessageEnvelope.Cast): UIO[Unit] = {
    if (msg.to.isRemote || msg.to == id) {
      mailbox.cast(msg)
    } else {
      forwardToExchange(msg).unit
    }
  }

  def send(msg: MessageEnvelope.Response): UIO[Unit] = {
    (msg.to.isRemote, msg.to == id) match {
      case (true, _) =>
        // If message is for remote actor, send it through mailbox
        mailbox.send(mapResponse(msg))
      case (false, false) =>
        // If message is not for me and actor is local
        forwardToExchange(msg).unit
      case (false, true) => {
        // If message is for me I need to check if it is one of
        // the inProgressCalls, in such case resolve the promise.
        handleRespone(msg).unit
      }
    }
  }
  def send(msg: MessageEnvelope.Send): UIO[Unit] = {
    if (msg.to.isRemote || msg.to == id) {
      mailbox.send(msg)
    } else {
      forwardToExchange(msg).unit
    }
  }
  // I want to prevent direct calls to this function
  // Since it should only be used from OTPNode
  def mailbox(accessKey: OTPNode.AccessKey): OtpMbox = mbox

  // The order here is important since we need to run finalizers in reverse order
  def start(actorFiber: Fiber.Runtime[_, _]) = {
    fibers += Symbol("actorLoop") -> actorFiber
    for {
      mailboxFibers <- mailbox.start(scope)
      _             <- ZIO.succeed(fibers ++= mailboxFibers)
      _             <- scope.addFinalizerExit(onExit)
    } yield ()
  }

  /*
   * Use it for tests only
   */
  def interruptNamedFiber(fiberName: Symbol)(implicit trace: Trace): UIO[Unit] = {
    fibers.get(fiberName).get.interrupt.unit
  }

  private def terminateInProgressCalls = {
    val stream = zio.stream.ZStream.fromIterable(inProgressCalls)
    for {
      _ <- stream.foreach(entry => {
        val (ref, resultChannel) = entry
        resultChannel.succeed(Left(Node.Error.Disconnected())).unit
      })
    } yield ()
  }

  def onExit(exit: Exit[_, _]) = {
    if (!isFinalized.getAndSet(true)) {
      for {
        _ <- terminateInProgressCalls
        // Closing scope with reason to propagate correct reason
        _ <- scope.close(exit)
      } yield ()
    } else {
      ZIO.unit
    }
  }
  def onStop(result: ActorResult): UIO[Unit] = {
    if (!isFinalized.getAndSet(true)) {
      for {
        _ <- terminateInProgressCalls
        // Closing scope with reason to propagate correct reason
        _ <- scope.close(Exit.succeed(result))
      } yield ()
    } else {
      ZIO.unit
    }
  }

  private def mapResponse(msg: MessageEnvelope.Response): MessageEnvelope.Send = {
    (msg.payload, msg.reason) match {
      case (Some(payload), None) =>
        MessageEnvelope.Send(msg.from, msg.to, Codec.ETuple(msg.replyRef, payload), msg.to)
      case (None, Some(reason)) =>
        // https://github.com/erlang/otp/blob/master/lib/stdlib/src/gen.erl#L340
        // this is very unlikely to happen we return something just to help with
        // diagnostics
        val payload = Codec.ETuple(
          Codec.EAtom("error"),
          Codec.ETuple(Codec.EBinary(reason.toString()), msg.from.get)
        )
        MessageEnvelope.Send(msg.from, msg.to, Codec.ETuple(msg.replyRef, Codec.EAtom("error")), msg.to)
      case (_, _) =>
        throw new Throwable("unreachable")
    }
  }

}

object OTPProcessContext {
  type Ready    = Builder.Ready
  type Complete = Builder[State.Seeded with State.Ready with State.Builder]
  type Seeded   = Builder.Seeded
  sealed trait State
  object State {
    sealed trait Initial       extends State
    sealed trait MessageBox    extends Initial
    sealed trait ProcessName   extends State
    sealed trait Capacity      extends State
    sealed trait Worker        extends State
    sealed trait Builder       extends State
    sealed trait NodeName      extends State
    sealed trait MeterRegistry extends State
    sealed trait Scope         extends State
    type Seeded   = Initial with NodeName
    type Ready    = Seeded with Worker with MessageBox with Scope with MeterRegistry
    type Complete = Seeded with Ready with Builder
  }

  case class Builder[S <: State] private (
    otpMbox: Option[OtpMbox] = None,
    name: Option[String] = None,
    capacity: Option[Int] = None,
    worker: Option[OTPEngineWorker] = None,
    nodeName: Option[Symbol] = None,
    meterRegistry: Option[Metrics.Registry[_]] = None,
    scope: Option[Scope.Closeable] = None
  ) {
    def withOtpMbox(mbox: OtpMbox): Builder[S with State.MessageBox] = {
      this.copy(otpMbox = Some(mbox))
    }
    def withName(name: Option[String]): Builder[S with State.ProcessName] = {
      this.copy(name = name)
    }
    def withWorker(worker: OTPEngineWorker): Builder[S with State.Worker] = {
      this.copy(worker = Some(worker))
    }
    def withNodeName(nodeName: Symbol): Builder[S with State.NodeName] = {
      this.copy(nodeName = Some(nodeName))
    }
    def withBuilder[A <: Actor](builder: ActorBuilder.Sealed[A]): Builder[S with State.Builder] = {
      if (builder.capacity.nonEmpty) {
        this.copy(capacity = builder.capacity, name = builder.name)
      } else {
        this.copy(name = builder.name)
      }
    }
    def withScope(scope: Scope.Closeable): Builder[S with State.Scope] = {
      this copy (scope = Some(scope))
    }
    def withMeterRegistry(registry: Metrics.Registry[_]): Builder[S with State.MeterRegistry] = {
      this copy (meterRegistry = Some(registry))
    }
    def getMbox()(implicit ev: S =:= State.Ready): OtpMbox = {
      // it is safe to use .get since we require State.Ready
      this.otpMbox.get
    }
    def getWorkerId()(implicit ev: S =:= State.Seeded with State.Ready): Engine.WorkerId = {
      // it is safe to use .get since we require State.Seeded
      this.worker.get.id
    }
    def getMeterRegistry()(implicit ev: S =:= State.Ready): Metrics.Registry[_] = {
      // it is safe to use .get since we require State.Ready
      this.meterRegistry.get
    }

    def getNodeName()(implicit ev: S =:= State.Ready): Symbol = {
      // it is safe to use .get since we require State.Ready
      this.nodeName.get
    }

    def getCapacity(): Option[Int] = capacity

    def build()(implicit ev: S =:= State.Complete with State.Ready): UIO[OTPProcessContext] = {
      for {
        mailbox <- OTPMailbox.make(this.asInstanceOf[OTPProcessContext.Ready])
      } yield new OTPProcessContext(
        name,
        mailbox,
        meterRegistry.get,
        // it is safe to use .get since we require State.Complete
        scope.get,
        worker.get,
        otpMbox.get
      )
    }
  }

  object Builder {
    type Ready    = Builder[State.Ready]
    type Seeded   = Builder[State.Seeded]
    type Complete = Builder[State.Complete]
    def apply[S <: State]() = new Builder[State.Initial]()
  }

  def builder(name: Symbol): Builder[State.Seeded] = {
    Builder()
      .withNodeName(name)
  }
}
