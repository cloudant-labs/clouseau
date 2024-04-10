package com.cloudant.ziose.clouseau

/*
❯ erl -setcookie cookie -name node1@127.0.0.1
Erlang/OTP 25 [erts-13.2.2.6] [source] [64-bit] [smp:10:10] [ds:10:10:10] [async-threads:1] [jit:ns]

Eshell V13.2.2.6  (abort with ^G)
(node1@127.0.0.1)1>
(node1@127.0.0.1)1>  gen_server:call({init, 'clouseau1@127.0.0.1'}, version).
<<"3.0.0">>
(node1@127.0.0.1)2>
 */

import _root_.com.cloudant.ziose.scalang
import scalang.{Adapter, Pid, SNode, Service, ServiceContext}
import _root_.com.cloudant.ziose.core
import core.Codec
import Codec.EBinary
import core.ActorBuilder.State
import core.{ActorBuilder, ActorConstructor, ProcessContext}
import core.BuildInfo

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.collection.immutable.HashMap

class InitService(ctx: ServiceContext[ConfigurationArgs])(implicit adapter: Adapter[_, _]) extends Service(ctx) {
  println("[Init] Created")

  private def spawnEcho(id: Symbol): Either[Any, Codec.EPid] = {
    val ConfigurationArgs(args) = ctx.args
    EchoService.start(adapter.node, id.name, args) match {
      case (Symbol("ok"), pidUntyped) => Right(pidUntyped.asInstanceOf[Pid].fromScala)
      case reason                     => Left(reason)
    }
  }

  override def handleInfo(request: Any): Any = {
    request match {
      case Symbol("shutdown") =>
        println("[Init] Stopping")
        exit("shutdown")
      case (Symbol("spawn"), from: Codec.EPid, Symbol("echo"), id: Symbol) =>
        // This is a simplistic solution to make it possible for the
        // requestor to get the context of the response without
        // requiring an Erlang ref or monitor.  It is meant to be used
        // for testing only, not suitable for production code.
        val response = spawnEcho(id) match {
          case Right(pid)   => (Symbol("spawned"), id, pid)
          case Left(reason) => (Symbol("spawn_error"), id, reason)
        }
        send(from, response)
      case msg =>
        println(s"[Init][WARNING][handleInfo] Unexpected message: $msg ...")
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
      case (Symbol("spawn"), Symbol("echo"), id: Symbol) =>
        val result = spawnEcho(id) match {
          case Right(pid)   => (Symbol("ok"), pid)
          case Left(reason) => (Symbol("error"), reason)
        }
        (Symbol("reply"), result)
      case msg =>
        println(s"[Init][WARNING][handleCall] Unexpected message: $msg ...")
    }
  }

  private def now(): BigInt = ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now())
}

private object InitService extends ActorConstructor[InitService] {
  private def make(
    node: SNode,
    service_context: ServiceContext[ConfigurationArgs],
    name: String
  ): ActorBuilder.Builder[InitService, State.Spawnable] = {
    def maker[PContext <: ProcessContext](process_context: PContext): InitService = {
      new InitService(service_context)(Adapter(process_context, node, ClouseauTypeFactory))
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
    node.spawnService[InitService, ConfigurationArgs](make(node, ctx, name)) match {
      case core.Success(actor) =>
        println(s"[Init] Started $name")
        (Symbol("ok"), Pid.toScala(actor.self.pid))
      case core.Failure(reason) => reason
    }
  }
}
