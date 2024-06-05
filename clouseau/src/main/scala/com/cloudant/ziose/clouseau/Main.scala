/*
sbt 'clouseau/runMain com.cloudant.ziose.clouseau.Main'
 */
package com.cloudant.ziose.clouseau

import _root_.com.cloudant.ziose._
import core.{ActorFactory, AddressableActor, EngineWorker, Node}
import otp.{OTPActorFactory, OTPEngineWorker, OTPNode, OTPNodeConfig}
import scalang.ScalangMeterRegistry
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.FromConfigSourceTypesafe
import zio.{&, ConfigProvider, IO, RIO, Scope, System, Task, ZIO, ZIOAppArgs, ZIOAppDefault}

import java.io.FileNotFoundException
import zio.ZLayer
import com.cloudant.ziose.core.Engine.EngineId
import com.cloudant.ziose.core.Engine.WorkerId

object Main extends ZIOAppDefault {
  final case class NodeCfg(config: List[AppConfiguration])

  def getConfig(pathToCfgFile: String): IO[FileNotFoundException, NodeCfg] = {
    ConfigProvider
      .fromHoconFilePath(pathToCfgFile)
      .load(deriveConfig[NodeCfg])
      .orElseFail(new FileNotFoundException(s"File Not Found: $pathToCfgFile"))
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
        .provide(nodeLayers(engineId, workerId, node))
    } yield ()
  }

  override def run: ZIO[ZIOAppArgs & Scope, Any, Unit] = {
    for {
      cfgFile <- getArgs.map(_.headOption.getOrElse("app.conf"))
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

  def nodeLayers(
    engineId: EngineId,
    workerId: WorkerId,
    nodeCfg: OTPNodeConfig
  ): ZLayer[Any, Throwable, EngineWorker & Node & ActorFactory] = {
    val name    = s"${nodeCfg.name}@${nodeCfg.domain}"
    val factory = OTPActorFactory.live(name, nodeCfg)
    val node    = OTPNode.live(name, engineId, workerId, nodeCfg)
    val worker  = OTPEngineWorker.live(engineId, workerId, name, nodeCfg)
    factory >+> node >+> worker
  }

  def testEnvironment: ZLayer[Any, Throwable, EngineWorker & Node & ActorFactory & OTPNodeConfig] = {
    val nodeCfg = OTPNodeConfig("test", "127.0.0.1", "testCookie")
    nodeLayers(engineId, workerId, nodeCfg) ++ ZLayer.succeed(nodeCfg)
  }

  def testClouseauNode: ZIO[EngineWorker & Node & ActorFactory & OTPNodeConfig, Throwable, ClouseauNode] = for {
    runtime  <- ZIO.runtime[EngineWorker & Node & ActorFactory]
    otp_node <- ZIO.service[Node]
    worker   <- ZIO.service[EngineWorker]
    metricsRegistry = ClouseauMetrics.makeRegistry
    node    <- ZIO.succeed(new ClouseauNode()(runtime, worker, metricsRegistry))
    nodeCfg <- ZIO.service[OTPNodeConfig]
  } yield node

  def testConfiguration: ZIO[OTPNodeConfig, Throwable, Configuration] = for {
    nodeCfg <- ZIO.service[OTPNodeConfig]
  } yield Configuration(ClouseauConfiguration(), nodeCfg)

}
