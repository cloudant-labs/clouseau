package com.cloudant.ziose.clouseau

import com.cloudant.ziose.macros.CheckEnv
import com.cloudant.ziose.{core, scalang}
import core.ActorBuilder.State
import core.{ActorBuilder, ActorConstructor, ProcessContext}
import scalang.{Adapter, Pid, SNode, Service, ServiceContext}

import java.time.Instant
import java.time.temporal.ChronoUnit
import zio._

class TestEchoService(ctx: ServiceContext[ConfigurationArgs])(implicit adapter: Adapter[_, _]) extends Service(ctx) {
  val isTest                                    = true
  var throwFromTerminate: Option[(Pid, String)] = None
  var exitFromTerminate: Option[(Pid, Any)]     = None

  val logger = LoggerFactory.getLogger("clouseau.EchoService")
  logger.debug("Created")

  val echoTimer: metrics.Timer = metrics.timer("echo.response_time")

  override def handleInit(): Unit = {
    adapter.setTag(name.get)
  }

  override def handleInfo(request: Any): Any = {
    request match {
      case (Symbol("echo"), from: Pid, ts: Long, seq) =>
        val reply = echoTimer.time(
          (Symbol("echo_reply"), from, ts, self.pid, now(), seq)
        )
        send(from, reply)
      case (Symbol("block_for_ms"), durationInMs: Int) =>
        logger.warn(s"Blocking the actor loop for ${durationInMs} ms")
        Thread.sleep(durationInMs)
        logger.warn(s"Blocking the actor loop is over")
      case (Symbol("exitWithReason"), reason: String) =>
        exit(reason)
      case (Symbol("exitWithReason"), reason: Any) =>
        exit(reason)
      case msg =>
        logger.warn(s"[handleInfo] Unexpected message: $msg ...")
    }
  }

  override def handleCall(tag: (Pid, Any), request: Any): Any = {
    request match {
      case (Symbol("echo"), request) => (Symbol("reply"), (Symbol("echo"), adapter.fromScala(request)))
      case (Symbol("crashWithReason"), reason: String) => throw new Throwable(reason)
      case (Symbol("stop"), reason: Symbol) =>
        (Symbol("stop"), reason, adapter.fromScala(request))
      case (Symbol("exitWithReason"), reason: String) =>
        exit(reason)
      case (Symbol("exitWithReason"), reason: Any) =>
        exit(reason)
      case (Symbol("setThrowFromTerminate"), reason: String) =>
        throwFromTerminate = Some((tag._1, reason))
        (Symbol("reply"), Symbol("ok"))
      case (Symbol("setExitFromTerminate"), reason) =>
        exitFromTerminate = Some((tag._1, reason))
        (Symbol("reply"), Symbol("ok"))
      case (Symbol("call"), address: Pid, req) =>
        (Symbol("reply"), (Symbol("ok"), call(address, req)))
      case (Symbol("call"), name: Symbol, req) =>
        (Symbol("reply"), (Symbol("ok"), call(name, req)))
      case (Symbol("cast"), address: Pid, req) =>
        cast(address, req)
        (Symbol("reply"), Symbol("ok"))
      case (Symbol("cast"), name: Symbol, req) =>
        cast(name, req)
        (Symbol("reply"), Symbol("ok"))
      case msg =>
        logger.warn(s"[handleCall] Unexpected message: $msg ...")
    }
  }

  override def handleCast(request: Any): Any = {
    request match {
      case (Symbol("echo"), from: Pid, request) =>
        from ! (Symbol("echo"), adapter.fromScala(request))
        ()
      case msg =>
        logger.warn(s"[handleCast] Unexpected message: $msg ...")
    }
  }
  override def onTermination[PContext <: ProcessContext](reason: core.Codec.ETerm, ctx: PContext) = {
    ZIO.logTrace(s"onTermination ${ctx.name}: ${reason.getClass.getSimpleName} -> ${reason}") *>
      ZIO.succeed(exitFromTerminate.map(term => {
        val (from: Pid, reason) = term
        from ! reason
        // pause to let the message arrive to the caller
        Thread.sleep(500)
        core.ActorResult.exit(core.Codec.fromScala(reason))
      })) *>
      ZIO.succeed(throwFromTerminate.map(term => {
        val (from: Pid, reason) = term
        from ! reason
        // pause to let the message arrive to the caller
        Thread.sleep(500)
        throw new Throwable(reason)
      }))
  }

  private def now(): BigInt = ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now())

  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"adapter=$adapter"
  )
}

private object EchoService extends ActorConstructor[TestEchoService] {
  val logger = LoggerFactory.getLogger("clouseau.EchoServiceBuilder")

  private def make(
    node: SNode,
    service_context: ServiceContext[ConfigurationArgs],
    name: String
  ): ActorBuilder.Builder[TestEchoService, State.Spawnable] = {
    def maker[PContext <: ProcessContext](process_context: PContext): TestEchoService = {
      new TestEchoService(service_context)(Adapter(process_context, node, ClouseauTypeFactory))
    }

    ActorBuilder()
      .withName(name)
      .withMaker(maker)
      .build(this)
  }

  def start(node: SNode, name: String, config: Configuration)(implicit adapter: Adapter[_, _]): Any = {
    val ctx: ServiceContext[ConfigurationArgs] = {
      new ServiceContext[ConfigurationArgs] {
        val args: ConfigurationArgs = ConfigurationArgs(config)
      }
    }
    node.spawnService[TestEchoService, ConfigurationArgs](make(node, ctx, name)) match {
      case core.Success(actor) =>
        logger.debug(s"Started $name")
        (Symbol("ok"), Pid.toScala(actor.self.pid))
      case core.Failure(reason) => reason
    }
  }

  def startZIO(
    node: SNode,
    name: String,
    config: Configuration
  ): ZIO[core.EngineWorker & core.Node & core.ActorFactory, core.Node.Error, core.AddressableActor[_, _]] = {
    val ctx: ServiceContext[ConfigurationArgs] = {
      new ServiceContext[ConfigurationArgs] {
        val args: ConfigurationArgs = ConfigurationArgs(config)
      }
    }
    node.spawnServiceZIO[TestEchoService, ConfigurationArgs](make(node, ctx, name))
  }

}
