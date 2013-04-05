/*
 * Copyright 2012 Cloudant. All rights reserved.
 */

package com.cloudant.clouseau

import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy
import org.apache.commons.configuration._
import org.apache.log4j.Logger

import scalang._
import scalang.node._

object Main extends App {

  val logger = Logger.getLogger("clouseau.main")

  Thread.setDefaultUncaughtExceptionHandler(
    new Thread.UncaughtExceptionHandler {
      def uncaughtException(t : Thread, e : Throwable) {
        logger.fatal("Uncaught exception: " + e.getMessage)
        System.exit(1)
      }
    }
  )

  // Load and monitor configuration file.
  val config = new CompositeConfiguration()
  config.addConfiguration(new SystemConfiguration())

  val fileName = if (args.length > 0) args(0) else "clouseau.ini"
  val reloadableConfig = new HierarchicalINIConfiguration(fileName)
  reloadableConfig.setReloadingStrategy(new FileChangedReloadingStrategy)
  config.addConfiguration(reloadableConfig)

  val name = config.getString("clouseau.name", "clouseau@127.0.0.1")
  val cookie = config.getString("clouseau.cookie", "monster")
  val nodeconfig = NodeConfig(
    typeFactory = ClouseauTypeFactory,
    typeEncoder = ClouseauTypeEncoder,
    typeDecoder = ClouseauTypeDecoder)
  val node = Node(name, cookie, nodeconfig)

  scala.sys.addShutdownHook {
    node.call('main, 'close)
  }

  ClouseauSupervisor.start(node)
  logger.info("Clouseau running as " + name)
}
