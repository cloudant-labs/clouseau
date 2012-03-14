package com.cloudant.clouseau

import org.apache.log4j.Logger
import org.cliffc.high_scale_lib.NonBlockingHashMap

import scalang._

class IndexManagerService(ctx: ServiceContext[ServerArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case ('get_index_server, dbName: String, indexName: String) =>
      val pid = indexes.get((dbName, indexName)) match {
        case null =>
          logger.info("Spawning service for " + dbName + " : " + indexName)
          val pid = node.spawnService[IndexService, IndexServiceArgs](IndexServiceArgs(dbName, indexName, ctx.args.config))
          indexes.put((dbName, indexName), pid)
          pid
        case pid: Pid =>
          pid
      }
      ('ok, pid)
    case _ =>
      // Remove if Scalang gets supervisors.
      ('error, msg)
  }

  override def handleCast(msg: Any) {
    // Remove if Scalang gets supervisors.
  }

  override def handleInfo(msg: Any) {
    // Remove if Scalang gets supervisors.
  }

  val logger = Logger.getLogger("clouseau.manager")
  val indexes = new NonBlockingHashMap[(String, String), Pid]
}