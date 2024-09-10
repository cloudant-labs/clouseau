package com.cloudant.ziose.clouseau

import com.cloudant.ziose.macros.checkEnv
import com.cloudant.ziose.{core, scalang}
import core.ActorBuilder.State
import core.{ActorBuilder, ActorConstructor, ProcessContext}
import scalang.{Adapter, Pid, SNode, Service, ServiceContext}

import java.time.Instant
import java.time.temporal.ChronoUnit
import zio._

class EchoService(ctx: ServiceContext[ConfigurationArgs])(implicit adapter: Adapter[_, _]) extends Service(ctx) {
  val logger = LoggerFactory.getLogger("clouseau.EchoService")
  logger.debug("Created")

  val echoTimer: metrics.Timer = metrics.timer("echo.response_time")

  override def handleInfo(request: Any): Any = {
    request match {
      case (Symbol("echo"), from: Pid, ts: BigInt, seq: Int) =>
        val reply = echoTimer.time(
          (Symbol("echo_reply"), from, ts, self.pid, now(), seq)
        )
        send(from, reply)
      case msg =>
        logger.info(s"[WARNING][handleInfo] Unexpected message: $msg ...")
    }
  }

  override def handleCall(tag: (Pid, Any), request: Any): Any = {
    request match {
      case (Symbol("echo"), request) => (Symbol("reply"), (Symbol("echo"), adapter.fromScala(request)))
      case (Symbol("crashWithReason"), reason: String) => throw new Throwable(reason)
      case (Symbol("stop"), reason: Symbol) =>
        (Symbol("stop"), reason, adapter.fromScala(request))
      case msg =>
        logger.info(s"[WARNING][handleCall] Unexpected message: $msg ...")
    }
  }
  override def onTermination[PContext <: ProcessContext](reason: core.Codec.ETerm, ctx: PContext) = {
    ZIO.logTrace("onTermination")
  }

  private def now(): BigInt = ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now())

  @checkEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"adapter=$adapter"
  )
}

private object EchoService extends ActorConstructor[EchoService] {
  val logger = LoggerFactory.getLogger("clouseau.EchoServiceBuilder")

  private def make(
    node: SNode,
    service_context: ServiceContext[ConfigurationArgs],
    name: String
  ): ActorBuilder.Builder[EchoService, State.Spawnable] = {
    def maker[PContext <: ProcessContext](process_context: PContext): EchoService = {
      new EchoService(service_context)(Adapter(process_context, node, ClouseauTypeFactory))
    }

    ActorBuilder()
      // TODO get capacity from config
      .withCapacity(16)
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
    node.spawnService[EchoService, ConfigurationArgs](make(node, ctx, name)) match {
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
    node.spawnServiceZIO[EchoService, ConfigurationArgs](make(node, ctx, name))
  }

}
