package com.cloudant.ziose.clouseau

import zio.{&, ZIO}

import _root_.com.cloudant.ziose.scalang
import scalang.{Adapter, Pid, SNode, Service, ServiceContext}
import _root_.com.cloudant.ziose
import com.cloudant.ziose.core.Codec
import Codec.{EPid, EBinary}
import ziose.core.ActorBuilder.State
import ziose.core.{ActorBuilder, ActorConstructor, ActorFactory, AddressableActor, EngineWorker, Node, ProcessContext}

import java.time.Instant
import java.time.temporal.ChronoUnit
import zio.FiberFailure

class EchoService(ctx: ServiceContext[ConfigurationArgs])(implicit adapter: Adapter[_, _]) extends Service(ctx) {
  override def handleInfo(request: Any): Any = {
    request match {
      case (Symbol("echo"), from: EPid, ts: BigInt, seq: BigInt) =>
        val reply = (Symbol("echo_reply"), from, ts, self.pid, now(), seq)
        send(from, reply)
      case msg =>
        println(s"[WARNING] Unexpected message: $msg ...")
    }
  }

  override def handleCall(tag: (Pid, Any), request: Any): Any = {
    request match {
      case (Symbol("version"))       => (Symbol("reply"), EBinary("0.1.0".getBytes))
      case (Symbol("echo"), request) => (Symbol("reply"), (Symbol("echo"), Codec.fromScala(request)))
    }
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
      new EchoService(service_context)(new Adapter(process_context, node, ClouseauTypeFactory))
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
  ): ZIO[EngineWorker & Node & ActorFactory, Throwable, AddressableActor[_, _]] = {
    val ctx: ServiceContext[ConfigurationArgs] = {
      new ServiceContext[ConfigurationArgs] {
        val args: ConfigurationArgs = ConfigurationArgs(config)
      }
    }
    node.spawnServiceZIO[EchoService, ConfigurationArgs](make(node, ctx, name))
  }
}
