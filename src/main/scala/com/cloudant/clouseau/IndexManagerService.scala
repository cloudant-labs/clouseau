package com.cloudant.clouseau

import org.apache.commons.configuration.Configuration
import org.apache.log4j.Logger
import scalang._

case class IndexManagerServiceArgs(config: Configuration)
class IndexManagerService(ctx: ServiceContext[IndexManagerServiceArgs]) extends Service(ctx) {

  override def handleCall(tag: (Pid, Reference), msg: Any): Any = msg match {
    case ('open, path: String) =>
      val pid = IndexService.start(node, rootDir, path)
      ('ok, pid)
  }

  val rootDir = ctx.args.config.getString("clouseau.dir", "target/indexes")
}

object IndexManagerService {
  def start(node: Node, config: Configuration): Pid = {
    node.spawnService[IndexManagerService, IndexManagerServiceArgs]('main, IndexManagerServiceArgs(config))
  }
}
