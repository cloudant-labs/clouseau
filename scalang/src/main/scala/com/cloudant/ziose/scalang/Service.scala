package com.cloudant.ziose.scalang

import _root_.com.cloudant.ziose.core
import core.ActorResult
import core.Codec
import core.Codec.EAtom
import core.Codec.EPid
import core.Codec.ERef
import core.Codec.ETerm
import core.Codec.ETuple
import core.Codec.EListImproper
import core.Address
import core.Node
import core.MessageEnvelope
import core.ProcessContext
import core.ZioSupport
import com.cloudant.ziose.macros.CheckEnv
import zio._

import java.util.concurrent.TimeUnit
import com.cloudant.ziose.core.Name
import com.cloudant.ziose.core.NameOnNode
import com.cloudant.ziose.core.PID

trait Error extends Throwable

case class HandleCallCBError(err: Throwable) extends Error {
  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"Error.${getClass.getSimpleName}",
    s"err=${err.getMessage}"
  )
}

object HandleCallCBError {
  def apply(err: Throwable) = {
    val exception  = new HandleCallCBError(err)
    val stackTrace = err.getStackTrace()
    exception.setStackTrace(stackTrace)
    exception
  }
}

case class HandleCastCBError(err: Throwable) extends Error {
  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"Error.${getClass.getSimpleName}",
    s"err=${err.getMessage}"
  )
}

object HandleCastCBError {
  def apply(err: Throwable) = {
    val exception  = new HandleCastCBError(err)
    val stackTrace = err.getStackTrace()
    exception.setStackTrace(stackTrace)
    exception
  }
}

case class HandleInfoCBError(err: Throwable) extends Error {
  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"Error.${getClass.getSimpleName}",
    s"err=${err.getMessage}"
  )
}

object HandleInfoCBError {
  def apply(err: Throwable) = {
    val exception  = new HandleInfoCBError(err)
    val stackTrace = err.getStackTrace()
    exception.setStackTrace(stackTrace)
    exception
  }
}

case class UnreachableError() extends Error
case class HandleCallUndefined(className: String) extends Error {
  override def toString: String = s"Error.${getClass.getSimpleName}($className) did not define a call handler"
}
case class HandleCastUndefined(className: String) extends Error {
  override def toString: String = s"Error.${getClass.getSimpleName}($className)did not define a cast handler"
}
case class HandleInfoUndefined(className: String) extends Error {
  override def toString: String = s"Error.${getClass.getSimpleName}($className) did not define a info handler"
}

object ProcessLike {
  type RegName  = Symbol
  type NodeName = Symbol
}

