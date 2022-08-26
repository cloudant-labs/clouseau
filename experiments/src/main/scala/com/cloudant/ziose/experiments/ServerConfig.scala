package com.cloudant.ziose.experiments

import zio._
import zio.config._
import zio.config.magnolia.descriptor
import zio.config.typesafe.TypesafeConfigSource

case class ServerConfig(host: String, port: Int)

object ServerConfig {
  val layer: ZLayer[Any, ReadError[String], ServerConfig] =
    ZLayer {
      read {
        descriptor[ServerConfig].from(
          TypesafeConfigSource.fromResourcePath.at(PropertyTreePath.$("ServerConfig"))
        )
      }
    }
}
