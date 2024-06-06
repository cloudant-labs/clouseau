/*
sbt 'clouseau/runMain com.cloudant.ziose.clouseau.Main'
 */
package com.cloudant.ziose.clouseau

import _root_.com.cloudant.ziose._
import core.{ActorFactory, AddressableActor, EngineWorker, Node}
import otp.{OTPLayers, OTPNodeConfig}
import scalang.ScalangMeterRegistry
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.FromConfigSourceTypesafe
import zio.{&, Config, ConfigProvider, IO, RIO, Scope, System, Task, UIO, ZIO, ZIOAppArgs, ZIOAppDefault}

import java.io.FileNotFoundException
import scala.reflect.io.File

object Main extends ZIOAppDefault {
  private val defaultCfgFile: String = "app.conf"

  def getCfgFile(args: Option[String]): UIO[String] = {
    args match {
      case Some(file) =>
        if (File(file).exists) ZIO.succeed(file)
        else ZIO.die(new FileNotFoundException(s"The system cannot find the file specified"))
      case None =>
        ZIO.succeed(defaultCfgFile)
    }
  }

  def getNodeIdx: Task[Int] = {
    for {
      prop <- System.property("node")
      lastChar = prop.getOrElse("1").last
      index = {
        if (('1' to '3').contains(lastChar)) {
          lastChar - '1'
        } else {
          0
        }
      }
    } yield index
  }

  final case class NodeCfg(config: List[AppConfiguration])

  def getConfig(pathToCfgFile: String): IO[Config.Error, NodeCfg] = {
    ConfigProvider
      .fromHoconFilePath(pathToCfgFile)
      .load(deriveConfig[NodeCfg])
  }

  private def startSupervisor(
    node: ClouseauNode,
    config: AppConfiguration
  ): RIO[EngineWorker & Node & ActorFactory, AddressableActor[_, _]] = {
    val clouseauCfg: ClouseauConfiguration = config.clouseau.get
    val nodeCfg: OTPNodeConfig             = config.node
    ClouseauSupervisor.start(node, Configuration(clouseauCfg, nodeCfg))
  }

  private def main(
    nodeCfg: AppConfiguration,
    metricsRegistry: ScalangMeterRegistry
  ): RIO[EngineWorker & Node & ActorFactory, Unit] = {
    for {
      runtime  <- ZIO.runtime[EngineWorker & Node & ActorFactory]
      otp_node <- ZIO.service[Node]
      remote_node = s"node${nodeCfg.node.name.last}@${nodeCfg.node.domain}"
      _      <- otp_node.monitorRemoteNode(remote_node)
      worker <- ZIO.service[EngineWorker]
      node   <- ZIO.succeed(new ClouseauNode()(runtime, worker, metricsRegistry))
      _      <- startSupervisor(node, nodeCfg)
      _      <- worker.awaitShutdown
    } yield ()
  }

  private val workerId: Int = 1
  private val engineId: Int = 1
  private val logger        = LoggerFactory.getZioLogger("clouseau.main")

  private def app(cfgFile: String, metricsRegistry: ScalangMeterRegistry): Task[Unit] = {
    for {
      nodeIdx  <- getNodeIdx
      nodesCfg <- getConfig(cfgFile)
      nodeCfg = nodesCfg.config(nodeIdx)
      node    = nodeCfg.node
      name    = s"${node.name}@${node.domain}"
      _ <- logger.info("Clouseau running as " + name)
      _ <- ZIO
        .scoped(main(nodeCfg, metricsRegistry))
        .provide(OTPLayers.nodeLayers(engineId, workerId, node))
    } yield ()
  }

  override def run: RIO[ZIOAppArgs & Scope, Unit] = {
    for {
      args    <- getArgs.map(_.headOption)
      cfgFile <- getCfgFile(args)
      metricsRegistry = ClouseauMetrics.makeRegistry
      metricsLayer    = ClouseauMetrics.makeLayer(metricsRegistry)
      _ <- ZIO
        .scoped(app(cfgFile, metricsRegistry))
        .provide(
          LoggerFactory.loggerDefault,
          metricsLayer
        )
    } yield ()
  }
}
