package com.cloudant.ziose.scalang

import zio._
import _root_.com.cloudant.ziose.core
import core.Codec
import core.Codec.EAtom
import core.Codec.EPid
import core.Codec.ERef
import core.Codec.ETerm
import core.Codec.ETuple
import core.Codec.EListImproper
import core.Address
import core.MessageEnvelope
import core.ProcessContext
import java.util.concurrent.TimeUnit

trait Error                                  extends Throwable {}
case class HandleCallCBError(err: Throwable) extends Error
case class HandleCastCBError(err: Throwable) extends Error
case class HandleInfoCBError(err: Throwable) extends Error
case class UnreachableError()                extends Error
case class HandleCallUndefined(className: String) extends Error {
  override def toString(): String = "HandleCallUndefined(" + className + ") did not define a call handler"
}
case class HandleCastUndefined(className: String) extends Error {
  override def toString(): String = "HandleCastUndefined(" + className + ") did not define a cast handler"
}
case class HandleInfoUndefined(className: String) extends Error {
  override def toString(): String = "HandleInfoUndefined(" + className + ") did not define a info handler"
}

trait ProcessLike[A <: Adapter[_, _]] extends core.Actor {
  type RegName  = Symbol
  type NodeName = Symbol

  /// We are not accessing this type directly in Clouseau
  type Mailbox = Unit

  val adapter: A
  def self: Address

  def send(pid: Pid, msg: Any) = {
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(sendZIO(pid, msg))
    }
  }

  def send(name: RegName, msg: Any): UIO[Unit] = {
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(sendZIO(name, msg))
    }
  }

  def send(dest: (RegName, NodeName), from: Pid, msg: Any) = {
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(sendZIO(dest, from, msg))
    }
  }

  def sendZIO(pid: Pid, msg: Any) = {
    val address  = Address.fromPid(pid.fromScala, self.workerId)
    val envelope = MessageEnvelope.makeSend(address, Codec.fromScala(msg), self.workerId)
    adapter.send(envelope)
  }
  def sendZIO(name: RegName, msg: Any) = {
    val address  = Address.fromName(Codec.EAtom(name), self.workerId)
    val envelope = MessageEnvelope.makeSend(address, Codec.fromScala(msg), self.workerId)
    adapter.send(envelope)
  }
  def sendZIO(dest: (RegName, NodeName), from: Pid, msg: Any) = {
    val (name, node) = dest
    val address      = Address.fromRemoteName(Codec.EAtom(name), Codec.EAtom(node), self.workerId)
    val envelope     = MessageEnvelope.makeRegSend(from.fromScala, address, Codec.fromScala(msg), self.workerId)
    adapter.send(envelope)
  }

  def handleMessage(msg: Any): Unit

  // TODO: Fully evaluate the effect and return Unit
  def handleExit(from: Pid, reason: Any) = {
    exit(reason)
  }

  def handleMonitorExit(monitored: Any, ref: Reference, reason: Any): Unit

  def exit(reason: Any) = {
    Unsafe.unsafe { implicit unsafe =>
      adapter.runtime.unsafe.run(adapter.exit(Codec.fromScala(reason)))
    }
    ()
  }

  def unlink(to: Pid) = {
    adapter.unlink(to.fromScala)
  }

  def link(to: Pid) = {
    adapter.link(to.fromScala)
  }

  def monitor(monitored: Any): Reference = {
    val ref = monitored match {
      case pid: Pid     => adapter.monitor(Address.fromPid(pid.fromScala, self.workerId))
      case atom: Symbol => adapter.monitor(Address.fromName(Codec.EAtom(atom), self.workerId))
      case (name: RegName, nodeName: NodeName) =>
        adapter.monitor(Address.fromRemoteName(Codec.EAtom(name), Codec.EAtom(nodeName), self.workerId))
    }
    Reference.toScala(ref)
  }

  def demonitor(ref: Reference) = {
    adapter.demonitor(ref.fromScala)
  }
}

/*
`Process` is only used from tests
clouseau/RunningNode.scala

trait RunningNode extends BeforeAfter {

object EpmdCmd {
  def apply(): Process = {
    val builder = new ProcessBuilder("epmd")
    builder.start
  }
}
 */

/*
ProcessAdapter is only used from tests
class FakeServiceContext[A <: Product](serviceArgs: A) extends ServiceContext[A] {
  ...
  var adapter: ProcessAdapter = null
}

TODO Since `ProcessAdapter` and `Process` are only used in the tests we can try to remove this abstractions.
Because dealing with `Actor -> ProcessLike -> Process -> Service -> EchoService` chain could be challenging.

What we can do is:

1. investigate the complexity of these tests.
2. disable these specific tests
3. re-implement these tests in separate file using different means

In clouseau builds we would be running old tests
In ziose builds we would be running new ones

 */
