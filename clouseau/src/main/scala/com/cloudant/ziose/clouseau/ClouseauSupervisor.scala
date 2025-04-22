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
import java.time.Instant
import java.time.temporal.ChronoUnit

case class ClouseauSupervisor(
    ctx: ServiceContext[ConfigurationArgs],
    var manager: Option[Pid] = None,
    var cleanup: Option[Pid] = None,
    var analyzer: Option[Pid] = None,
    var init: Option[Pid] = None,
  )(implicit adapter: Adapter[_, _])
    extends Service(ctx) {
  val TERMINATION_TIMEOUT = Duration.fromSeconds(3)
  val logger = LoggerFactory.getLogger("clouseau.supervisor")

  override def onInit[P <: ProcessContext](_ctx: P): ZIO[Any, Throwable, _ <: ActorResult] = for {
    _ <- ZIO.succeed(spawnAndMonitorService[IndexManagerService, ConfigurationArgs](Symbol("main"), ctx.args))
    _ <- ZIO.succeed(spawnAndMonitorService[IndexCleanupService, ConfigurationArgs](Symbol("cleanup"), ctx.args))
    _ <- ZIO.succeed(spawnAndMonitorService[AnalyzerService, ConfigurationArgs](Symbol("analyzer"), ctx.args))
    _ <- ZIO.succeed(spawnAndMonitorService[InitService, ConfigurationArgs](Symbol("init"), ctx.args))
  } yield ActorResult.Continue()

  override def onTermination[PContext <: ProcessContext](reason: Codec.ETerm, ctx: PContext) = {
    val reasonScala = adapter.toScala(reason)
    for {
      _ <- stopChild(Symbol("main"), reasonScala, ctx)
      _ <- stopChild(Symbol("cleanup"), reasonScala, ctx)
      _ <- stopChild(Symbol("analyzer"), reasonScala, ctx)
      _ <- stopChild(Symbol("init"), reasonScala, ctx)
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
      case (Symbol("getChild"), name: Symbol) =>
        (Symbol("reply"), getChild(name).getOrElse(Symbol("undefined")))
    }
  }

  override def trapMonitorExit(monitored: Any, ref: Reference, reason: Any): Unit = {
    val pid = monitored.asInstanceOf[Pid]
    if (manager.contains(pid)) {
      logger.warn(s"manager crashed with reason: ${reason}")
      manager = None
      spawnAndMonitorService[IndexManagerService, ConfigurationArgs](Symbol("main"), ctx.args)
    }
    if (cleanup.contains(pid)) {
      logger.warn(s"cleanup crashed with reason: ${reason}")
      cleanup = None
      spawnAndMonitorService[IndexCleanupService, ConfigurationArgs](Symbol("cleanup"), ctx.args)
    }
    if (analyzer.contains(pid)) {
      logger.warn(s"analyzer crashed with reason: ${reason}")
      analyzer = None
      spawnAndMonitorService[AnalyzerService, ConfigurationArgs](Symbol("analyzer"), ctx.args)
    }
    if (init.contains(pid)) {
      logger.warn(s"init crashed with reason: ${reason}")
      init = None
      spawnAndMonitorService[EchoService, ConfigurationArgs](Symbol("init"), ctx.args)
    }
  }

  def getChild(name: Symbol): Option[Pid] = {
    name match {
      case Symbol("main") => manager
      case Symbol("cleanup") => cleanup
      case Symbol("analyzer") => analyzer
      case Symbol("init") => init
      case _ => None
    }
  }

  def waitTermination[PContext <: ProcessContext](pid: Pid, ctx: PContext) = {
    val address = ctx.addressFromEPid(pid.fromScala)
    ctx.worker.exchange.isKnown(address).repeatWhile(_ == true).timeout(TERMINATION_TIMEOUT).unit
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

  private def spawnAndMonitorService[TS <: Service[A] with Actor: Tag, A <: Product](regName: Symbol, args: A)(implicit
    adapter: Adapter[_, _]
  ) = {
    val beginTs = Instant.now()

    val result = (regName, args) match {
      // case (Symbol("IndexService"), args: IndexServiceArgs) => IndexServiceBuilder.start(adapter.node, args)
      case (Symbol("cleanup"), ConfigurationArgs(args))  => IndexCleanupServiceBuilder.start(adapter.node, args)
      case (Symbol("analyzer"), ConfigurationArgs(args)) => AnalyzerServiceBuilder.start(adapter.node, args)
      case (Symbol("main"), ConfigurationArgs(args))     => IndexManagerServiceBuilder.start(adapter.node, args)
      case (Symbol("init"), ConfigurationArgs(args))     => InitService.start(adapter.node, "init", args)
    }
    val timeSpentInMs = ChronoUnit.MILLIS.between(beginTs, Instant.now)
    val pid = result match {
      case (Symbol("ok"), pidUntyped) =>
        logger.debug(s"${regName.name} is started after ${timeSpentInMs} ms")
        pidUntyped.asInstanceOf[Pid]
      case e =>
        throw new Throwable(s"cannot start ${regName.name} after ${timeSpentInMs} ms, due to ${e.toString}")
    }
    val ref = monitor(pid)

    regName match {
      case Symbol("cleanup")  => cleanup = Some(pid)
      case Symbol("analyzer") => analyzer = Some(pid)
      case Symbol("main")     => manager = Some(pid)
      case Symbol("init")     => init = Some(pid)
    }
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
