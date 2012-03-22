package com.cloudant.clouseau

import scala.collection.mutable.LinkedHashMap
import org.apache.log4j.Logger
import scalang._
import java.nio.ByteBuffer
import java.nio.charset.Charset

class IndexManagerService(ctx: ServiceContext[ServerArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case ('get_index_server, dbName: ByteBuffer, indexName: ByteBuffer) =>
      val dbNameStr = Utils.toString(dbName)
      val indexNameStr = Utils.toString(indexName)
      val pid = indexes.get((dbNameStr, indexNameStr)) match {
        case None =>
          val pid = node.spawnService[IndexService, IndexServiceArgs](IndexServiceArgs(dbNameStr, indexNameStr, ctx.args.config))
          indexes.put((dbNameStr, indexNameStr), pid)
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