class Process(implicit val adapter: Adapter[_, _]) extends ProcessLike[Adapter[_, _]] {
  val runtime = adapter.runtime
  val name    = adapter.name
  val self    = adapter.self
  val node    = adapter.node

  implicit def pid2sendable(pid: core.PID): PidSend            = new PidSend(pid, this)
  implicit def pid2sendable(pid: Pid): PidSend                 = new PidSend(pid, this)
  implicit def sym2sendable(to: Symbol): SymSend               = new SymSend(to, this)
  implicit def dest2sendable(dest: (Symbol, Symbol)): DestSend = new DestSend(dest, self, this)

  def sendEvery(pid: Pid, msg: Any, delay: Long) = {
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .run(
          sendEveryZIO(pid, msg, delay)
        )
        .getOrThrowFiberFailure() // TODO: TBD should we kill the caller
    }
  }

  def sendEvery(name: Symbol, msg: Any, delay: Long) = {
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .run(
          sendEveryZIO(name, msg, delay)
        )
        .getOrThrowFiberFailure() // TODO: TBD should we kill the caller
    }
  }

  def sendEvery(dest: (RegName, NodeName), msg: Any, delay: Long) = {
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .run(
          sendEveryZIO(dest, msg, delay)
        )
        .getOrThrowFiberFailure() // TODO: TBD should we kill the caller
    }
  }

  def sendEveryZIO(pid: Pid, msg: Any, delay: Long) = {
    val effect = for {
      _ <- sendZIO(pid, msg)
    } yield ()
    val interval = Duration(delay, TimeUnit.MILLISECONDS)
    effect.schedule(Schedule.spaced(interval))
  }

  def sendEveryZIO(name: Symbol, msg: Any, delay: Long) = {
    val effect = for {
      _ <- sendZIO(name, msg)
    } yield ()
    val interval = Duration(delay, TimeUnit.MILLISECONDS)
    effect.schedule(Schedule.spaced(interval))
  }

  def sendEveryZIO(dest: (RegName, NodeName), msg: Any, delay: Long) = {
    val effect = for {
      _ <- sendZIO(dest, Pid.toScala(self.pid), msg)
    } yield ()
    val interval = Duration(delay, TimeUnit.MILLISECONDS)
    effect.schedule(Schedule.spaced(interval))
  }

  def sendAfter(pid: Pid, msg: Any, delay: Long) = {
    val effect = for {
      _ <- sendZIO(pid, msg)
    } yield ()
    val duration = Duration(delay, TimeUnit.MILLISECONDS)
    effect.delay(duration)
  }
  def sendAfter(name: Symbol, msg: Any, delay: Long) = {
    val effect = for {
      _ <- sendZIO(name, msg)
    } yield ()
    val duration = Duration(delay, TimeUnit.MILLISECONDS)
    effect.delay(duration)
  }
  def sendAfter(dest: (RegName, NodeName), msg: Any, delay: Long) = {
    val effect = for {
      _ <- sendZIO(dest, Pid.toScala(self.pid), msg)
    } yield ()
    val duration = Duration(delay, TimeUnit.MILLISECONDS)
    effect.delay(duration)
  }

  def onMessage[PContext <: ProcessContext](event: MessageEnvelope, ctx: PContext): ZIO[Any, Throwable, Unit] = {
    event.getPayload match {
      case None        => ZIO.succeed(handleMessage(()))
      case Some(value) => ZIO.succeed(handleMessage(Codec.toScala(value)))
    }
  }

  def onTermination[PContext <: ProcessContext](reason: ETerm, ctx: PContext): UIO[Unit] = {
    ZIO.logError(s"${getClass.toString} did not define a onTermination function.")
    ZIO.succeed(())
  }

  override def handleMessage(msg: Any) = ()

  override def handleExit(from: Pid, msg: Any) = {
    trapExit(from, msg)
  }

  /**
   * Subclasses wishing to trap exits should override this method.
   */
  def trapExit(from: Pid, msg: Any) = {
    exit(msg)
  }

  def handleMonitorExit(monitored: Any, ref: Reference, reason: Any) = {
    trapMonitorExit(monitored, ref, reason)
  }

  /**
   * Subclasses wishing to trap monitor exits should override this method.
   */
  def trapMonitorExit(monitored: Any, ref: Reference, reason: Any) = ()
}

trait ServiceContext[A <: Product] {
  def args: A
}

