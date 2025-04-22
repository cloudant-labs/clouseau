package com.cloudant.ziose.otp

import java.util.concurrent.atomic.AtomicBoolean

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

import com.cloudant.ziose.core.Node
import com.cloudant.ziose.core.ActorResult
import scala.collection.mutable.ListBuffer

class OTPProcessContext private (
  val name: Option[String],
  val mailbox: OTPMailbox,
  val scope: Scope.Closeable,
  val worker: EngineWorker,
  private val mbox: OtpMbox
) extends ProcessContext {
  val id                                               = mailbox.id
  val engineId: Engine.EngineId                        = worker.engineId
  val workerId: Engine.WorkerId                        = worker.id
  val nodeName: Symbol                                 = worker.nodeName
  private var fibers: Map[Symbol, Fiber.Runtime[_, _]] = Map()
  val self                                             = PID(new Codec.EPid(mbox.self), worker.id, worker.nodeName)
  private val isFinalized: AtomicBoolean               = new AtomicBoolean(false)

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

  override def toString: String = name match {
    case Some(n) => s"OTPProcessContext(${n}.${worker.id}.${worker.engineId}@${nodeName})"
    case None    => s"OTPProcessContext(${self.pid}.${worker.id}.${worker.engineId}@${nodeName})"
  }

  def capacity: Int = mailbox.capacity
  override def awaitShutdown(implicit trace: Trace): UIO[Unit] = {
    mailbox.awaitShutdown
  }
  def isShutdown(implicit trace: Trace): UIO[Boolean] = {
    mailbox.isShutdown
  }
  def shutdown(implicit trace: Trace): UIO[Unit] = {
    if (!isFinalized.getAndSet(true)) { mailbox.shutdown }
    else { ZIO.unit }
  }
  def offer(msg: MessageEnvelope)(implicit trace: zio.Trace): UIO[Boolean] = {
    mailbox.offer(msg)
  }
  def offerAll[A1 <: MessageEnvelope](as: Iterable[A1])(implicit trace: zio.Trace): UIO[zio.Chunk[A1]] = {
    mailbox.offerAll(as)
  }
  def size(implicit trace: zio.Trace): UIO[Int] = {
    mailbox.size
  }

  def exit(reason: Codec.ETerm): UIO[Unit] = {
    mailbox.exit(MessageEnvelope.Exit(None, id, reason, mailbox.id))
  }

  def exit(msg: MessageEnvelope.Exit): UIO[Unit] = {
    if (msg.to.isRemote || msg.to == id) {
      mailbox.exit(msg)
    } else {
      worker.offer(msg).unit
    }
  }

  def unlink(to: Codec.EPid) = mailbox.unlink(to)
  def unlink(msg: MessageEnvelope.Unlink) = {
    if (msg.to.isRemote || msg.to == id) {
      mailbox.unlink(msg.from.get)
    } else {
      worker.offer(msg.forward).unit
    }
  }

  def link(to: Codec.EPid) = mailbox.link(to)
  def link(msg: MessageEnvelope.Link) = {
    if (msg.to.isRemote && msg.from.get == id.pid) {
      mailbox.link(msg.from.get)
    } else {
      worker.offer(msg.forward).unit
    }
  }

  def monitor(monitored: Address) = mailbox.monitor(monitored)
  def demonitor(ref: Codec.ERef)  = mailbox.demonitor(ref)

  def nextEvent: ZIO[Any, Nothing, Option[MessageEnvelope]] = mailbox.nextEvent

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

  def call(msg: MessageEnvelope.Call): ZIO[Node, _ <: Node.Error, MessageEnvelope.Response] = {
    mailbox.call(msg)
  }
  def cast(msg: MessageEnvelope.Cast): UIO[Unit] = {
    mailbox.cast(msg)
  }
  def send(msg: MessageEnvelope.Send): UIO[Unit] = {
    if (msg.to.isRemote || msg.to == id) {
      mailbox.send(msg)
    } else {
      worker.offer(msg).unit
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

  def onExit(exit: Exit[_, _]) = {
    if (!isFinalized.getAndSet(true)) {
      for {
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
        // Closing scope with reason to propagate correct reason
        _ <- scope.close(Exit.succeed(result))
      } yield ()
    } else {
      ZIO.unit
    }
  }

}

object OTPProcessContext {
  type Ready    = Builder.Ready
  type Complete = Builder[State.Seeded with State.Ready with State.Builder]
  type Seeded   = Builder.Seeded
  sealed trait State
  object State {
    sealed trait Initial     extends State
    sealed trait MessageBox  extends Initial
    sealed trait ProcessName extends State
    sealed trait Capacity    extends State
    sealed trait Worker      extends State
    sealed trait Builder     extends State
    sealed trait NodeName    extends State
    sealed trait Scope       extends State
    type Seeded   = Initial with NodeName
    type Ready    = Seeded with Worker with MessageBox with Scope
    type Complete = Seeded with Ready with Builder
  }

  case class Builder[S <: State] private (
    otpMbox: Option[OtpMbox] = None,
    name: Option[String] = None,
    capacity: Option[Int] = None,
    worker: Option[OTPEngineWorker] = None,
    nodeName: Option[Symbol] = None,
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
    def getMbox()(implicit ev: S =:= State.Ready): OtpMbox = {
      // it is safe to use .get since we require State.Ready
      this.otpMbox.get
    }
    def getWorkerId()(implicit ev: S =:= State.Seeded with State.Ready): Engine.WorkerId = {
      // it is safe to use .get since we require State.Seeded
      this.worker.get.id
    }

    def getNodeName()(implicit ev: S =:= State.Ready): Symbol = {
      // it is safe to use .get since we require State.Ready
      this.nodeName.get
    }

    def getCapacity(): Option[Int] = capacity

    def build()(implicit ev: S =:= State.Complete with State.Ready): UIO[OTPProcessContext] = for {
      mailbox <- OTPMailbox.make(this.asInstanceOf[OTPProcessContext.Ready])
    } yield new OTPProcessContext(
      name,
      mailbox,
      // it is safe to use .get since we require State.Complete
      scope.get,
      worker.get,
      otpMbox.get
    )
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
