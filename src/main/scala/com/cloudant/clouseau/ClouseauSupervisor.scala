/*
 * Copyright 2012 Cloudant. All rights reserved.
 */

package com.cloudant.clouseau

import org.apache.log4j.Logger
import scalang._

class ClouseauSupervisor(ctx : ServiceContext[NoArgs]) extends Service(ctx) {

  val logger = Logger.getLogger("clouseau.supervisor")
  var manager = spawnAndMonitorService[IndexManagerService, NoArgs]('main, NoArgs)
  var cleanup = spawnAndMonitorService[IndexCleanupService, NoArgs]('cleanup, NoArgs)

  override def trapMonitorExit(monitored : Any, ref : Reference, reason : Any) {
    if (monitored == manager) {
      logger.warn("manager crashed")
      manager = spawnAndMonitorService[IndexManagerService, NoArgs]('main, NoArgs)
    }
    if (monitored == cleanup) {
      logger.warn("cleanup crashed")
      cleanup = spawnAndMonitorService[IndexCleanupService, NoArgs]('cleanup, NoArgs)
    }
  }

  private def spawnAndMonitorService[T <: Service[A], A <: Product](regName : Symbol, args : A)(implicit mf : Manifest[T]) : Pid = {
    val pid = node.spawnService[T,A](regName, args, reentrant = false)(mf)
    monitor(pid)
    pid
  }

}

object ClouseauSupervisor {

  def start(node : Node) : Pid = {
    node.spawnService[ClouseauSupervisor, NoArgs]('sup, NoArgs)
  }

}
