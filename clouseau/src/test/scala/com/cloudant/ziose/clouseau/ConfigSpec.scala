/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.ConfigSpec'
 */
package com.cloudant.ziose.clouseau

import com.cloudant.ziose.core.Exponent
import com.cloudant.ziose.test.helpers.TestRunner
import org.junit.runner.RunWith
import pureconfig.error.ConvertFailure
import zio.LogLevel
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}
import zio.test._

@RunWith(classOf[ZTestJUnitRunner])
class ConfigSpec extends JUnitRunnableSpec {
  def spec: Spec[Any, Nothing] = {
    suite("ConfigSpec")(
      suite(s"configSuite for exponent type")(
        test(s"Ensure we can parse lower bound")(
          for {
            config <- AppCfg.fromHoconFile("src/test/resources/testCapacity_LowerBound.conf")
          } yield {
            assertTrue(config.is(_.right).config.head.capacity.main_exponent.is(_.some) == Exponent(1))
          }
        ),
        test(s"Ensure we can parse upper bound")(
          for {
            config <- AppCfg.fromHoconFile("src/test/resources/testCapacity_UpperBound.conf")
          } yield {
            assertTrue(config.is(_.right).config.head.capacity.main_exponent.is(_.some) == Exponent(16))
          }
        ),
        test(s"Ensure we can parse config file when no capacity specified")(
          for {
            config <- AppCfg.fromHoconFile("src/test/resources/testCapacity_NoCapacity.conf")
          } yield assertTrue(
            config.is(_.right).config.head.capacity.main_exponent.isEmpty
          ) ?? s"Expected capacity should be None"
        ),
        test(s"Ensure we return 'InvalidData' error when capacity above upper limit")(
          for {
            error <- AppCfg.fromHoconFile("src/test/resources/testCapacity_Invalid_TooHigh.conf")
          } yield (assertTrue(
            error.is(_.left).head.asInstanceOf[ConvertFailure].path == "config.0.capacity.main_exponent"
          )
            ?? "Expect error to be for 'config.0.capacity.main_exponent' path")
            && assertTrue(
              error.is(_.left).head.description.contains(s"Exponent must be between 1 and 16 inclusive")
            )
        ),
        test(s"Ensure we return 'InvalidData' error when capacity below lower limit")(
          for {
            error <- AppCfg.fromHoconFile("src/test/resources/testCapacity_Invalid_TooLow.conf")
          } yield assertTrue(
            error.is(_.left).head.asInstanceOf[ConvertFailure].path == "config.0.capacity.main_exponent"
          )
            // error.exists(res => res.is(_.left).head.asInstanceOf[ConvertFailure].path == "config.0.capacity.main_exponent"))
            ?? "Expect error to be for 'config.0.capacity.main_exponent' path"
            && (assertTrue(
              error
                .is(_.left)
                .head
                .asInstanceOf[ConvertFailure]
                .reason
                .description
                .contains("Exponent must be between 1 and 16 inclusive")
            ))
            ?? s"Expect error message to include actual value from the file"
        )
      ),
      suite("configSuite for 'logger.level'")(
        test("Ensure we can parse log level ALL - mixed case")(
          for {
            config <- AppCfg.fromHoconFile("src/test/resources/testLogLevel_All.conf")
          } yield assertTrue(
            config.is(_.right).logger.level == LogLevel.All
          ) ?? s"Expected Some(${LogLevel.All.label})"
        ),
        test("Ensure we can parse log level DEBUG - upper case")(
          for {
            config <- AppCfg.fromHoconFile("src/test/resources/testLogLevel_Debug.conf")
          } yield assertTrue(
            config.is(_.right).logger.level == LogLevel.Debug
          ) ?? s"Expected Some(${LogLevel.Debug.label})"
        ),
        test("Ensure we can parse log level DEBUG - upper case")(
          for {
            config <- AppCfg.fromHoconFile("src/test/resources/testLogLevel_Debug.conf")
          } yield assertTrue(
            config.is(_.right).logger.level == LogLevel.Debug
          ) ?? s"Expected Some(${LogLevel.Debug.label})"
        ),
        test("Ensure we can parse log level NONE - lower case")(
          for {
            config <- AppCfg.fromHoconFile("src/test/resources/testLogLevel_None.conf")
          } yield assertTrue(
            config.is(_.right).logger.level == LogLevel.None
          ) ?? s"Expected Some(${LogLevel.None.label})"
        ),
        test("Ensure we return 'InvalidData' error - typo")(
          for {
            error <- AppCfg.fromHoconFile("src/test/resources/testLogLevel_Invalid.conf")
          } yield (
            assertTrue(error.is(_.left).head.asInstanceOf[ConvertFailure].path == "logger.level")
              ?? "Expect error to be for 'logger.level' path"
              && (assertTrue(
                error.is(_.left).head.asInstanceOf[ConvertFailure].description.startsWith("Cannot convert '")
              )
                ?? s"Expect error message to include actual value")
              && assertTrue(
                error
                  .is(_.left)
                  .head
                  .asInstanceOf[ConvertFailure]
                  .description
                  .contains("ALL|FATAL|ERROR|WARNING|INFO|DEBUG|TRACE|NONE")
              )
          ) ?? "Expect error message to contain hint of supported levels"
        )
      )
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