trait ProcessLike[A <: Adapter[_, _]] extends core.Actor {
  type RegName  = ProcessLike.RegName
  type NodeName = ProcessLike.NodeName

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
    val address  = Address.fromPid(pid.fromScala, self.workerId, self.workerNodeName)
    val envelope = MessageEnvelope.makeSend(address, adapter.fromScala(msg), self)
    adapter.send(envelope)
  }
  def sendZIO(name: RegName, msg: Any) = {
    val address  = Address.fromName(Codec.EAtom(name), self.workerId, self.workerNodeName)
    val envelope = MessageEnvelope.makeSend(address, adapter.fromScala(msg), self)
    adapter.send(envelope)
  }
  def sendZIO(dest: (RegName, NodeName), from: Pid, msg: Any) = {
    val (name, node) = dest
    val address      = Address.fromRemoteName(Codec.EAtom(name), Codec.EAtom(node), self.workerId, self.workerNodeName)
    val envelope     = MessageEnvelope.makeRegSend(from.fromScala, address, adapter.fromScala(msg), self)
    adapter.send(envelope)
  }

  def exit(pid: Pid, reason: Any) = {
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(exitZIO(pid, reason))
    }
  }

  def exit(name: RegName, reason: Any): UIO[Unit] = {
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe.run(exitZIO(name, reason))
    }
  }

  def exitZIO(pid: Pid, reason: Any) = {
    val address  = Address.fromPid(pid.fromScala, self.workerId, self.workerNodeName)
    val envelope = MessageEnvelope.Exit(None, address, adapter.fromScala(reason), self)
    adapter.exit(envelope)
  }
  def exitZIO(name: RegName, reason: Any) = {
    val address  = Address.fromName(Codec.EAtom(name), self.workerId, self.workerNodeName)
    val envelope = MessageEnvelope.Exit(None, address, adapter.fromScala(reason), self)
    adapter.exit(envelope)
  }

  def handleInit(): Unit
  def handleMessage(msg: Any): Unit

  // TODO: Fully evaluate the effect and return Unit
  def handleExit(from: Pid, reason: Any) = {
    exit(reason)
  }

  def handleMonitorExit(monitored: Any, ref: Reference, reason: Any): Unit

  def exit(reason: Any) = {
    Unsafe.unsafe { implicit unsafe =>
      adapter.runtime.unsafe.run(adapter.exit(adapter.fromScala(reason)))
    }
    ()
  }

  def unlink(to: Pid): Unit = {
    Unsafe.unsafe { implicit unsafe =>
      adapter.runtime.unsafe.run(adapter.unlink(to.fromScala))
    }
  }

  def link(to: Pid): Unit = {
    Unsafe.unsafe { implicit unsafe =>
      adapter.runtime.unsafe.run(adapter.link(to.fromScala)).getOrThrow()
    }
  }

  def monitorZIO(monitored: Any): ZIO[Node, _ <: Node.Error, Reference] = {
    for {
      ref <- monitored match {
        case address: Address => adapter.monitor(address)
        case pid: Pid         => adapter.monitor(Address.fromPid(pid.fromScala, self.workerId, self.workerNodeName))
        case atom: Symbol => adapter.monitor(Address.fromName(Codec.EAtom(atom), self.workerId, self.workerNodeName))
        case (name: RegName, nodeName: NodeName) =>
          adapter.monitor(
            Address.fromRemoteName(Codec.EAtom(name), Codec.EAtom(nodeName), self.workerId, self.workerNodeName)
          )
      }
    } yield Reference.toScala(ref)
  }

  def monitor(monitored: Any): Reference = {
    Unsafe.unsafe { implicit unsafe =>
      val rt = adapter.runtime.asInstanceOf[Runtime[core.Node]]
      rt.unsafe.run[Node.Error, Reference](monitorZIO(monitored)).getOrThrow()
    }
  }

  def demonitor(ref: Reference) = {
    Unsafe.unsafe { implicit unsafe =>
      adapter.runtime.unsafe.run(adapter.demonitor(ref.fromScala))
    }
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
class Process(implicit val adapter: Adapter[_, _]) extends ProcessLike[Adapter[_, _]] with ZioSupport {
  val runtime = adapter.runtime
  val name    = adapter.name
  val self    = adapter.self
  val node    = adapter.node

  implicit val process: Process = this

  implicit def pid2sendable(pid: core.PID): PidSend            = new PidSend(pid, this)
  implicit def pid2sendable(pid: Pid): PidSend                 = new PidSend(pid, this)
  implicit def sym2sendable(to: Symbol): SymSend               = new SymSend(to, this)
  implicit def dest2sendable(dest: (Symbol, Symbol)): DestSend = new DestSend(dest, self, this)

  def sendEvery(pid: Pid, msg: Any, delay: Long) = {
    val interval: Duration = Duration.fromMillis(delay)
    adapter.forkScoped(sendZIO(pid, msg).schedule(Schedule.spaced(interval))).unsafeRun
  }

  def sendEvery(name: Symbol, msg: Any, delay: Long) = {
    val interval: Duration = Duration.fromMillis(delay)
    adapter.forkScoped(sendZIO(name, msg).schedule(Schedule.spaced(interval))).unsafeRun
  }

  def sendEvery(dest: (RegName, NodeName), msg: Any, delay: Long) = {
    val interval: Duration = Duration.fromMillis(delay)
    adapter.forkScoped(sendZIO(dest, Pid.toScala(self.pid), msg).schedule(Schedule.spaced(interval))).unsafeRun
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

  def onInit[PContext <: ProcessContext](_ctx: PContext): ZIO[Any, Throwable, _ <: ActorResult] = {
    ZIO.succeed(handleInit()).as(ActorResult.Continue())
  }

  def onMessage[PContext <: ProcessContext](
    event: MessageEnvelope,
    ctx: PContext
  )(implicit trace: Trace): ZIO[Any, Throwable, _ <: ActorResult] = {
    event.getPayload match {
      case None        => ZIO.succeed(handleMessage(())).as(ActorResult.Continue())
      case Some(value) => ZIO.succeed(handleMessage(adapter.toScala(value))).as(ActorResult.Continue())
    }
  }

  def onTermination[PContext <: ProcessContext](reason: ETerm, ctx: PContext): UIO[Unit] = {
    ZIO.logError(s"${getClass.toString} did not define a onTermination function.")
    ZIO.succeed(())
  }

  override def handleInit()            = ()
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

  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"adapter=$adapter",
    s"runtime=$runtime",
    s"name=$name",
    s"self=$self",
    s"node=$node"
  )
}

trait ServiceContext[A <: Product] {
  def args: A
}

class PidSend(to: Pid, proc: Process) {
  def !(msg: Any): Unit = {
    proc.send(to, msg)
  }

  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"to=$to",
    s"proc=$proc"
  )
}

