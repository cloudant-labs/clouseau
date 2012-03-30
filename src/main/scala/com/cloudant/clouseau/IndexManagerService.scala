package com.cloudant.clouseau

import scala.collection.mutable.LinkedHashMap
import org.apache.log4j.Logger
import scalang._
import java.nio.ByteBuffer
import java.nio.charset.Charset
import org.apache.commons.configuration.Configuration
import com.cloudant.clouseau.Conversions._

case class IndexManagerServiceArgs(config: Configuration)
class IndexManagerService(ctx: ServiceContext[IndexManagerServiceArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case ('get_index_server, dbName: String, indexName: String) =>
      val pid = indexes.get((dbName, indexName)) match {
        case Some(pid: Pid) if node.isAlive(pid) =>
          pid
        case _ =>
          val pid = IndexService.start(node, dbName, indexName, ctx.args.config)
          indexes.put((dbName, indexName), pid)
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

object IndexManagerService {
  def start(node: Node, config: Configuration): Pid = {
    node.spawnService[IndexManagerService, IndexManagerServiceArgs]('main, IndexManagerServiceArgs(config))
  }
}
