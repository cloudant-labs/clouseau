package com.cloudant.ziose.otp

import zio._
import zio.stream.ZStream

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

import collection.mutable.Set
import com.cloudant.ziose.core.Node

class OTPProcessContext private (
  val name: Option[String],
  val mailbox: OTPMailbox,
  val worker: EngineWorker,
  private val mbox: OtpMbox,
  private val monitorers: Set[Product2[Codec.EPid, Codec.ERef]]
) extends ProcessContext {
  val id                        = mailbox.id
  val engineId: Engine.EngineId = worker.engineId
  val workerId: Engine.WorkerId = worker.id
  val nodeName: Symbol          = worker.nodeName
  val self                      = PID(new Codec.EPid(mailbox.externalMailbox.self), worker.id, worker.nodeName)

  override def toString: String = name match {
    case Some(n) => s"OTPProcessContext(${n}.${workerId}.${engineId}@${nodeName})"
    case None    => s"OTPProcessContext(${self.pid}.${workerId}.${engineId}@${nodeName})"
  }

  def capacity: Int = mailbox.capacity
  override def awaitShutdown(implicit trace: Trace): UIO[Unit] = {
    mailbox.awaitShutdown
  }
  def isShutdown(implicit trace: Trace): UIO[Boolean] = {
    mailbox.isShutdown
  }
  def shutdown(implicit trace: Trace): UIO[Unit] = {
    mailbox.shutdown
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
  def unlink(to: Codec.EPid)                  = mailbox.unlink(to)
  def link(to: Codec.EPid)                    = mailbox.link(to)
  def monitor(monitored: Address): Codec.ERef = mailbox.monitor(monitored)
  def demonitor(ref: Codec.ERef)              = mailbox.demonitor(ref)

  def stream: ZStream[Any, Throwable, MessageEnvelope] = mailbox.stream

  def call(msg: MessageEnvelope.Call): ZIO[Node, _ <: Node.Error, MessageEnvelope.Response] = {
    mailbox.call(msg)
  }
  def cast(msg: MessageEnvelope.Cast): UIO[Unit] = {
    mailbox.cast(msg)
  }
  def send(msg: MessageEnvelope.Send): UIO[Unit] = {
    mailbox.send(msg)
  }
  // I want to prevent direct calls to this function
  // Since it should only be used from OTPNode
  def mailbox(accessKey: OTPNode.AccessKey): OtpMbox = mbox
  def start(scope: Scope)                            = mailbox.start(scope)

  def addMonitorer(from: Option[Codec.EPid], ref: Codec.ERef): UIO[Unit] = for {
    _ <- from match {
      case Some(pid) =>
        ZIO.succeed(monitorers += Tuple2(pid, ref))
      case None =>
        ZIO.succeed(())
    }
  } yield ()

  def removeMonitorer(from: Option[Codec.EPid], ref: Codec.ERef): UIO[Unit] = for {
    _ <- from match {
      case Some(pid) =>
        ZIO.succeed(monitorers -= Tuple2(pid, ref))
      case None =>
        ZIO.succeed(())
    }
  } yield ()

  def notifyMonitorers(reason: Codec.ETerm) = {
    // println(s"monitorers: $monitorers")
    for (Tuple2(monitorer, ref) <- monitorers) {
      mailbox.sendMonitorExit(monitorer, ref, reason)
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
    type Seeded   = Initial with NodeName
    type Ready    = Seeded with Worker with MessageBox
    type Complete = Seeded with Ready with Builder
  }

  case class Builder[S <: State] private (
    otpMbox: Option[OtpMbox] = None,
    name: Option[String] = None,
    capacity: Option[Int] = None,
    worker: Option[OTPEngineWorker] = None,
    nodeName: Option[Symbol] = None
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
      worker.get,
      otpMbox.get,
      Set()
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