class SymSend(to: Symbol, proc: Process) {
  def !(msg: Any): Unit = {
    proc.send(to, msg)
  }

  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"to=$to",
    s"proc=$proc"
  )
}

class DestSend(to: (Symbol, Symbol), from: Pid, proc: Process) {
  def !(msg: Any): Unit = {
    proc.send(to, from, msg)
  }

  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"to=$to",
    s"from=$from",
    s"proc=$proc"
  )
}

class Service[A <: Product](ctx: ServiceContext[A])(implicit adapter: Adapter[_, _]) extends Process()(adapter) {
  def metricsRegistry      = adapter.node.metricsRegistry
  val metrics              = MetricsGroup(getClass, metricsRegistry)
  val PING_TIMEOUT_IN_MSEC = 3000

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
  def makeTag(ref: ERef) = EListImproper(EAtom("alias"), ref)

  override def onMessage[PContext <: ProcessContext](
    event: MessageEnvelope,
    ctx: PContext
  )(implicit trace: Trace): ZIO[Any, Throwable, _ <: ActorResult] = {
    event.getPayload match {
      case Some(ETuple(EAtom("ping"), from: EPid, ref: ERef)) => {
        val fromPid = Pid.toScala(from)
        sendZIO(fromPid, (Symbol("pong"), ref))
          .as(ActorResult.Continue())
      }
      case Some(
            ETuple(
              EAtom("$gen_call"),
              // Match on either
              // - {pid(), ref()}
              // - {pid(), [alias | ref()]}
              fromTag @ ETuple(from: EPid, _ref),
              EAtom("ping")
            )
          ) => {
        onHandlePingMessage(fromTag)
      }
      case Some(
            ETuple(
              EAtom("$gen_call"),
              // Match on either
              // - {pid(), ref()}
              // - {pid(), [alias | ref()]}
              fromTag @ ETuple(from: EPid, _ref),
              EAtom("metrics")
            )
          ) => {
        onHandleMetricsMessage(fromTag)
      }
      case Some(
            ETuple(
              EAtom("$gen_call"),
              // Match on either
              // - {pid(), ref()}
              // - {pid(), [alias | ref()]}
              fromTag @ ETuple(from: EPid, _ref),
              request: ETerm
            )
          ) => {
        onHandleCallMessage(fromTag, request)
      }
      case Some(ETuple(EAtom("$gen_cast"), request: ETerm)) => {
        try {
          ZIO
            .succeed(handleCast(adapter.toScala(request)))
            .as(ActorResult.Continue())
        } catch {
          case err: Throwable => {
            printThrowable("onMessage[$gen_cast]", err)
            ZIO.fail(HandleCastCBError(err))
          }
        }
      }
      case Some(ETuple(EAtom("DOWN"), ref: ERef, EAtom("process"), from, reason: ETerm)) =>
        try {
          ZIO
            .succeed(handleMonitorExit(monitoredToScala(from), Reference.toScala(ref), reason))
            .as(ActorResult.Continue())
        } catch {
          case err: Throwable => {
            printThrowable("onMessage[DOWN]", err)
            ZIO.fail(HandleCastCBError(err))
          }
        }
      case Some(info: ETerm) => {
        ZIO
          .attemptBlockingInterrupt(handleInfo(adapter.toScala(info)))
          .mapError(err => {
            printThrowable("onMessage[ETerm]", err)
            HandleInfoCBError(err)
          })
          .as(ActorResult.Continue())
      }
      case Some(info) => {
        ZIO.logError(s"nothing matched but it is not a ETerm $info")
        ZIO
          .attemptBlockingInterrupt(handleInfo(adapter.toScala(info)))
          .mapError(err => {
            printThrowable("onMessage[Any]", err)
            HandleInfoCBError(err)
          })
          .as(ActorResult.Continue())
      }
      case None => ZIO.fail(UnreachableError())
    }
  }

