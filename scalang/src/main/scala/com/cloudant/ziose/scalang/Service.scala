package com.cloudant.ziose.scalang

import zio._
import _root_.com.cloudant.ziose.core
import core.Codec
import core.Codec.EAtom
import core.Codec.EPid
import core.Codec.ERef
import core.Codec.ETerm
import core.Codec.ETuple
import core.Address
import core.MessageEnvelope
import core.ProcessContext
import java.util.concurrent.TimeUnit

trait ProcessLike[A <: Adapter[_]] extends core.Actor {
  type RegName = Symbol
  type NodeName = Symbol

  val adapter: A
  def self: Address

  def send(pid : Pid, msg : Any) = {
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(sendZIO(pid, msg))
    }
  }

  def send(name : RegName, msg : Any): UIO[Unit] = {
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(sendZIO(name, msg))
    }
  }

  def send(dest : (RegName, NodeName), from: Pid, msg : Any) = {
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(sendZIO(dest, from, msg))
    }
  }

  def sendZIO(pid : Pid, msg : Any) = {
    val address = Address.fromPid(pid.fromScala, self.workerId)
    val envelope = MessageEnvelope.makeSend(address, Codec.fromScala(msg), self.workerId)
    adapter.send(envelope)
  }
  def sendZIO(name : RegName, msg : Any) = {
    val address = Address.fromName(Codec.EAtom(name), self.workerId)
    val envelope = MessageEnvelope.makeSend(address, Codec.fromScala(msg), self.workerId)
    adapter.send(envelope)
  }
  def sendZIO(dest : (RegName, NodeName), from: Pid, msg : Any) = {
    val (name, node) = dest
    val address = Address.fromRemoteName(Codec.EAtom(name), Codec.EAtom(node), self.workerId)
    val envelope = MessageEnvelope.makeRegSend(from.fromScala, address, Codec.fromScala(msg), self.workerId)
    adapter.send(envelope)
  }

  def handleMessage(msg : Any)

  def handleExit(from : Pid, reason : Any) = {
    exit(reason)
  }

  def handleMonitorExit(monitored : Any, ref : Reference, reason : Any)

  def exit(reason : Any) =
    adapter.exit(Codec.fromScala(reason))

  def unlink(to : Pid) =
    adapter.unlink(to.fromScala)

  def link(to : Pid) =
    adapter.link(to.fromScala)

  def monitor(monitored : Any): Reference = {
    val ref = monitored match {
      case pid: Pid => adapter.monitor(Address.fromPid(pid.fromScala, self.workerId))
      case atom: Symbol => adapter.monitor(Address.fromName(Codec.EAtom(atom), self.workerId))
      case (name: RegName, nodeName: NodeName) => adapter.monitor(Address.fromRemoteName(Codec.EAtom(name), Codec.EAtom(nodeName), self.workerId))
    }
    Reference.toScala(ref)
  }

  def demonitor(ref : Reference) =
    adapter.demonitor(ref.fromScala)
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
class Process(implicit val adapter: Adapter[_]) extends ProcessLike[Adapter[_]] {
  val name = adapter.name
  val self = adapter.self
  val node = adapter.node

  // Original scalang has following API functions defined
  // - `def sendEvery(pid : Pid, msg : Any, delay : Long) =`
  // - `def sendEvery(name : Symbol, msg : Any, delay : Long) =`
  // - `def sendEvery(dest : (RegName, NodeName), msg : Any, delay : Long) =`
  // TODO: Verify how we use them in Clouseau and implement (with help of `sendEveryZIO`) if we need them


  def sendEveryZIO(pid : Pid, msg : Any, delay : Long) = {
    val effect = for {
      _ <- sendZIO(pid, msg)
    } yield ()
    val interval = Duration(delay, TimeUnit.MILLISECONDS)
    effect.schedule(Schedule.spaced(interval))
  }

  def sendEveryZIO(name : Symbol, msg : Any, delay : Long) = {
    val effect = for {
      _ <- sendZIO(name, msg)
    } yield ()
    val interval = Duration(delay, TimeUnit.MILLISECONDS)
    effect.schedule(Schedule.spaced(interval))
  }

  def sendEveryZIO(dest : (RegName, NodeName), msg : Any, delay : Long) = {
    val effect = for {
      _ <- sendZIO(dest, Pid.toScala(self.pid), msg)
    } yield ()
    val interval = Duration(delay, TimeUnit.MILLISECONDS)
    effect.schedule(Schedule.spaced(interval))
  }

  def sendAfter(pid : Pid, msg : Any, delay : Long) = {
    val effect = for {
      _ <- sendZIO(pid, msg)
    } yield ()
    val duration = Duration(delay, TimeUnit.MILLISECONDS)
    effect.delay(duration)
  }
  def sendAfter(name : Symbol, msg : Any, delay : Long) = {
    val effect = for {
    _ <- sendZIO(name, msg)
    } yield ()
    val duration = Duration(delay, TimeUnit.MILLISECONDS)
    effect.delay(duration)
  }
  def sendAfter(dest : (RegName, NodeName), msg : Any, delay : Long) = {
    val effect = for {
      _ <- sendZIO(dest, Pid.toScala(self.pid), msg)
    } yield ()
      val duration = Duration(delay, TimeUnit.MILLISECONDS)
      effect.delay(duration)
    }

