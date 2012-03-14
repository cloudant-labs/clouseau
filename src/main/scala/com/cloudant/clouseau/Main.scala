package com.cloudant.clouseau

import java.io.File
import scalang.{ Service, ServiceContext, Reference, Pid, Node, NoArgs }
import org.apache.commons.configuration.HierarchicalINIConfiguration
import org.apache.commons.configuration.reloading.ManagedReloadingStrategy
import org.apache.commons.configuration.HierarchicalConfiguration
import org.apache.log4j.Logger

case class ServerArgs(config: HierarchicalConfiguration)
object Main extends App {
  val logger = Logger.getLogger("main")
  val fileName = if (args.length > 0) args(0) else "clouseau.ini"
  val config = new HierarchicalINIConfiguration(fileName)
  val name = config.getString("clouseau.name", "clouseau@127.0.0.1")
  val node = config.getString("clouseau.cookie") match {
    case null => Node(name)
    case (cookie: String) => Node(name, cookie)
  }
  val dir = config.getString("clouseau.dir", "target/indexes")

  node.spawnService[IndexManagerService, ServerArgs]('main, ServerArgs(config))
  logger.info("Clouseau running.")
}