  def monitoredToScala(monitored: ETerm): Any = monitored match {
    case pid: EPid   => Pid.toScala(pid)
    case name: EAtom => adapter.toScala(name)
    case other       => throw new Throwable("unreachable")
  }

  def onHandlePingMessage(fromTag: ETerm) = {
    val callerTag: (Pid, Any) = fromTag match {
      case ETuple(from: EPid, EListImproper(EAtom("alias"), ref: ERef)) =>
        (Pid.toScala(from), List(Symbol("alias"), Reference.toScala(ref)))
      case ETuple(from: EPid, ref: ERef) => (Pid.toScala(from), Reference.toScala(ref))
      case _                             => throw new Throwable("unreachable")
    }
    Service.replyZIO(callerTag, Symbol("pong"))(this).as(ActorResult.Continue())
  }

  def onHandleMetricsMessage(fromTag: ETerm) = {
    val callerTag: (Pid, Any) = fromTag match {
      case ETuple(from: EPid, EListImproper(EAtom("alias"), ref: ERef)) =>
        (Pid.toScala(from), List(Symbol("alias"), Reference.toScala(ref)))
      case ETuple(from: EPid, ref: ERef) => (Pid.toScala(from), Reference.toScala(ref))
      case _                             => throw new Throwable("unreachable")
    }
    val replyTerm = (Symbol("ok"), metrics.dumpAsSymbolValuePairs())
    Service.replyZIO(callerTag, replyTerm)(this).as(ActorResult.Continue())
  }

  def onHandleCallMessage(fromTag: ETerm, request: ETerm)(implicit trace: Trace) = {
    val callerTag: (Pid, Any) = fromTag match {
      case ETuple(from: EPid, EListImproper(EAtom("alias"), ref: ERef)) =>
        (Pid.toScala(from), List(Symbol("alias"), Reference.toScala(ref)))
      case ETuple(from: EPid, ref: ERef) => (Pid.toScala(from), Reference.toScala(ref))
      case _                             => throw new Throwable("unreachable")
    }
    for {
      result <- ZIO
        .attemptBlockingInterrupt(handleCall(callerTag, adapter.toScala(request)))
        .mapError(err => {
          printThrowable("onMessage[$gen_call]", err)
          HandleCallCBError(err)
        })
      res <- result match {
        case (Symbol("reply"), replyTerm) =>
          Service.replyZIO(callerTag, replyTerm)(this).as(ActorResult.Continue())
        case Symbol("noreply") =>
          ZIO.succeed(ActorResult.Continue())
        case (Symbol("stop"), reason: String, replyTerm) =>
          Service.replyZIO(callerTag, replyTerm)(this).as(ActorResult.StopWithReasonString(reason))
        case (Symbol("stop"), reason: Any, replyTerm) =>
          Service.replyZIO(callerTag, replyTerm)(this).as(ActorResult.StopWithReasonTerm(adapter.fromScala(reason)))
        case replyTerm =>
          Service.replyZIO(callerTag, replyTerm)(this).as(ActorResult.Continue())
      }
    } yield res
  }

