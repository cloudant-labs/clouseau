package com.cloudant.ziose.experiments

import zio._
import zio.test.TestAspect.silent
import zio.test._
import zio.test.junit.JUnitRunnableSpec
import org.junit.runner.RunWith
import zio.test.junit._

// The following is imported from yaml
// - ConfigProvider.fromYamlString
import zio.config.yaml._
// The following is imported from typesafe
// - ConfigProvider.fromHoconString
// - ConfigProvider.fromResourcePath
import zio.config.typesafe._

import com.cloudant.ziose.experiments.config.{Main => ReadConfig}

@RunWith(classOf[ZTestJUnitRunner])
class ReadConfigSpec extends JUnitRunnableSpec {
  val environment = {
    ZIOAppArgs.empty ++ ZLayer.environment[Scope] ++ bootstrap
  }

  val yamlConfig: String = {
    s"""
       |host: "yaml.mycluster"
       |port: 200
       |""".stripMargin
  }

  val hoconConfig: String = {
    s"""
       |{
       |host: "hocon.mycluster",
       |port: 100
       |}
       |""".stripMargin
  }

  def spec = {
    suite("ServerConfig Tests")(
      test("test fromYAML") {
        for {
          config <- ConfigProvider.fromYamlString(yamlConfig).load(ServerConfig.config)
        } yield assertTrue(
          config.host == "yaml.mycluster" &&
            config.port == 200
        )
      },
      test("fromHOCON") {
        for {
          config <- ConfigProvider.fromHoconString(hoconConfig).load(ServerConfig.config)
        } yield assertTrue(
          config.host == "hocon.mycluster" &&
            config.port == 100
        )
      },
      test("fromResource") {
        for {
          config1 <- ConfigProvider.fromResourcePath.load(ServerConfig.config.nested("ServerConfig"))
          config2 <- ConfigProvider.fromResourcePath.nested("ServerConfig").load(ServerConfig.config)
        } yield assertTrue(
          config1 == config2 &&
            config1.host == "localhost" &&
            config1.port == 1234
        )
      },
      test("fromResource and YAML as a backup") {
        val yamlProvider     = ConfigProvider.fromYamlString(yamlConfig)
        val resourceProvider = ConfigProvider.fromResourcePath.nested("Incomplete")
        for {
          config <- resourceProvider.orElse(yamlProvider).load(ServerConfig.config)
        } yield assertTrue(
          config.host == "yaml.mycluster" &&
            config.port == 4321
        )
      },
      test("test readConfig") {
        for {
          _      <- ReadConfig.run
          output <- TestConsole.output
          expected = {
            List(
              s"configYaml: ServerConfig(yaml.mycluster,200)",
              s"configHocon: ServerConfig(hocon.mycluster,100)",
              s"configResource: ServerConfig(localhost,1234)",
              s"config: ServerConfig(yaml.mycluster,200)"
            ).mkString("\n")
          }
        } yield assertTrue(output.mkString("").contains(expected))
      } @@ silent
    ).provideLayer(environment)
  }
}
