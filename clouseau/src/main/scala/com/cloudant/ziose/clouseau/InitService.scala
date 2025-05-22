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

import com.cloudant.ziose.macros.CheckEnv
import com.cloudant.ziose.{core, scalang}
import core.ActorBuilder.State
import core.{ActorBuilder, ActorConstructor, BuildInfo, Codec, ProcessContext}
import scalang.{Adapter, Pid, SNode, Service, ServiceContext}

import java.nio.charset.StandardCharsets
import scala.collection.immutable.HashMap

class InitService(ctx: ServiceContext[ConfigurationArgs])(implicit adapter: Adapter[_, _]) extends Service(ctx) {
  val logger = LoggerFactory.getLogger("clouseau.InitService")
  logger.debug("Created")

  private val spawnedSuccess = metrics.counter("spawned.success")
  private val spawnedFailure = metrics.counter("spawned.failure")
  private val spawnedTimer   = metrics.timer("spawned.timer")

  override def handleInit(): Unit = {
    logger.debug(s"handleInit(capacity = ${adapter.capacity})")
  }

  private def spawnEcho(id: Symbol): Either[Any, Codec.EPid] = {
    val ConfigurationArgs(args) = ctx.args
    spawnedTimer.time(EchoService.start(adapter.node, id.name, args)) match {
      case (Symbol("ok"), pidUntyped) => {
        spawnedSuccess += 1
        Right(pidUntyped.asInstanceOf[Pid].fromScala)
      }
      case reason: core.Node.Error => {
        spawnedFailure += 1
        Left(reason)
      }
    }
  }

  override def handleInfo(request: Any): Any = {
    request match {
      case Symbol("shutdown") =>
        logger.debug("Stopping")
        exit("shutdown")
      case (Symbol("spawn"), from: Pid, Symbol("echo"), id: Symbol) =>
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
        logger.info(s"[WARNING][handleInfo] Unexpected message: $msg ...")
    }
  }

  override def handleCall(tag: (Pid, Any), request: Any): Any = {
    request match {
      case (Symbol("version")) => (Symbol("reply"), Codec.EBinary(BuildInfo.version.getBytes(StandardCharsets.UTF_8)))
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
        logger.info(s"[WARNING][handleCall] Unexpected message: $msg ...")
    }
  }

  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"ctx=$ctx",
    s"adapter=$adapter"
  )
}

private object InitService extends ActorConstructor[InitService] {
  val logger = LoggerFactory.getLogger("clouseau.InitServiceBuilder")

  private def make(
    node: SNode,
    service_context: ServiceContext[ConfigurationArgs],
    name: String
  ): ActorBuilder.Builder[InitService, State.Spawnable] = {
    def maker[PContext <: ProcessContext](process_context: PContext): InitService = {
      new InitService(service_context)(Adapter(process_context, node, ClouseauTypeFactory))
    }

    val capacityExponent = service_context.args.config.capacity.init_exponent

    ActorBuilder()
      .withOptionalCapacityExponent(capacityExponent)
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
        logger.debug(s"Started $name")
        (Symbol("ok"), Pid.toScala(actor.self.pid))
      case core.Failure(reason) => reason
    }
  }
}
