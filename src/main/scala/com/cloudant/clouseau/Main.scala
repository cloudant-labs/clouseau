package com.cloudant.clouseau

import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy
import org.apache.commons.configuration._
import org.apache.log4j.Logger

import scalang._
import scalang.node._

object Main extends App {

  val logger = Logger.getLogger("clouseau.main")

  // Load and monitor configuration file.
  val config = new CompositeConfiguration()
  config.addConfiguration(new SystemConfiguration())

  val fileName = if (args.length > 0) args(0) else "clouseau.ini"
  val reloadableConfig = new HierarchicalINIConfiguration(fileName)
  reloadableConfig.setReloadingStrategy(new FileChangedReloadingStrategy)
  config.addConfiguration(reloadableConfig)

  val name = config.getString("clouseau.name", "clouseau@127.0.0.1")
  val cookie = config.getString("clouseau.cookie", "monster")
  val nodeconfig = NodeConfig(typeFactory = ClouseauTypeFactory)
  val node = Node(name, cookie, nodeconfig)

  ClouseauSupervisor.start(node)
  logger.info("Clouseau running as " + name)
}
