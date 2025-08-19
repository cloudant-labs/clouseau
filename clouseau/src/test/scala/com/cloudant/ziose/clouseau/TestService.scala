package com.cloudant.ziose.clouseau

import com.cloudant.ziose.macros.CheckEnv
import com.cloudant.ziose.{core, scalang}
import core.ActorBuilder.State
import core.{ActorBuilder, ActorConstructor, Codec, ProcessContext}
import scalang.{Adapter, Pid, SNode, Service, ServiceContext}

import java.time.Instant
import java.time.temporal.ChronoUnit
import zio._
import java.util.concurrent.TimeoutException

case class TestServiceArgs(terminate: Queue[Unit])

case class TestServiceHandle(
  actor: core.AddressableActor[TestService, _],
  terminate: Queue[Unit]
) {
  val TERMINATION_TIMEOUT                                        = 2.seconds
  val self                                                       = actor.self
  val id                                                         = actor.id
  val ctx                                                        = actor.ctx
  def doTestCall(payload: Codec.ETerm)                           = actor.doTestCall(payload)
  def doTestCallTimeout(payload: Codec.ETerm, timeout: Duration) = actor.doTestCallTimeout(payload, timeout)
  def sendTestCall(payload: Codec.ETerm)                         = actor.sendTestCall(payload)
  def exit(reason: Codec.ETerm): UIO[Unit]                       = actor.exit(reason)
  def stopWithReason(reason: Any): ZIO[core.Node & core.EngineWorker, Throwable, Unit] = {
    actor.sendTestCall(Codec.fromScala((Symbol("stop"), reason))) *>
      terminate.take
        .timeout(TERMINATION_TIMEOUT)
        .someOrFail(new TimeoutException(s"stopWithReason($reason) for $id timed out"))
        .unit
  }

  def crashWithReason(reason: String): ZIO[core.Node & core.EngineWorker, Throwable, Unit] = {
    actor.sendTestCall(Codec.fromScala((Symbol("crashWithReason"), reason))) *>
      terminate.take
        .timeout(TERMINATION_TIMEOUT)
        .someOrFail(new TimeoutException(s"crashWithReason($reason) for $id timed out"))
        .unit
  }

  def exitWithReason(reason: String): ZIO[core.Node & core.EngineWorker, Throwable, Unit] = {
    actor.exit(Codec.fromScala(reason)) *>
      terminate.take
        .timeout(TERMINATION_TIMEOUT)
        .someOrFail(new TimeoutException(s"exitWithReason($reason) for $id timed out"))
        .unit
  }

  def history = actor
    .doTestCallTimeout(Codec.EAtom("history"), 3.seconds)
    .delay(100.millis)
    .repeatUntil(_.isSuccess)
    .map(result => Codec.toScala(result.payload.get).asInstanceOf[List[Any]])
    .timeout(3.seconds)
    .someOrFail(new TimeoutException(s"Getting history for $id timed out"))
}

class TestService(ctx: ServiceContext[TestServiceArgs])(implicit adapter: Adapter[_, _]) extends Service(ctx) {
  val logger = LoggerFactory.getLogger("clouseau.TestService")

  val echoTimer: metrics.Timer                = metrics.timer("echo.response_time")
  var calledArgs: List[Product2[String, Any]] = List()

  override def handleInfo(request: Any): Any = {
    calledArgs = ("handleInfo", request) :: calledArgs
    request match {
      case (Symbol("echo"), from: Pid, ts: BigInt, seq: Long) =>
        val reply = echoTimer.time(
          (Symbol("echo_reply"), from, ts, self.pid, now(), seq)
        )
        send(from, reply)
      case msg =>
        logger.warn(s"Unexpected message: $msg ...")
    }
  }

  override def handleCall(tag: (Pid, Any), request: Any): Any = {
    calledArgs = ("handleCall", request) :: calledArgs
    request match {
      case (Symbol("echo"), request) =>
        (Symbol("reply"), (Symbol("echo"), adapter.fromScala(request)))
      case (Symbol("crashWithReason"), reason: String) =>
        throw new Throwable(reason)
      case (Symbol("crashLogger.debug"), reason: String) =>
        logger.debug(throw new Throwable(reason))
        (Symbol("reply"), Symbol("debug"))
      case (Symbol("crashLogger.info"), reason: String) =>
        logger.debug(throw new Throwable(reason))
        (Symbol("reply"), Symbol("info"))
      case (Symbol("crashLogger.warn"), reason: String) =>
        logger.debug(throw new Throwable(reason))
        (Symbol("reply"), Symbol("warn"))
      case (Symbol("crashLogger.error"), reason: String) =>
        logger.debug(throw new Throwable(reason))
        (Symbol("reply"), Symbol("error"))
      case (Symbol("stop"), reason: Any) =>
        (Symbol("stop"), reason, adapter.fromScala(request))
      case Symbol("history") =>
        // remove calls to history from the result
        (Symbol("reply"), calledArgs.filterNot(_._2 == Symbol("history")))
      case (Symbol("exitWithReason"), reason: String) =>
        exit(reason)
      case (Symbol("exitWithReason"), reason: Any) =>
        exit(reason)
      case msg =>
        logger.warn(s"Unexpected message: $msg ...")
    }
  }
  override def onTermination[PContext <: ProcessContext](reason: Codec.ETerm, _ctx: PContext) = {
    val reasonId = reason.getClass.getSimpleName
    reason match {
      case binary: Codec.EBinary =>
        ZIO.logTrace(
          s"[${name}]onTermination: ${reasonId} -> ${binary.asString}"
        ) *> ctx.args.terminate
          .offer(())
          .unit
      case _ =>
        ZIO.logTrace(
          s"[${name}]onTermination: ${reasonId} -> ${reason}"
        ) *> ctx.args.terminate
          .offer(())
          .unit
    }
  }

  private def now(): BigInt = ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now())

  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"adapter=$adapter"
  )
}

private object TestService extends ActorConstructor[TestService] {
  val TIMEOUT = 2.seconds
  val logger  = LoggerFactory.getLogger("clouseau.TestServiceBuilder")

  private def make(
    node: SNode,
    service_context: ServiceContext[TestServiceArgs],
    name: String
  ): ActorBuilder.Builder[TestService, State.Spawnable] = {
    def maker[PContext <: ProcessContext](process_context: PContext): TestService = {
      new TestService(service_context)(Adapter(process_context, node, ClouseauTypeFactory))
    }

    ActorBuilder()
      // TODO get capacity from config
      .withCapacity(16)
      .withName(name)
      .withMaker(maker)
      .build(this)
  }

  def start(
    node: SNode,
    name: String
  ): ZIO[core.EngineWorker & core.Node & core.ActorFactory, core.Node.Error, TestServiceHandle] = {
    def ctx(channel: Queue[Unit]): ServiceContext[TestServiceArgs] = {
      new ServiceContext[TestServiceArgs] { val args: TestServiceArgs = TestServiceArgs(channel) }
    }
    for {
      terminateChannel <- Queue.bounded[Unit](1)
      actor            <- node.spawnServiceZIO[TestService, TestServiceArgs](make(node, ctx(terminateChannel), name))
    } yield TestServiceHandle(actor.asInstanceOf[core.AddressableActor[TestService, _]], terminateChannel)
  }

}
