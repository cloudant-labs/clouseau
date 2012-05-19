package com.cloudant.clouseau

import org.apache.commons.configuration.Configuration
import org.apache.log4j.Logger
import scalang._

case class IndexManagerServiceArgs()
class IndexManagerService(ctx: ServiceContext[IndexManagerServiceArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case OpenIndexMsg(path: String) =>
      val start = System.currentTimeMillis
      IndexService.start(node, rootDir, path) match {
        case ('ok, pid: Pid) =>
          val duration = System.currentTimeMillis - start
          logger.info("%s: opened in %d ms".format(path, duration))
          node.link(tag._1, pid)
          ('ok, pid)
        case error =>
          error
      }
  }

  val logger = Logger.getLogger("clouseau.main")
  val rootDir = Main.config.getString("clouseau.dir", "target/indexes")
}

object IndexManagerService {
  def start(node: Node): Pid = {
    node.spawnService[IndexManagerService, IndexManagerServiceArgs]('main, IndexManagerServiceArgs())
  }
}
