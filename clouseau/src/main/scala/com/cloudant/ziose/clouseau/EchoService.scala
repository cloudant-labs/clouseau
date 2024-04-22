package com.cloudant.ziose.clouseau

import _root_.com.cloudant.ziose.scalang
import scalang.{Adapter, Pid, SNode, Service, ServiceContext}
import _root_.com.cloudant.ziose.core
import core.Codec
import Codec.EPid
import core.ActorBuilder.State
import core.{ActorBuilder, ActorConstructor, ProcessContext}

import java.time.Instant
import java.time.temporal.ChronoUnit

class EchoService(ctx: ServiceContext[ConfigurationArgs])(implicit adapter: Adapter[_, _]) extends Service(ctx) {
  val logger = LoggerFactory.getLogger("clouseau.EchoService")
  logger.debug("[Echo] Created")

  val echoTimer: metrics.Timer = metrics.timer("echo.response_time")

  override def handleInfo(request: Any): Any = {
    request match {
      case (Symbol("echo"), from: EPid, ts: BigInt, seq: BigInt) =>
        val reply = echoTimer.time(
          (Symbol("echo_reply"), from, ts, self.pid, now(), seq)
        )
        send(from, reply)
      case msg =>
        logger.info(s"[Echo][WARNING][handleInfo] Unexpected message: $msg ...")
    }
  }

  override def handleCall(tag: (Pid, Any), request: Any): Any = {
    request match {
      case (Symbol("echo"), request) => (Symbol("reply"), (Symbol("echo"), Codec.fromScala(request)))
      case msg =>
        logger.info(s"[Echo][WARNING][handleCall] Unexpected message: $msg ...")
    }
  }

  private def now(): BigInt = ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now())
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
        logger.debug(s"[Echo] Started $name")
        (Symbol("ok"), Pid.toScala(actor.self.pid))
      case core.Failure(reason) => reason
    }
  }
}
