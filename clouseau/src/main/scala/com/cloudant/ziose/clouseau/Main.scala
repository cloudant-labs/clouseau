/*
sbt 'clouseau/runMain com.cloudant.ziose.clouseau.Main'
 */
package com.cloudant.ziose.clouseau

import com.cloudant.ziose.core.{ActorFactory, AddressableActor, EngineWorker, Node}
import com.cloudant.ziose.otp.{OTPLayers, OTPNodeConfig}
import com.cloudant.ziose.scalang.ScalangMeterRegistry
import zio.{&, LogLevel, RIO, Scope, System, Task, ZIO, ZIOAppArgs, ZIOAppDefault}

object Main extends ZIOAppDefault {
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
    config: WorkerConfiguration
  ): RIO[EngineWorker & Node & ActorFactory, AddressableActor[_, _]] = {
    val clouseauCfg: ClouseauConfiguration = config.clouseau.get
    val nodeCfg: OTPNodeConfig             = config.node
    ClouseauSupervisor.start(node, Configuration(clouseauCfg, nodeCfg, capacity(config)))
  }

  private def main(
    workerCfg: WorkerConfiguration,
    metricsRegistry: ScalangMeterRegistry,
    loggerCfg: LogConfiguration
  ): RIO[Scope & EngineWorker & Node & ActorFactory, Unit] = {
    for {
      runtime  <- ZIO.runtime[EngineWorker & Node & ActorFactory]
      otp_node <- ZIO.service[Node]
      remote_node = s"node${workerCfg.node.name.last}@${workerCfg.node.domain}"
      _      <- otp_node.monitorRemoteNode(remote_node)
      worker <- ZIO.service[EngineWorker]
      logLevel = loggerCfg.level.getOrElse(LogLevel.Debug)
      node       <- ZIO.succeed(new ClouseauNode()(runtime, worker, metricsRegistry, logLevel))
      supervisor <- startSupervisor(node, workerCfg)
      _          <- ZIO.addFinalizer(worker.shutdown *> supervisor.shutdown *> otp_node.shutdown)
      _          <- supervisor.awaitShutdown
    } yield ()
  }

  private val workerId: Int = 1
  private val engineId: Int = 1

  private def capacity(workerCfg: WorkerConfiguration) = {
    workerCfg.capacity.getOrElse(CapacityConfiguration())
  }

  private def app(
    workerCfg: WorkerConfiguration,
    metricsRegistry: ScalangMeterRegistry,
    loggerCfg: LogConfiguration
  ): Task[Unit] = {
    val node = workerCfg.node
    val name = s"${node.name}@${node.domain}"
    for {
      _ <- ZIO.logInfo("Clouseau running as " + name)
      _ <- ZIO
        .scoped(main(workerCfg, metricsRegistry, loggerCfg))
        .provide(OTPLayers.nodeLayers(engineId, workerId, node))
    } yield ()
  }

  override def run: RIO[ZIOAppArgs & Scope, Unit] = (
    for {
      appCfg  <- ZIO.service[AppCfg]
      nodeIdx <- getNodeIdx
      workerCfg       = appCfg.config(nodeIdx)
      loggerCfg       = appCfg.logger
      metricsRegistry = ClouseauMetrics.makeRegistry
      metricsLayer    = ClouseauMetrics.makeLayer(metricsRegistry)
      _ <- ZIO
        .scoped(app(workerCfg, metricsRegistry, loggerCfg))
        .provide(
          LoggerFactory.loggerDefault(loggerCfg),
          metricsLayer
        )
    } yield ()
  ).provideSome[ZIOAppArgs](AppCfg.layer)
}