class PidSend(to: Pid, proc: Process) {
  def !(msg: Any): Unit = {
    proc.sendZIO(to, msg)
  }
}

class SymSend(to: Symbol, proc: Process) {
  def !(msg: Any): Unit = {
    proc.sendZIO(to, msg)
  }
}

class DestSend(to: (Symbol, Symbol), from: Pid, proc: Process) {
  def !(msg: Any): Unit = {
    proc.sendZIO(to, from, msg)
  }
}

class Service[A <: Product](ctx: ServiceContext[A])(implicit adapter: Adapter[_, _]) extends Process()(adapter) {

  /**
   * Handle a call style of message which will expect a response.
   */
  def handleCall(tag: (Pid, Any), request: Any): Any = {
    throw HandleCallUndefined(getClass.toString)
  }

  /**
   * Handle a cast style of message which will receive no response.
   */
  def handleCast(request: Any): Any = {
    throw HandleCastUndefined(getClass.toString)
  }

  /**
   * Handle any messages that do not fit the call or cast pattern.
   */
  def handleInfo(request: Any): Any = {
    throw HandleInfoUndefined(getClass.toString)
  }

  // OTP uses improper list in `gen.erl`
  // https://github.com/erlang/otp/blob/master/lib/stdlib/src/gen.erl#L252C11-L252C20
  //  Tag = [alias | Mref],
  def makeTag(ref: ERef) = EListImproper(EAtom(Symbol("alias")), ref)

  override def onMessage[PContext <: ProcessContext](
    event: MessageEnvelope,
    ctx: PContext
  ): ZIO[Any, Throwable, Unit] = {
    event.getPayload match {
      case Some(ETuple(EAtom(Symbol("ping")), from: EPid, ref: ERef)) => {
        val fromPid = Pid.toScala(from)
        sendZIO(fromPid, (Symbol("pong"), ref))
      }
      case Some(
            ETuple(
              EAtom(Symbol("$gen_call")),
              // Match on {pid(), [alias | ref()]}
              ETuple(from: EPid, EListImproper(EAtom(Symbol("alias")), ref: ERef)),
              request: ETerm
            )
          ) => {

        val fromPid = Pid.toScala(from)
        try {
          val result = handleCall((fromPid, ref), adapter.toScala(request))
          for {
            _ <- result match {
              case (Symbol("reply"), reply) =>
                sendZIO(fromPid, (makeTag(ref), reply))
              case Symbol("noreply") =>
                ZIO.unit
              case reply =>
                sendZIO(fromPid, (makeTag(ref), Codec.fromScala(reply)))
            }
          } yield ()
        } catch {
          case err: Throwable => {
            println(s"onMessage Throwable ${err.getMessage()}")
            ZIO.fail(HandleCallCBError(err))
          }
        }
      }
      case Some(ETuple(EAtom(Symbol("$gen_cast")), request: ETerm)) => {
        try {
          ZIO.succeed(handleCast(adapter.toScala(request))).unit
        } catch {
          case err: Throwable => {
            println(s"onMessage Throwable ${err.getMessage()}")
            ZIO.fail(HandleCastCBError(err))
          }
        }
      }
      case Some(info: ETerm) => {
        try {
          ZIO.succeed(handleInfo(adapter.toScala(info))).unit
        } catch {
          case err: Throwable => {
            println(s"onMessage Throwable ${err.getMessage()}")
            ZIO.fail(HandleInfoCBError(err))
          }
        }
      }
      case Some(info) => {
        println(s"nothing matched but it is not a ETerm $info")
        try {
          ZIO.succeed(handleInfo(info)).unit
        } catch {
          case err: Throwable => {
            println(s"onMessage Throwable ${err.getMessage()}")
            ZIO.fail(HandleInfoCBError(err))
          }
        }
      }
      case None => ZIO.fail(UnreachableError())
    }
  }

  def call(to: Pid, msg: Any): Any                                = node.call(to, msg)
  def call(to: Pid, msg: Any, timeout: Long): Any                 = node.call(to, msg, timeout)
  def call(to: Symbol, msg: Any): Any                             = node.call(to, msg)
  def call(to: Symbol, msg: Any, timeout: Long): Any              = node.call(to, msg, timeout)
  def call(to: (RegName, NodeName), msg: Any): Any                = node.call(to, msg)
  def call(to: (RegName, NodeName), msg: Any, timeout: Long): Any = node.call(to, msg, timeout)

  def cast(to: Pid, msg: Any)                 = node.cast(to, msg)
  def cast(to: Symbol, msg: Any)              = node.cast(to, msg)
  def cast(to: (RegName, NodeName), msg: Any) = node.cast(to, msg)
}
