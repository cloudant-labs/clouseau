/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.ConfigSpec'
 */
package com.cloudant.ziose.clouseau

import com.cloudant.ziose.core.Exponent
import com.cloudant.ziose.test.helpers.TestRunner
import org.junit.runner.RunWith
import zio.test.Assertion.{equalTo, isSome}
import zio.test.{assert, assertTrue}
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}
import zio.{Chunk, Config, LogLevel}

@RunWith(classOf[ZTestJUnitRunner])
class ConfigSpec extends JUnitRunnableSpec {
  def capacityFixture(key: String, value: Int = 0, withCapacity: Boolean = true): String = {
    s"""
       |config: [
       |  {
       |    node: {
       |      name: ziose1
       |      domain: 127.0.0.1
       |      cookie: cookie
       |    }
       |    ${if (withCapacity) s"capacity: { $key: $value }".stripMargin else ""}
       |  }
       |]
       |""".stripMargin
  }

  def suiteForCapacity(key: String, getter: CapacityConfiguration => Option[Exponent]) = {
    def getCapacity(appConfig: AppCfg): Option[Exponent] = {
      getter(appConfig.config.head.capacity.get)
    }

    val validTests = (1 to 16).map(idx => {
      test(s"Ensure we can get correct exponent - ${idx}")(
        for {
          config <- AppCfg.fromHoconString(capacityFixture(key, idx))
        } yield assert(getCapacity(config))(
          isSome(equalTo(Exponent(idx)))
        ) ?? s"Expected capacity exponent to be set to ${idx}"
      )
    })

    def testInvalidCapacity(name: String, capacity: Int) = {
      test(s"Ensure we return 'InvalidData' error - $name")(
        for {
          error <- AppCfg.fromHoconString(capacityFixture(key, capacity)).flip.exit
        } yield assertTrue(
          error.exists(_.isInstanceOf[Config.Error.InvalidData])
        ) ?? "Expect error of type 'Config.Error.InvalidData'"
          && assertTrue(
            error.exists(_.asInstanceOf[Config.Error.InvalidData].path == Chunk("config", "[0]", "capacity", key))
          ) ?? "Expect error to be for 'config.[0].capacity.${key}' path"
          && assertTrue(
            error.exists(
              _.toString.contains(s"Exponent must be greater than 0 and less than or equal to 16 (got '$capacity'")
            )
          ) ?? s"Expect error message to include provided value ('$capacity')"
      )
    }

    val invalidTests = List(
      ("negative value", -1),
      ("zero value", 0),
      ("big exponent value", 17)
    ).map { case (name, capacity) =>
      testInvalidCapacity(name, capacity)
    }

    val noCapacityTest = List(
      test(s"No capacity specified in the config")(
        for {
          config <- AppCfg.fromHoconString(capacityFixture(key, withCapacity = false))
        } yield assertTrue(
          getCapacity(config).isEmpty
        ) ?? s"Expected capacity should be None"
      )
    )

    suite(s"configSuite for 'config.capacity.$key'")(
      validTests ++ invalidTests ++ noCapacityTest
    )
  }

  def logLevelFixture(level: String): String = {
    s"""
       |logger {
       |  level: ${level}
       |}
       |config: [
       |  {
       |    node: {
       |      name: ziose1
       |      domain: 127.0.0.1
       |      cookie: cookie
       |    }
       |  }
       |]
       |""".stripMargin
  }

  def suiteForLogLevel(level: LogLevel) = {
    def levelToString(level: LogLevel) = level.label.toLowerCase() match {
      case "warn"    => "warning"
      case "off"     => "none"
      case lowerCase => lowerCase
    }

    val levelLowerCase   = levelToString(level)
    val mixedCase        = levelLowerCase.capitalize
    val trailingSpace    = levelLowerCase + " "
    val leadingSpace     = " " + levelLowerCase
    val levelWithTypo    = levelLowerCase + "typo"
    val expectedLogLevel = s"LogLevel.${levelLowerCase.capitalize}"

    def testLogLevelParsing(name: String, testCase: String) = {
      test(s"Ensure we can parse log levels - $name")(
        for {
          config <- AppCfg.fromHoconString(logLevelFixture(testCase))
        } yield assertTrue(config.logger.level.get == level) ?? s"Expected Some(${expectedLogLevel})"
      )
    }

    suite(s"configSuite for 'logger.level' - '${levelLowerCase}'")(
      testLogLevelParsing("mixed case", mixedCase),
      testLogLevelParsing("leading space", leadingSpace),
      testLogLevelParsing("trailing space", trailingSpace),
      test("Ensure we return 'InvalidData' error - typo")(
        for {
          error <- AppCfg.fromHoconString(logLevelFixture(levelWithTypo)).flip.exit
        } yield assertTrue(
          error.exists(_.isInstanceOf[Config.Error.InvalidData])
        ) ?? "Expect error of type 'Config.Error.InvalidData'"
          && assertTrue(
            error.exists(_.asInstanceOf[Config.Error.InvalidData].path == Chunk("logger", "level"))
          ) ?? "Expect error to be for 'logger.level' path"
          && assertTrue(
            error.exists(_.toString.contains(s"got '${levelWithTypo}'"))
          ) ?? s"Expect error message to include provided value ('${levelWithTypo}')"
          && assertTrue(
            error.exists(_.toString.contains("ALL|FATAL|ERROR|WARNING|INFO|DEBUG|TRACE|NONE"))
          ) ?? "Expect error message to contain hint of supported levels"
      )
    )
  }

  def spec = {
    suite("ConfigSpec")(
      suiteForCapacity("analyzer_exponent", capacity => capacity.analyzer_exponent),
      suiteForCapacity("cleanup_exponent", capacity => capacity.cleanup_exponent),
      suiteForCapacity("index_exponent", capacity => capacity.index_exponent),
      suiteForCapacity("init_exponent", capacity => capacity.init_exponent),
      suiteForCapacity("main_exponent", capacity => capacity.main_exponent),
      suiteForLogLevel(LogLevel.All),
      suiteForLogLevel(LogLevel.Fatal),
      suiteForLogLevel(LogLevel.Error),
      suiteForLogLevel(LogLevel.Warning),
      suiteForLogLevel(LogLevel.Info),
      suiteForLogLevel(LogLevel.Debug),
      suiteForLogLevel(LogLevel.Trace),
      suiteForLogLevel(LogLevel.None)
    )
  }
}

/**
 * ```shell
 * rm artifacts/clouseau_*.jar ; make jartest
 * java -cp artifacts/clouseau_*_test.jar com.cloudant.ziose.clouseau.ConfigSpecMain
 * ```
 */
object ConfigSpecMain {
  def main(args: Array[String]): Unit = {
    TestRunner.runSpec("ConfigSpec", new ConfigSpec().spec)
  }
}
