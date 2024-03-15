package com.cloudant.ziose.clouseau

/*
❯ erl -setcookie cookie -name node1@127.0.0.1
Erlang/OTP 25 [erts-13.2.2.6] [source] [64-bit] [smp:10:10] [ds:10:10:10] [async-threads:1] [jit:ns]

Eshell V13.2.2.6  (abort with ^G)
(node1@127.0.0.1)1>
(node1@127.0.0.1)1>  gen_server:call({coordinator, 'clouseau1@127.0.0.1'}, version).
<<"3.0.0">>
(node1@127.0.0.1)2>
 */

import _root_.com.cloudant.ziose.scalang
import scalang.{Adapter, Pid, SNode, Service, ServiceContext}
import _root_.com.cloudant.ziose.core
import core.Codec
import Codec.{EPid, EBinary}
import core.ActorBuilder.State
import core.{ActorBuilder, ActorConstructor, ProcessContext}
import core.BuildInfo

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.collection.immutable.HashMap

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
      case (Symbol("version")) => (Symbol("reply"), EBinary(BuildInfo.version.getBytes))
      case (Symbol("build_info")) =>
        (
          Symbol("reply"),
          HashMap(
            Symbol("clouseau") -> BuildInfo.version,
            Symbol("scala")    -> BuildInfo.scalaVersion,
            Symbol("sbt")      -> BuildInfo.sbtVersion
          )
        )
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
      new EchoService(service_context)(Adapter(process_context, node, ClouseauTypeFactory))
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
  )(implicit
    adapter: Adapter[_, _]
  ): Any = {
    val ctx: ServiceContext[ConfigurationArgs] = {
      new ServiceContext[ConfigurationArgs] {
        val args: ConfigurationArgs = ConfigurationArgs(config)
      }
    }
    node.spawnService[EchoService, ConfigurationArgs](make(node, ctx, name)) match {
      case core.Success(actor)  => (Symbol("ok"), Pid.toScala(actor.self.pid))
      case core.Failure(reason) => reason
    }
  }
}
