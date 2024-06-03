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

case class ClouseauSupervisor(ctx: ServiceContext[ConfigurationArgs])(implicit adapter: Adapter[_, _])
    extends Service(ctx)
    with Actor {
  // def onMessage[C <: ProcessContext](msg: MessageEnvelope, ctx: C): UIO[Unit] =
  //   ZIO.succeed(())
  // def onTermination[C <: ProcessContext](reason: Codec.ETerm, ctx: C): UIO[Unit] =
  //   ZIO.succeed(())
  val logger = LoggerFactory.getLogger("clouseau.supervisor")
  var manager = {
    spawnAndMonitorService[IndexManagerService, ConfigurationArgs](Symbol("main"), ctx.args)
  }
  var cleanup = {
    spawnAndMonitorService[IndexCleanupService, ConfigurationArgs](Symbol("cleanup"), ctx.args)
  }
  var analyzer = {
    spawnAndMonitorService[AnalyzerService, ConfigurationArgs](Symbol("analyzer"), ctx.args)
  }
  var init = spawnAndMonitorService[InitService, ConfigurationArgs](Symbol("init"), ctx.args)

  override def trapMonitorExit(monitored: Any, ref: Reference, reason: Any): Unit = {
    if (monitored == manager) {
      logger.warn("manager crashed")
      manager = {
        spawnAndMonitorService[IndexManagerService, ConfigurationArgs](Symbol("main"), ctx.args)
      }
    }
    if (monitored == cleanup) {
      logger.warn("cleanup crashed")
      cleanup = {
        spawnAndMonitorService[IndexCleanupService, ConfigurationArgs](Symbol("cleanup"), ctx.args)
      }
    }
    if (monitored == analyzer) {
      logger.warn("analyzer crashed")
      analyzer = {
        spawnAndMonitorService[AnalyzerService, ConfigurationArgs](Symbol("analyzer"), ctx.args)
      }
    }
    if (monitored == init) {
      logger.warn("init crashed")
      init = spawnAndMonitorService[EchoService, ConfigurationArgs](Symbol("init"), ctx.args)
    }
  }

  private def spawnAndMonitorService[TS <: Service[A] with Actor: Tag, A <: Product](regName: Symbol, args: A)(implicit
    adapter: Adapter[_, _]
  ): Pid = {
    val result = (regName, args) match {
      // case (Symbol("IndexService"), args: IndexServiceArgs) => IndexServiceBuilder.start(adapter.node, args)
      case (Symbol("cleanup"), ConfigurationArgs(args))  => IndexCleanupServiceBuilder.start(adapter.node, args)
      case (Symbol("analyzer"), ConfigurationArgs(args)) => AnalyzerServiceBuilder.start(adapter.node, args)
      case (Symbol("main"), ConfigurationArgs(args))     => IndexManagerServiceBuilder.start(adapter.node, args)
      case (Symbol("init"), ConfigurationArgs(args))     => InitService.start(adapter.node, "init", args)
    }
    logger.debug(s"$regName -> $result")
    result match {
      case (Symbol("ok"), pidUntyped) =>
        val pid = pidUntyped.asInstanceOf[Pid]
        logger.debug(pid.toString)
        monitor(pid)
        pid
      case e => throw new Throwable(s"cannot start ${e.toString}")
    }
  }

  def monitor(pid: Pid): Unit = () // TODO implement it when we have monitors
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