  def ping(to: Pid): Boolean                   = Service.ping(to)
  def ping(to: Pid, timeout: Long): Boolean    = Service.ping(to)
  def ping(to: Symbol): Boolean                = Service.ping(to)
  def ping(to: Symbol, timeout: Long): Boolean = Service.ping(to, timeout)

  def call(to: Pid, msg: Any): Any                                = Service.call(to, msg)
  def call(to: Pid, msg: Any, timeout: Long): Any                 = Service.call(to, msg, timeout)
  def call(to: Symbol, msg: Any): Any                             = Service.call(to, msg)
  def call(to: Symbol, msg: Any, timeout: Long): Any              = Service.call(to, msg, timeout)
  def call(to: (RegName, NodeName), msg: Any): Any                = Service.call(to, msg)
  def call(to: (RegName, NodeName), msg: Any, timeout: Long): Any = Service.call(to, msg, timeout)

  def cast(to: Pid, msg: Any)                 = Service.cast(to, msg)
  def cast(to: Symbol, msg: Any)              = Service.cast(to, msg)
  def cast(to: (RegName, NodeName), msg: Any) = Service.cast(to, msg)

  def printThrowable(location: String, err: Throwable) = {
    ZIO.logError(
      s"$location Throwable ${err.getMessage()}:\n" + err.getStackTrace().map(e => s"  ${e.toString()}").mkString("\n")
    )
  }

  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"ctx=$ctx",
    s"adapter=$adapter",
    s"metricsRegistry=$metricsRegistry",
    s"metrics=$metrics"
  )
}

object Service {
  val PING_TIMEOUT_IN_MSEC = 3000

  def call(to: core.Address, msg: Any)(implicit adapter: Adapter[_, _]): Any = {
    to match {
      case Name(name, _workerId, _workerNodeName)             => adapter.node.call(name.atom, msg)
      case NameOnNode(name, node, _workerId, _workerNodeName) => adapter.node.call((name.atom, node.atom), msg)
      case PID(pid, _workerId, _workerNodeName)               => adapter.node.call(pid, msg)
    }
  }
  def call(to: core.PID, msg: Any)(implicit adapter: Adapter[_, _]): Any  = adapter.node.call(to, msg)
  def call(to: core.Name, msg: Any)(implicit adapter: Adapter[_, _]): Any = adapter.node.call(to.name.atom, msg)
  def call(to: core.NameOnNode, msg: Any)(implicit adapter: Adapter[_, _]): Any = {
    adapter.node.call((to.name.atom, to.node.atom), msg)
  }
  def call(to: Pid, msg: Any)(implicit adapter: Adapter[_, _]): Any                = adapter.node.call(to, msg)
  def call(to: Pid, msg: Any, timeout: Long)(implicit adapter: Adapter[_, _]): Any = adapter.node.call(to, msg, timeout)
  def call(to: Symbol, msg: Any)(implicit adapter: Adapter[_, _]): Any             = adapter.node.call(to, msg)
  def call(to: Symbol, msg: Any, timeout: Long)(implicit adapter: Adapter[_, _]): Any = {
    adapter.node.call(to, msg, timeout)
  }
  def call(to: (ProcessLike.RegName, ProcessLike.NodeName), msg: Any)(implicit adapter: Adapter[_, _]): Any = {
    adapter.node.call(to, msg)
  }
  def call(to: (ProcessLike.RegName, ProcessLike.NodeName), msg: Any, timeout: Long)(implicit
    adapter: Adapter[_, _]
  ): Any = {
    adapter.node.call(to, msg, timeout)
  }

