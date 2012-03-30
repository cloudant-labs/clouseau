package com.cloudant.clouseau

import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy
import org.apache.commons.configuration._
import org.apache.log4j.Logger

import scalang._
import scalang.node._

object Main extends App {
  val logger = Logger.getLogger("clouseau.main")

  // Load and monitor configuration file.
  val fileName = if (args.length > 0) args(0) else "clouseau.ini"
  val config = new CompositeConfiguration()
  config.addConfiguration(new SystemConfiguration())
  val reloadableConfig = new HierarchicalINIConfiguration(fileName)
  reloadableConfig.setReloadingStrategy(new FileChangedReloadingStrategy)
  config.addConfiguration(reloadableConfig)

  val caseClassFactory = new CaseClassFactory(Nil, Map("doc" -> classOf[Doc],
                                                       "hit" -> classOf[Hit],
                                                       "hits" -> classOf[Hits]))
  val nodeConfig = NodeConfig(typeFactory = caseClassFactory)

  val name = config.getString("clouseau.name", "clouseau@127.0.0.1")
  val cookie = config.getString("clouseau.cookie", "monster")
  val node = Node(name, cookie, nodeConfig)

  IndexManagerService.start(node, config)
  logger.info("Clouseau running as " + name)
}
