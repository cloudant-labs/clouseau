package com.cloudant.clouseau

import org.apache.commons.configuration.Configuration
import org.apache.log4j.Logger
import scalang._

case class IndexManagerServiceArgs(config: Configuration)
class IndexManagerService(ctx: ServiceContext[IndexManagerServiceArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case ('open, path: String) =>
      val start = System.currentTimeMillis
      val pid = IndexService.start(node, rootDir, path)
      val duration = System.currentTimeMillis - start
      logger.info("%s: opened in %d ms".format(path, duration))
      node.link(tag._1, pid)
      ('ok, pid)
  }

  val logger = Logger.getLogger("clouseau.main")
  val rootDir = ctx.args.config.getString("clouseau.dir", "target/indexes")
}

object IndexManagerService {
  def start(node: Node, config: Configuration): Pid = {
    node.spawnService[IndexManagerService, IndexManagerServiceArgs]('main, IndexManagerServiceArgs(config))
  }
}
