package com.cloudant.clouseau

import scala.collection.mutable.LinkedHashMap
import org.apache.log4j.Logger
import scalang._
import java.nio.ByteBuffer
import java.nio.charset.Charset
import org.apache.commons.configuration.Configuration
import java.security.MessageDigest

case class IndexManagerServiceArgs(config: Configuration)
class IndexManagerService(ctx: ServiceContext[IndexManagerServiceArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case ('get_index_server, dbName: String, indexSig: String, indexDef: String) =>
      val key = (dbName, indexSig)
      maybeCloseOldest(key)
      val pid = indexes.get(key) match {
        case Some(pid: Pid) if node.isAlive(pid) =>
          pid
        case _ =>
          val pid = IndexService.start(node, dbName, indexSig, indexDef, ctx.args.config)
          indexes.put(key, pid)
          pid
      }
      ('ok, pid)
    case _ =>
      'ignored
  }

  override def handleCast(msg: Any) {
    // Ignored
  }

  override def handleInfo(msg: Any) {
    // Ignored
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
