/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.ConfigSpec'
 */
package com.cloudant.ziose.clouseau

import org.junit.runner.RunWith
import zio._
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}

import zio.test._
import zio.test.Assertion._
import com.cloudant.ziose.core.Exponent

@RunWith(classOf[ZTestJUnitRunner])
class ConfigSpec extends JUnitRunnableSpec {
  def capacityFixture(key: String, value: Int) = {
    s"""
       |config: [
       |  {
       |    node: {
       |      name: ziose1
       |      domain: 127.0.0.1
       |      cookie: cookie
       |    }
       |    capacity: {
       |      ${key}: ${value}
       |    }
       |  }
       |
       |]
       |""".stripMargin
  }

  def suiteForCapacity(key: String, getter: (CapacityConfiguration) => Option[Exponent]) = {
    def getCapacity(appConfig: AppCfg) = {
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

    val invalidTests = IndexedSeq(
      test("Ensure we return 'InvalidData' error - negative value")(
        for {
          error <- AppCfg.fromHoconString(capacityFixture(key, -1)).flip.exit
        } yield assertTrue(
          error.exists(_.isInstanceOf[Config.Error.InvalidData])
        ) ?? "Expect error of type 'Config.Error.InvalidData'"
          && assertTrue(
            error.exists(_.asInstanceOf[Config.Error.InvalidData].path == Chunk("config", "[0]", "capacity", key))
          ) ?? "Expect error to be for 'config.[0].capacity.${key}' path"
          && assertTrue(
            error.exists(_.toString().contains(s"got '-1'"))
          ) ?? s"Expect error message to include provided value ('-1')"
          && assertTrue(
            error.exists(
              _.toString().contains("Exponent cannot be negative")
            )
          ) ?? "Expect error message to contain hint"
      ),
      test("Ensure we return 'InvalidData' error - zero value")(
        for {
          error <- AppCfg.fromHoconString(capacityFixture(key, 0)).flip.exit
        } yield assertTrue(
          error.exists(_.isInstanceOf[Config.Error.InvalidData])
        ) ?? "Expect error of type 'Config.Error.InvalidData'"
          && assertTrue(
            error.exists(_.asInstanceOf[Config.Error.InvalidData].path == Chunk("config", "[0]", "capacity", key))
          ) ?? "Expect error to be for 'config.[0].capacity.${key}' path"
          && assertTrue(
            error.exists(_.toString().contains(s"got '0'"))
          ) ?? s"Expect error message to include provided value ('0')"
          && assertTrue(
            error.exists(
              _.toString().contains("Exponent cannot be 0")
            )
          ) ?? "Expect error message to contain hint"
      ),
      test("Ensure we return 'InvalidData' error - big exponent (17)")(
        for {
          error <- AppCfg.fromHoconString(capacityFixture(key, 17)).flip.exit
        } yield assertTrue(
          error.exists(_.isInstanceOf[Config.Error.InvalidData])
        ) ?? "Expect error of type 'Config.Error.InvalidData'"
          && assertTrue(
            error.exists(_.asInstanceOf[Config.Error.InvalidData].path == Chunk("config", "[0]", "capacity", key))
          ) ?? "Expect error to be for 'config.[0].capacity.${key}' path"
          && assertTrue(
            error.exists(_.toString().contains(s"got '17'"))
          ) ?? s"Expect error message to include provided value ('17')"
          && assertTrue(
            error.exists(
              _.toString().contains("Exponent cannot be greater than 16")
            )
          ) ?? "Expect error message to contain hint"
      )
    )

    suite(s"configSuite for 'config.capacity.${key}'")(validTests ++ invalidTests)
  }
  def spec: Spec[Any, Throwable] = {
    suite("ConfigSpec")(
      suiteForCapacity("analyzer_exponent", capacity => capacity.analyzer_exponent),
      suiteForCapacity("cleanup_exponent", capacity => capacity.cleanup_exponent),
      suiteForCapacity("exchange_exponent", capacity => capacity.exchange_exponent),
      suiteForCapacity("index_exponent", capacity => capacity.index_exponent),
      suiteForCapacity("init_exponent", capacity => capacity.init_exponent),
      suiteForCapacity("main_exponent", capacity => capacity.main_exponent)
    )
  }
}
