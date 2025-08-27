/*
sbt 'clouseau/runMain com.cloudant.ziose.clouseau.TestJarMain'
 */
package com.cloudant.ziose.clouseau

import zio.{&, RIO, Scope, ZIO, ZIOAppArgs, ZIOAppDefault}

object TestJarMain extends ZIOAppDefault {
  override def run: RIO[ZIOAppArgs & Scope, Unit] = (
    for {
      appCfg <- ZIO.service[AppCfg]
      nodeIdx         = 0
      workerCfg       = appCfg.config(nodeIdx)
      loggerCfg       = appCfg.logger
      metricsRegistry = ClouseauMetrics.makeRegistry
      metricsLayer    = ClouseauMetrics.makeLayer(metricsRegistry)
      _ <- ZIO
        .scoped(Main.app("TestJarMain", workerCfg, metricsRegistry, loggerCfg))
        .provide(
          LoggerFactory.loggerDefault(loggerCfg),
          metricsLayer
        )
    } yield ()
  ).provideSome[ZIOAppArgs](AppCfg.layer)
}