  def onMessage[PContext <: ProcessContext](event: MessageEnvelope, ctx: PContext): ZIO[Any, Throwable, Unit] = {
    event.getPayload match {
      case None => ZIO.succeed(handleMessage(()))
      case Some(value) => ZIO.succeed(handleMessage(Codec.toScala(value)))
    }
  }

  def onTermination[PContext <: ProcessContext](reason: ETerm, ctx: PContext): UIO[Unit] = {
    ZIO.logError(s"${getClass} did not define a onTermination function.")
    ZIO.succeed(())
  }

  override def handleMessage(msg : Any) = ()

  override def handleExit(from : Pid, msg : Any) =
    trapExit(from, msg)

  /**
   * Subclasses wishing to trap exits should override this method.
   */
  def trapExit(from : Pid, msg : Any) =
    exit(msg)

  def handleMonitorExit(monitored : Any, ref : Reference, reason : Any) =
    trapMonitorExit(monitored, ref, reason)

  /**
   * Subclasses wishing to trap monitor exits should override this method.
   */
  def trapMonitorExit(monitored : Any, ref : Reference, reason : Any) = ()
}

trait ServiceContext[A <: Product] {
  def args : A
}

class Service[A <: Product](ctx: ServiceContext[A])(implicit adapter: Adapter[_]) extends Process()(adapter) {
  /**
   * Handle a call style of message which will expect a response.
   */
  def handleCall(tag : (Pid, Any), request : Any): Any =
    throw new Exception(getClass + " did not define a call handler.")

  /**
   * Handle a cast style of message which will receive no response.
   */
  def handleCast(request : Any): Any =
    throw new Exception(getClass + " did not define a cast handler.")

  /**
   * Handle any messages that do not fit the call or cast pattern.
   */
  def handleInfo(request : Any): Any =
    throw new Exception(getClass + " did not define an info handler.")

  override def onMessage[PContext <: ProcessContext](event: MessageEnvelope, ctx: PContext): ZIO[Any, Throwable, Unit] = {
      event.getPayload match {
        case Some(ETuple(List(EAtom('ping), from : EPid, ref : ERef))) => {
          val address = Address.fromPid(from, self.workerId)
          adapter.send(MessageEnvelope.makeSend(address, Codec.fromScala((Symbol("pong"), ref)), self.workerId)).unit
        }
        case Some(ETuple(List(EAtom(Symbol("$gen_call")), ETuple(List(from : EPid, ref : ERef)), request : ETerm))) => {
          val fromPid = Pid.toScala(from)
          val result = try {
            handleCall((fromPid, ref), request)
          } catch {
            case err : Throwable => {
              println(s"onMessage Throwable ${err}")
              return ZIO.fail(err)
            }
          }
          for {
            _ <- result match {
              case (Symbol("reply"), reply) =>
                sendZIO(fromPid, (ref, reply))
              case Symbol("noreply") =>
                ZIO.succeed(())
              case reply =>
                sendZIO(fromPid, (ref, reply))
            }
          } yield ()
        }
        case Some(ETuple(List(EAtom(Symbol("$gen_cast")), request : Any))) =>
          ZIO.succeed(handleCast(request))
        case Some(info: ETerm) => {
          try {
            return ZIO.succeed(handleInfo(Codec.toScala(info)))
          } catch {
            case err : Throwable => {
              println(s"onMessage Throwable ${err.getMessage()}")
              return ZIO.fail(err)
            }
          }
        }
        case Some(info) => {
          try {
            return ZIO.succeed(handleInfo(info))
          } catch {
            case err : Throwable => {
              println(s"onMessage Throwable ${err.getMessage()}")
              return ZIO.fail(err)
            }
          }
        }
        case None => ??? // shouldn't happen
      }
  }

  def call(to : Pid, msg : Any): Any = node.call(to, msg)
  def call(to : Pid, msg : Any, timeout : Long) : Any = node.call(to, msg, timeout)
  def call(to : Symbol, msg : Any) : Any = node.call(to, msg)
  def call(to : Symbol, msg : Any, timeout : Long) : Any = node.call(to, msg, timeout)
  def call(to : (RegName, NodeName), msg : Any) : Any = node.call(to, msg)
  def call(to : (RegName, NodeName), msg : Any, timeout : Long) : Any = node.call(to, msg, timeout)

  def cast(to : Pid, msg : Any) = node.cast(to, msg)
  def cast(to : Symbol, msg : Any) = node.cast(to, msg)
  def cast(to : (RegName, NodeName), msg : Any) = node.cast(to, msg)
}

class PidSend(to : Pid, proc : Process) {
  def !(msg : Any): Unit =
    proc.sendZIO(to, msg)
}

class SymSend(to : Symbol, proc : Process) {
  def !(msg : Any): Unit =
    proc.sendZIO(to, msg)
}

class DestSend(to : (Symbol,Symbol), from : Pid, proc : Process) {
  def !(msg : Any): Unit =
    proc.sendZIO(to, from, msg)
}
