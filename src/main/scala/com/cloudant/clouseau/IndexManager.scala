package com.cloudant.clouseau
import java.io.File
import scalang.{ Service, ServiceContext, Reference, Pid, NoArgs }
import org.cliffc.high_scale_lib.NonBlockingHashMap
import org.apache.commons.configuration.HierarchicalConfiguration
import org.apache.log4j.Logger

class IndexManager(ctx: ServiceContext[ServerArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case ('get_index_server, dbName: String, indexName: String) =>
      val pid = indexes.get((dbName, indexName)) match {
        case null =>
          logger.info("Spawning service for " + dbName + " : " + indexName)
          val pid = node.spawnService[IndexServer, IndexServerArgs](IndexServerArgs(dbName, indexName, ctx.args.config))
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

  val logger = Logger.getLogger("index_manager")
  val indexes = new NonBlockingHashMap[(String, String), Pid]
}