  def cast(to: core.PID, msg: Any)(implicit adapter: Adapter[_, _])  = adapter.node.cast(to, msg)
  def cast(to: core.Name, msg: Any)(implicit adapter: Adapter[_, _]) = adapter.node.cast(to.name.atom, msg)
  def cast(to: core.NameOnNode, msg: Any)(implicit adapter: Adapter[_, _]) = {
    adapter.node.cast((to.name.atom, to.node.atom), msg)
  }
  def cast(to: Pid, msg: Any)(implicit adapter: Adapter[_, _])    = adapter.node.cast(to, msg)
  def cast(to: Symbol, msg: Any)(implicit adapter: Adapter[_, _]) = adapter.node.cast(to, msg)
  def cast(to: (ProcessLike.RegName, ProcessLike.NodeName), msg: Any)(implicit adapter: Adapter[_, _]) = {
    adapter.node.cast(to, msg)
  }

  def ping(to: core.Address)(implicit adapter: Adapter[_, _]): Any = {
    to match {
      case name: Name       => ping(name)
      case name: NameOnNode => ping(name)
      case pid: PID         => ping(pid)
    }
  }

  def ping(to: core.PID)(implicit adapter: Adapter[_, _]): Boolean = {
    adapter.node.call(Pid.toScala(to.pid), ETuple(EAtom("ping")), PING_TIMEOUT_IN_MSEC) == ETuple(EAtom("pong"))
  }
  def ping(to: core.Name)(implicit adapter: Adapter[_, _]): Boolean = {
    adapter.node.call(to.name.atom, ETuple(EAtom("ping")), PING_TIMEOUT_IN_MSEC) == ETuple(EAtom("pong"))
  }
  def ping(to: core.NameOnNode)(implicit adapter: Adapter[_, _]): Boolean = {
    adapter.node.call((to.name.atom, to.node.atom), ETuple(EAtom("ping")), PING_TIMEOUT_IN_MSEC) == ETuple(
      EAtom("pong")
    )
  }
  def ping(to: Pid)(implicit adapter: Adapter[_, _]): Boolean = {
    adapter.node.call(to, ETuple(EAtom("ping")), PING_TIMEOUT_IN_MSEC) == ETuple(EAtom("pong"))
  }
  def ping(to: Pid, timeout: Long)(implicit adapter: Adapter[_, _]): Boolean = {
    adapter.node.call(to, ETuple(EAtom("ping")), timeout) == ETuple(EAtom("pong"))
  }
  def ping(to: Symbol)(implicit adapter: Adapter[_, _]): Boolean = {
    adapter.node.call(to, ETuple(EAtom("ping")), PING_TIMEOUT_IN_MSEC) == ETuple(EAtom("pong"))
  }
  def ping(to: Symbol, timeout: Long)(implicit adapter: Adapter[_, _]): Boolean = {
    adapter.node.call(to, ETuple(EAtom("ping")), timeout) == ETuple(EAtom("pong"))
  }

  def replyZIO[P <: Process](caller: (Pid, Any), reply: Any)(implicit process: P): UIO[Unit] = {
    val (from, replyRef) = caller match {
      case (from: Pid, List(Symbol("alias"), ref: Reference)) =>
        (from.fromScala, EListImproper(EAtom("alias"), ref.fromScala))
      case (from: Pid, ref: Reference) =>
        (from.fromScala, ref.fromScala)
      case _ =>
        throw new Throwable("unreachable")
    }
    val adapter  = process.adapter
    val address  = Address.fromPid(from, adapter.workerId, adapter.workerNodeName)
    val envelope = MessageEnvelope.makeSend(address, adapter.fromScala((replyRef, reply)), adapter.self)
    adapter.send(envelope)
  }

  def reply[P <: Process](caller: (Pid, Any), reply: Any)(implicit process: P): Unit = {
    val (pid, ref) = caller
    process.send(pid, (ref, reply))
  }
}
