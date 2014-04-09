/*
 * Copyright 2012 Cloudant. All rights reserved.
 */

package com.cloudant.clouseau

import org.apache.log4j.Logger
import scalang._
import org.apache.commons.configuration.Configuration

case class ConfigurationArgs(config: Configuration)

class ClouseauSupervisor(ctx: ServiceContext[ConfigurationArgs]) extends Service(ctx) {

  val logger = Logger.getLogger("clouseau.supervisor")
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
