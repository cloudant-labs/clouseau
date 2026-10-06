// Licensed under the Apache License, Version 2.0 (the "License"); you may not
// use this file except in compliance with the License. You may obtain a copy of
// the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations under
// the License.

package com.cloudant.ziose.clouseau

import zio._

import com.cloudant.ziose.scalang.{Service, ServiceContext}
import com.cloudant.ziose.core.ProcessContext
import com.cloudant.ziose.core.Node
import com.cloudant.ziose.scalang.Reference
import com.cloudant.ziose.core.ActorConstructor
import com.cloudant.ziose.core.ActorBuilder
import com.cloudant.ziose.scalang.SNode
import com.cloudant.ziose.scalang.Adapter
import com.cloudant.ziose.core.Actor
import com.cloudant.ziose.core.EngineWorker
import com.cloudant.ziose.core.AddressableActor
import com.cloudant.ziose.core.ActorFactory
import com.cloudant.ziose.scalang.Pid
import com.cloudant.ziose.core.ActorResult
import com.cloudant.ziose.core.Codec

case class ClouseauSupervisor(
    ctx: ServiceContext[ConfigurationArgs],
    var manager: Option[Pid] = None,
    var cleanup: Option[Pid] = None,
    var analyzer: Option[Pid] = None,
    var init: Option[Pid] = None,
    var rex: Option[Pid] = None
  )(implicit adapter: Adapter[_, _])
    extends Service(ctx) {
  val TERMINATION_TIMEOUT = Duration.fromSeconds(3)
  val logger = LoggerFactory.getLogger("clouseau.supervisor")

  override def onInit[P <: ProcessContext](_ctx: P): ZIO[Any, Throwable, _ <: ActorResult] = {
    val cnode   = adapter.node.asInstanceOf[ClouseauNode]
    val noneCtx = new ServiceContext[None.type] { val args = None }
    (for {
      _ <- spawnMonitorZIO[IndexManagerService, ConfigurationArgs](
        cnode, Symbol("main"), IndexManagerServiceBuilder.make(cnode, ctx)
      )
      _ <- spawnMonitorZIO[IndexCleanupService, ConfigurationArgs](
        cnode, Symbol("cleanup"), IndexCleanupServiceBuilder.make(cnode, ctx)
      )
      _ <- spawnMonitorZIO[AnalyzerService, ConfigurationArgs](
        cnode, Symbol("analyzer"), AnalyzerServiceBuilder.make(cnode, ctx)
      )
      _ <- spawnMonitorZIO[InitService, ConfigurationArgs](
        cnode, Symbol("init"), InitService.make(cnode, ctx, "init")
      )
      _ <- spawnMonitorZIO[RexService, None.type](
        cnode, Symbol("rex"), RexService.make(cnode, noneCtx)
      )
    } yield ActorResult.Continue()).provideEnvironment(cnode.runtime.environment)
  }

  private def spawnMonitorZIO[TS <: Service[A] with Actor: Tag, A <: Product](
    cnode: ClouseauNode,
    regName: Symbol,
    builder: ActorBuilder.Sealed[TS]
  ): ZIO[Node with EngineWorker, Throwable, Unit] =
    for {
      actor <- cnode.spawnServiceZIO[TS, A](builder).mapError(e => new Throwable(s"cannot start ${regName.name}: $e"))
      pid = Pid.toScala(actor.self.pid)
      _ <- ZIO.succeed(logger.debug(s"${regName.name} is started"))
      _ <- monitorZIO(pid).mapError(e => new Throwable(s"cannot monitor ${regName.name}: $e"))
      _ <- ZIO.succeed(setChild(regName, pid))
    } yield ()

  private def setChild(regName: Symbol, pid: Pid): Unit = regName match {
    case Symbol("cleanup")  => cleanup = Some(pid)
    case Symbol("analyzer") => analyzer = Some(pid)
    case Symbol("main")     => manager = Some(pid)
    case Symbol("init")     => init = Some(pid)
    case Symbol("rex")      => rex = Some(pid)
    case _                  => ()
  }

  private def clearChild(regName: Symbol): Unit = regName match {
    case Symbol("cleanup")  => cleanup = None
    case Symbol("analyzer") => analyzer = None
    case Symbol("main")     => manager = None
    case Symbol("init")     => init = None
    case Symbol("rex")      => rex = None
    case _                  => ()
  }

  override def onTermination[PContext <: ProcessContext](reason: Codec.ETerm, ctx: PContext) = {
    val reasonScala = adapter.toScala(reason)
    for {
      _ <- stopChild(Symbol("main"), reasonScala, ctx)
      _ <- stopChild(Symbol("cleanup"), reasonScala, ctx)
      _ <- stopChild(Symbol("analyzer"), reasonScala, ctx)
      _ <- stopChild(Symbol("init"), reasonScala, ctx)
      _ <- stopChild(Symbol("rex"), reasonScala, ctx)
    } yield ()
  }

  override def handleCall(tag: (Pid, Any), request: Any): Any = {
    request match {
      case (Symbol("isAlive"), Symbol("main")) => {
        val result = manager match {
          case Some(pid) => ping(pid)
          case None => false
        }
        (Symbol("reply"), result)
      }
      case (Symbol("isAlive"), Symbol("cleanup")) =>
        val result = cleanup match {
          case Some(pid) => ping(pid)
          case None => false
        }
        (Symbol("reply"), result)
      case (Symbol("isAlive"), Symbol("analyzer")) => {
        val result = analyzer match {
          case Some(pid) => ping(pid)
          case None => false
        }
        (Symbol("reply"), result)
      }
      case (Symbol("isAlive"), Symbol("init")) => {
        val result = init match {
          case Some(pid) => ping(pid)
          case None => false
        }
        (Symbol("reply"), result)
      }
      case (Symbol("isAlive"), Symbol("rex")) => {
        val result = rex match {
          case Some(pid) => ping(pid)
          case None => false
        }
        (Symbol("reply"), result)
      }
      case (Symbol("getChild"), name: Symbol) =>
        (Symbol("reply"), getChild(name).getOrElse(Symbol("undefined")))
      case Symbol("listChildren") =>
              (Symbol("reply"), Map(
                Symbol("main") ->
                  getChild(Symbol("main")).getOrElse(Symbol("undefined")),
                Symbol("cleanup") ->
                  getChild(Symbol("cleanup")).getOrElse(Symbol("undefined")),
                Symbol("analyzer") ->
                  getChild(Symbol("analyzer")).getOrElse(Symbol("undefined")),
                Symbol("init") ->
                  getChild(Symbol("init")).getOrElse(Symbol("undefined")),
                Symbol("rex") ->
                  getChild(Symbol("rex")).getOrElse(Symbol("undefined")),
              ))
    }
  }

  override def handleMonitorExitZIO(monitored: Any, ref: Reference, reason: Any): ZIO[Any, Throwable, Unit] = {
    val pid     = monitored.asInstanceOf[Pid]
    val cnode   = adapter.node.asInstanceOf[ClouseauNode]
    val noneCtx = new ServiceContext[None.type] { val args = None }
    def respawn[TS <: Service[A] with Actor: Tag, A <: Product](
      regName: Symbol,
      builder: ActorBuilder.Sealed[TS]
    ): ZIO[Node with EngineWorker, Throwable, Unit] =
      ZIO.succeed(logger.warn(s"${regName.name} crashed with reason: ${reason}")) *>
        ZIO.succeed(clearChild(regName)) *>
        spawnMonitorZIO[TS, A](cnode, regName, builder)
    (ZIO.when(manager.contains(pid))(
      respawn[IndexManagerService, ConfigurationArgs](
        Symbol("main"), IndexManagerServiceBuilder.make(cnode, ctx)
      )
    ) *>
      ZIO.when(cleanup.contains(pid))(
        respawn[IndexCleanupService, ConfigurationArgs](
          Symbol("cleanup"), IndexCleanupServiceBuilder.make(cnode, ctx)
        )
      ) *>
      ZIO.when(analyzer.contains(pid))(
        respawn[AnalyzerService, ConfigurationArgs](
          Symbol("analyzer"), AnalyzerServiceBuilder.make(cnode, ctx)
        )
      ) *>
      ZIO.when(init.contains(pid))(
        respawn[InitService, ConfigurationArgs](
          Symbol("init"), InitService.make(cnode, ctx, "init")
        )
      ) *>
      ZIO.when(rex.contains(pid))(
        respawn[RexService, None.type](
          Symbol("rex"), RexService.make(cnode, noneCtx)
        )
      )).unit.provideEnvironment(cnode.runtime.environment)
  }

  def getChild(name: Symbol): Option[Pid] = {
    name match {
      case Symbol("main") => manager
      case Symbol("cleanup") => cleanup
      case Symbol("analyzer") => analyzer
      case Symbol("init") => init
      case Symbol("rex") => rex
      case _ => None
    }
  }

  def waitTermination[PContext <: ProcessContext](pid: Pid, ctx: PContext) = {
    val address = ctx.addressFromEPid(pid.fromScala)
    ctx.worker.exchange
      .isKnown(address)
      .repeat(Schedule.recurWhile[Boolean](_ == true) && Schedule.spaced(25.millis))
      .timeout(TERMINATION_TIMEOUT)
      .unit
  }

  def stopChild[PContext <: ProcessContext](name: Symbol, reason: Any, ctx: PContext) = {
    for {
      maybeChild <- ZIO.succeedBlocking(getChild(name))
      _ <- ZIO.succeedBlocking(maybeChild.map(pid => exit(pid, reason)))
      duration <- maybeChild match {
        case Some(pid) => waitTermination(pid, ctx).timed
        case None => ZIO.succeed((Duration.Zero, ()))
      }
      _ <- ZIO.logDebug(s"${name.name} is shut down after ${duration._1.toMillis()} ms")
    } yield ()
  }

}

object ClouseauSupervisor extends ActorConstructor[ClouseauSupervisor] {
  def make(node: SNode, service_ctx: ServiceContext[ConfigurationArgs]) = {
    def maker[PContext <: ProcessContext](process_context: PContext): ClouseauSupervisor = {
      ClouseauSupervisor(service_ctx)(Adapter(process_context, node, ClouseauTypeFactory))
    }

    ActorBuilder()
      // TODO get capacity from config
      .withCapacity(16)
      .withName("sup")
      .withMaker(maker)
      .build(this)
  }

  def start(
    node: SNode,
    config: Configuration
  ): ZIO[EngineWorker & Node & ActorFactory, Throwable, AddressableActor[_, _]] = {
    val ctx = new ServiceContext[ConfigurationArgs] { val args = ConfigurationArgs(config) }
    node.spawnServiceZIO[ClouseauSupervisor, ConfigurationArgs](make(node, ctx))
  }
}
