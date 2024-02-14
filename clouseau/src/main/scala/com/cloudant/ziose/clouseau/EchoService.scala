package com.cloudant.ziose.clouseau

import com.cloudant.ziose.core.ActorBuilder.State
import com.cloudant.ziose.core.{
  ActorBuilder,
  ActorConstructor,
  ActorFactory,
  AddressableActor,
  Codec,
  EngineWorker,
  Node,
  ProcessContext
}
import com.cloudant.ziose.scalang.{Adapter, Pid, Service, ServiceContext, Node => SNode}
import zio.{&, RIO}

import java.time.Instant
import java.time.temporal.ChronoUnit

class EchoService(ctx: ServiceContext[ConfigurationArgs])(implicit adapter: Adapter[_]) extends Service(ctx) {
  override def handleInfo(request: Any): Any = {
    request match {
      case (Symbol("echo"), from: Codec.EPid, ts: BigInt, seq: BigInt) =>
        val reply = (Symbol("echo_reply"), from, ts, self.pid, now(), seq)
        send(from, reply)
      case msg =>
        println(s"[WARNING] Unexpected message: $msg ...")
    }
  }

  override def handleCall(tag: (Pid, Any), request: Any): Any = {
    (Symbol("echo"), request)
  }

  private def now(): BigInt = ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now())
}

private object EchoService extends ActorConstructor[EchoService] {
  private def make(
    node: SNode,
    service_context: ServiceContext[ConfigurationArgs],
    name: String
  ): ActorBuilder.Builder[EchoService, State.Spawnable] = {
    def maker[PContext <: ProcessContext](process_context: PContext): EchoService = {
      new EchoService(service_context)(Adapter(process_context, node))
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
    name: String,
    config: Configuration
  ): RIO[EngineWorker & Node & ActorFactory, AddressableActor[_, _]] = {
    val ctx: ServiceContext[ConfigurationArgs] = {
      new ServiceContext[ConfigurationArgs] {
        val args: ConfigurationArgs = ConfigurationArgs(config)
      }
    }

    node.spawnServiceZIO[EchoService, ConfigurationArgs](make(node, ctx, name))
  }
}
