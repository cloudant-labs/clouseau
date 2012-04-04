package com.cloudant.clouseau

import scala.collection.mutable.LinkedHashMap
import org.apache.log4j.Logger
import scalang._
import java.nio.ByteBuffer
import java.nio.charset.Charset
import org.apache.commons.configuration.Configuration

case class IndexManagerServiceArgs(config: Configuration)
class IndexManagerService(ctx: ServiceContext[IndexManagerServiceArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case ('get_index_server, dbName: String, indexName: String) =>
      val key = (dbName, indexName)
      maybeCloseOldest(key)
      val pid = indexes.get(key) match {
        case Some(pid: Pid) if node.isAlive(pid) =>
          pid
        case _ =>
          val pid = IndexService.start(node, dbName, indexName, ctx.args.config)
          indexes.put(key, pid)
          pid
      }
      ('ok, pid)
  }

  override def trapExit(from : Pid, msg : Any) {
    exit(msg)
    IndexManagerService.start(node, ctx.args.config) // bleurgh
  }

  // Close the least-recently accessed index if we are at max index limit.
  // Only close indexes with refCount == 1 that are not 'key'.
  private def maybeCloseOldest(key: Any) {
    var max = ctx.args.config.getInteger("clouseau.max_indexes", 100)
    if (indexes.size <= max) {
      return
    }
    // TODO
  }

  val logger = Logger.getLogger("clouseau.manager")
  val indexes = new LinkedHashMap[(String, String), Pid]
}

object IndexManagerService {
  def start(node: Node, config: Configuration): Pid = {
    node.spawnService[IndexManagerService, IndexManagerServiceArgs]('main, IndexManagerServiceArgs(config))
  }
}
