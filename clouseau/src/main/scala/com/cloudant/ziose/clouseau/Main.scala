/*
sbt 'clouseau/runMain com.cloudant.ziose.clouseau.Main'
 */
package com.cloudant.ziose.clouseau

import com.cloudant.ziose.core.{ActorFactory, AddressableActor, EngineWorker, Node}
import com.cloudant.ziose.otp.{OTPLayers, OTPNodeConfig}
import com.cloudant.ziose.scalang.ScalangMeterRegistry
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

  final case class AppCfg(config: List[WorkerConfiguration], logger: LogConfiguration)

  def getConfig(pathToCfgFile: String): IO[Config.Error, AppCfg] = {
    ConfigProvider
      .fromHoconFilePath(pathToCfgFile)
      .load(deriveConfig[AppCfg])
  }

  private def startSupervisor(
    node: ClouseauNode,
    config: WorkerConfiguration
  ): RIO[EngineWorker & Node & ActorFactory, AddressableActor[_, _]] = {
    val clouseauCfg: ClouseauConfiguration = config.clouseau.get
    val nodeCfg: OTPNodeConfig             = config.node
    ClouseauSupervisor.start(node, Configuration(clouseauCfg, nodeCfg))
  }

  private def main(
    workerCfg: WorkerConfiguration,
    metricsRegistry: ScalangMeterRegistry
  ): RIO[EngineWorker & Node & ActorFactory, Unit] = {
    for {
      runtime  <- ZIO.runtime[EngineWorker & Node & ActorFactory]
      otp_node <- ZIO.service[Node]
      remote_node = s"node${workerCfg.node.name.last}@${workerCfg.node.domain}"
      _      <- otp_node.monitorRemoteNode(remote_node)
      worker <- ZIO.service[EngineWorker]
      node   <- ZIO.succeed(new ClouseauNode()(runtime, worker, metricsRegistry))
      _      <- startSupervisor(node, workerCfg)
      _      <- worker.awaitShutdown
    } yield ()
  }

  private val workerId: Int = 1
  private val engineId: Int = 1

  private def app(workerCfg: WorkerConfiguration, metricsRegistry: ScalangMeterRegistry): Task[Unit] = {
    val node = workerCfg.node
    val name = s"${node.name}@${node.domain}"
    for {
      _ <- ZIO.logInfo("Clouseau running as " + name)
      _ <- ZIO
        .scoped(main(workerCfg, metricsRegistry))
        .provide(OTPLayers.nodeLayers(engineId, workerId, node))
    } yield ()
  }

  override def run: RIO[ZIOAppArgs & Scope, Unit] = {
    for {
      args    <- getArgs.map(_.headOption)
      cfgFile <- getCfgFile(args)
      appCfg  <- getConfig(cfgFile)
      nodeIdx <- getNodeIdx
      workerCfg       = appCfg.config(nodeIdx)
      loggerCfg       = appCfg.logger
      metricsRegistry = ClouseauMetrics.makeRegistry
      metricsLayer    = ClouseauMetrics.makeLayer(metricsRegistry)
      _ <- ZIO
        .scoped(app(workerCfg, metricsRegistry))
        .provide(
          LoggerFactory.loggerDefault(loggerCfg),
          metricsLayer
        )
    } yield ()
  }
}
