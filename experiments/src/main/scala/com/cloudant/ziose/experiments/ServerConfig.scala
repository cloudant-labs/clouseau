package com.cloudant.ziose.experiments

import zio.Config

case class ServerConfig(host: String, port: Int)

object ServerConfig {
  val config: Config[ServerConfig] =
    (Config.string("host") ++ Config.int("port")).map { case (host, port) =>
      ServerConfig(host, port)
    }
}
