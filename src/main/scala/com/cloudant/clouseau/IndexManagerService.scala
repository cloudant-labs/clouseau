package com.cloudant.clouseau

import scala.collection.mutable.HashMap
import org.apache.log4j.Logger
import scalang._
import java.nio.ByteBuffer
import java.nio.charset.Charset
import org.apache.commons.configuration.Configuration
import java.security.MessageDigest

case class IndexManagerServiceArgs(config: Configuration)
class IndexManagerService(ctx: ServiceContext[IndexManagerServiceArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case ('get_index_server, dbName: String, index: List[(Symbol, Any)]) =>
      val key = (dbName, IndexService.getSignature(index))
      val pid = indexes.get(key) match {
        case Some(pid: Pid) if node.isAlive(pid) =>
          pid
        case _ =>
          val pid = IndexService.start(node, dbName, index, ctx.args.config)
          indexes.put(key, pid)
          pid
      }
      ('ok, pid)
  }

  val logger = Logger.getLogger("clouseau.manager")
  val indexes = new HashMap[(String, String), Pid]
}

object IndexManagerService {
  def start(node: Node, config: Configuration): Pid = {
    node.spawnService[IndexManagerService, IndexManagerServiceArgs]('main, IndexManagerServiceArgs(config))
  }
}
