package com.cloudant.ziose.experiments

import zio.ConfigProvider
import zio._

// The following is imported from yaml
// - ConfigProvider.fromYamlString
import zio.config.yaml._
// The following is imported from typesafe
// - ConfigProvider.fromHoconString
// - ConfigProvider.fromResourcePath
import zio.config.typesafe._

object ReadConfig extends ZIOAppDefault {
  val hoconConfig: String =
    s"""
       |{
       |host: "hocon.mycluster",
       |port: 100
       |}
       |""".stripMargin

  val yamlConfig: String =
    s"""
       |host: "yaml.mycluster"
       |port: 200
       |""".stripMargin

  def run =
    (for {
      configYaml     <- ConfigProvider.fromYamlString(yamlConfig).load(ServerConfig.config)
      configHocon    <- ConfigProvider.fromHoconString(hoconConfig).load(ServerConfig.config)
      configResource <- ConfigProvider.fromResourcePath.load(ServerConfig.config.nested("ServerConfig"))
      yamlProvider     = ConfigProvider.fromYamlString(yamlConfig)
      resourceProvider = ConfigProvider.fromResourcePath.nested("ServerConfig")
      config <- yamlProvider.orElse(resourceProvider).load(ServerConfig.config)
      _      <- Console.printLine("configYaml: " + configYaml)
      _      <- Console.printLine("configHocon: " + configHocon)
      _      <- Console.printLine("configResource: " + configResource)
      _      <- Console.printLine("config: " + config)
    } yield ())
}
