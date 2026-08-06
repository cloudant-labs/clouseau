/*
sbt 'clouseau/runMain com.cloudant.ziose.clouseau.Main'
 */
package com.cloudant.ziose.clouseau

import com.cloudant.ziose.core.{ActorFactory, EngineWorker, Node}
import com.cloudant.ziose.otp.OTPLayers
import com.cloudant.ziose.scalang.ScalangMeterRegistry
import zio.{&, RIO, Scope, Task, ZIO, ZIOAppArgs, ZIOAppDefault, Runtime, RuntimeFlag}

object Main extends ZIOAppDefault {
  override val bootstrap = Runtime.disableFlags(RuntimeFlag.FiberRoots)

  private def main(
    workerCfg: Configuration,
    metricsRegistry: ScalangMeterRegistry,
    loggerCfg: LogConfiguration
  ): RIO[Scope & EngineWorker & Node & ActorFactory, Unit] = {
    for {
      runtime  <- ZIO.runtime[EngineWorker & Node & ActorFactory]
      otp_node <- ZIO.service[Node]
      remote_node = s"node${workerCfg.node.name.last}@${workerCfg.node.domain}"
      _ <- otp_node.monitorRemoteNode(
        remote_node,
        workerCfg.node.pingTimeoutResolved,
        workerCfg.node.pingIntervalResolved
      )
      worker     <- ZIO.service[EngineWorker]
      node       <- ZIO.succeed(new ClouseauNode()(runtime, worker, metricsRegistry, loggerCfg.level))
      supervisor <- ClouseauSupervisor.start(node, workerCfg)
      _          <- ZIO.addFinalizer(worker.shutdown *> supervisor.shutdown *> otp_node.shutdown)
      _          <- supervisor.awaitShutdown
    } yield ()
  }

  private val workerId: Int = 1
  private val engineId: Int = 1

  def app(
    entryPoint: String,
    nodeConfig: Configuration,
    metricsRegistry: ScalangMeterRegistry,
    loggerCfg: LogConfiguration
  ): Task[Unit] = {
    val otpConfig   = nodeConfig.node
    val name        = s"${otpConfig.name}@${otpConfig.domain}"
    val clouseauCfg = nodeConfig.clouseau
    val closeIfIdle = clouseauCfg.close_if_idle
    for {
      _ <- ZIO.when(closeIfIdle) {
        val idleTimeout = clouseauCfg.idle_check_interval_secs
        ZIO.logInfo(s"Idle timeout is enabled and will check the indexer idle status every $idleTimeout seconds")
      }
      _ <- ZIO.logInfo(s"Clouseau running as ${name} from ${entryPoint}")
      _ <- ZIO
        .scoped(main(nodeConfig, metricsRegistry, loggerCfg))
        .provide(OTPLayers.nodeLayers(engineId, workerId, otpConfig))
    } yield ()
  }

  private def patchConfig(config: Configuration): Task[Configuration] =
    for {
      parseResult <- AppCfg.patchClouseauConfig(config.clouseau)
      patchResult <- parseResult.fold(
        err =>
          for {
            _ <- ZIO.logDebug(s"Parse error while reading system properties: $err - ignoring system properties")
          } yield config,
        patchedClouseauConfig => ZIO.succeed(config.copy(clouseau = patchedClouseauConfig))
      )
    } yield patchResult

  override def run: RIO[ZIOAppArgs & Scope, Unit] = (
    for {
      appCfg    <- ZIO.service[AppCfg]
      workerCfg <- patchConfig(appCfg.config(appCfg.configIndex))
      loggerCfg = appCfg.logger
      _ <- ZIO.logInfo(s"Resolved configuration: ${appCfg.copy(config = List(workerCfg))}")
      metricsRegistry = ClouseauMetrics.makeRegistry
      metricsLayer    = ClouseauMetrics.makeLayer(metricsRegistry)
      _ <- ZIO
        .scoped(app("Main", workerCfg, metricsRegistry, loggerCfg))
        .provide(
          LoggerFactory.loggerDefault(loggerCfg),
          metricsLayer
        )
    } yield ()
  ).provideSome[ZIOAppArgs](AppCfg.layer)
}
