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

package com.cloudant.clouseau

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import scalang._
import org.apache.commons.configuration.Configuration

case class ConfigurationArgs(config: Configuration)

class ClouseauSupervisor(ctx: ServiceContext[ConfigurationArgs]) extends Service(ctx) {

  val logger = LoggerFactory.getLogger("clouseau.supervisor")
  var manager = spawnAndMonitorService[IndexManagerService, ConfigurationArgs]('main, ctx.args)
  var cleanup = spawnAndMonitorService[IndexCleanupService, ConfigurationArgs]('cleanup, ctx.args)
  var analyzer = spawnAndMonitorService[AnalyzerService, ConfigurationArgs]('analyzer, ctx.args)

  override def trapMonitorExit(monitored: Any, ref: Reference, reason: Any) {
    if (monitored == manager) {
      logger.warn("manager crashed")
      manager = spawnAndMonitorService[IndexManagerService, ConfigurationArgs]('main, ctx.args)
    }
    if (monitored == cleanup) {
      logger.warn("cleanup crashed")
      cleanup = spawnAndMonitorService[IndexCleanupService, ConfigurationArgs]('cleanup, ctx.args)
    }
    if (monitored == analyzer) {
      logger.warn("analyzer crashed")
      analyzer = spawnAndMonitorService[AnalyzerService, ConfigurationArgs]('analyzer, ctx.args)
    }
  }

  private def spawnAndMonitorService[T <: Service[A], A <: Product](regName: Symbol, args: A)(implicit mf: Manifest[T]): Pid = {
    val pid = node.spawnService[T, A](regName, args, reentrant = false)(mf)
    monitor(pid)
    pid
  }

}

object ClouseauSupervisor {

  def start(node: Node, config: Configuration): Pid = {
    node.spawnService[ClouseauSupervisor, ConfigurationArgs]('sup, ConfigurationArgs(config))
  }

}
