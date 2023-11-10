package com.cloudant.ziose.experiments.config

// format: off
/**
  * Running from sbt `experiments/runMain com.cloudant.ziose.experiments.config.Main`
  *
  * # Goals of the experiment
  *
  * 1. Demonstrate how we can parse configuration
  *
  * # Context
  *
  * We need to configure properties such as node name and cookie.
  *
  * # Constrains
  *
  * 1. We need to be able to use different sources of configuration to read Clouseau config and facilitate testing.
  * 2. The user of the config shouldn't need to know the location of the configuration object in the tree.
  * So we can have different sections for different nodes.
  *
  * # Solution
  *
  * The solution is rather simple. The `ZIO.config` supports `nested` combinator which allows
  * us to avoid poisoning of the consumer with the knowledge about the location of a section.
  *
  * The `nested` usage is tested in the accompanying spec file.
  **/

import zio.ConfigProvider
import zio._

// The following is imported from yaml
// - ConfigProvider.fromYamlString
import zio.config.yaml._
// The following is imported from typesafe
// - ConfigProvider.fromHoconString
// - ConfigProvider.fromResourcePath
import zio.config.typesafe._

import com.cloudant.ziose.experiments.ServerConfig

object Main extends ZIOAppDefault {
  val hoconConfig: String = {
    s"""
       |{
       |host: "hocon.mycluster",
       |port: 100
       |}
       |""".stripMargin
  }

  val yamlConfig: String = {
    s"""
       |host: "yaml.mycluster"
       |port: 200
       |""".stripMargin
  }

  def run = {
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
}
