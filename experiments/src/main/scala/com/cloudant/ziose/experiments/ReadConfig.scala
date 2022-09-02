package com.cloudant.ziose.experiments

import zio._

object ReadConfig extends ZIOAppDefault {
  def run =
    (for {
      config <- ZIO.service[ServerConfig]
      _ <- Console.printLine(
        "Application Configuration:\n" +
          s"\tHost: ${config.host}\n" +
          s"\tPort: ${config.port}\n"
      )
    } yield ()).provide(ServerConfig.layer)
}
