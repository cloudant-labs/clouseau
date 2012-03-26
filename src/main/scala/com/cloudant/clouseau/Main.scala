package com.cloudant.clouseau

import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy
import org.apache.commons.configuration.HierarchicalConfiguration
import org.apache.commons.configuration.HierarchicalINIConfiguration
import org.apache.log4j.Logger

import scalang._

object Main extends App {
  val logger = Logger.getLogger("clouseau.main")

  // Load and monitor configuration file.
  val fileName = if (args.length > 0) args(0) else "clouseau.ini"
  val config = new HierarchicalINIConfiguration(fileName)
  config.setReloadingStrategy(new FileChangedReloadingStrategy)

  val name = config.getString("clouseau.name", "clouseau@127.0.0.1")
  val node = config.getString("clouseau.cookie") match {
    case null => Node(name)
    case cookie: String => Node(name, cookie)
  }
  val dir = config.getString("clouseau.dir", "target/indexes")

  IndexManagerService.start(node, config)
  logger.info("Clouseau running as " + name)
}
