package com.cloudant.clouseau

import scala.collection.mutable.LinkedHashMap
import org.apache.log4j.Logger
import scalang._

class IndexManagerService(ctx: ServiceContext[ServerArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case ('get_index_server, dbName: String, indexName: String) =>
      val pid = indexes.get((dbName, indexName)) match {
        case None =>
          val pid = node.spawnService[IndexService, IndexServiceArgs](IndexServiceArgs(dbName, indexName, ctx.args.config))
          indexes.put((dbName, indexName), pid)
          pid
        case Some(pid: Pid) =>
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
  val indexes = new LinkedHashMap[(String, String), Pid]
}