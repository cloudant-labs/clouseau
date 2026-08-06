/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.MainSpec'
 */
package com.cloudant.ziose.clouseau

import com.cloudant.ziose.test.helpers.TestRunner
import org.junit.runner.RunWith
import pureconfig.ConfigReader
import zio.test.Assertion.{anything, isSubtype}
import zio.test.TestSystem.Data
import zio.test._
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}
import zio.{Chunk, Task, ZEnvironment, ZIO, ZIOAppArgs}

@RunWith(classOf[ZTestJUnitRunner])
class MainSpec extends JUnitRunnableSpec {
  val getConfigSuite: Spec[Any, Throwable] = {
    suite("readConfig")(
      test("readConfig success: config file exists") {
        for {
          parseResult <- AppCfg.fromHoconFile("src/test/resources/testApp.conf")
          Right(nodes)                   = parseResult
          List(node1Config, node2Config) = nodes.config
        } yield assertTrue(
          node1Config.node.name == "ziose1",
          node1Config.node.domain == "127.0.0.1",
          !node1Config.clouseau.close_if_idle,
          node1Config.clouseau.max_indexes_open == 10,
          node2Config.node.name == "ziose2",
          node2Config.node.domain == "bss1.cloudant.com",
          node2Config.clouseau.count_locks,
          node2Config.clouseau.dir_class == "com.cloudant.ziose.store.NIOFSDirectory",
          node2Config.clouseau.lock_class == "com.cloudant.ziose.store.NativeFSLockFactory",
          node2Config.clouseau.dir == "ziose/src"
        )
      },
      test("getConfig success: config file without cookie") {
        for {
          result <- AppCfg.fromHoconFile("src/test/resources/testNoCookieApp.conf")
        } yield assertTrue(result.isRight)
      },
      test("getConfig failure: malformed config file") {
        for {
          result <- AppCfg.fromHoconFile("src/test/resources/testMalformedApp.conf")
        } yield assertTrue(result.isLeft)
      },
      test("Can get logger config") {
        for {
          appConfig <- AppCfg.fromHoconFile("src/test/resources/testApp.conf")
        } yield assertTrue(appConfig.is(_.right).logger.level == zio.LogLevel.Debug)
      }
    )
  }

  private val defaultClouseauConfig: ClouseauConfiguration = ClouseauConfiguration()

  val expectedDir: String        = defaultClouseauConfig.dir * 2
  val expectedCloseFlag: Boolean = !defaultClouseauConfig.close_if_idle
  val expectedInterval: Int      = defaultClouseauConfig.idle_check_interval_secs - 1
  val expectedTimeout: Long      = defaultClouseauConfig.search_allowed_timeout_msecs - 1

  val patchClouseau: Task[ConfigReader.Result[ClouseauConfiguration]] =
    AppCfg.patchClouseauConfig(defaultClouseauConfig)

  val mergeConfigWithPropsSuite: Spec[Any, Throwable] = {
    suite("Patch config file with system properties")(
      test("Set a string property") {
        for {
          patchResult <- patchClouseau
        } yield assertTrue(patchResult.is(_.right).dir == expectedDir) &&
          assert(expectedDir)(isSubtype[String](anything))
      },
      test("Set a boolean property") {
        for {
          patchResult <- patchClouseau
        } yield assertTrue(patchResult.is(_.right).close_if_idle == expectedCloseFlag) &&
          assert(expectedCloseFlag)(isSubtype[Boolean](anything))
      },
      test("Set an integer property") {
        for {
          patchResult <- patchClouseau
        } yield assertTrue(patchResult.is(_.right).idle_check_interval_secs == expectedInterval) &&
          assert(expectedInterval)(isSubtype[Int](anything))
      },
      test("Set a long property") {
        for {
          patchResult <- patchClouseau
        } yield assertTrue(patchResult.is(_.right).search_allowed_timeout_msecs == expectedTimeout) &&
          assert(expectedTimeout)(isSubtype[Long](anything))
      },
      test("Set multiple properties") {
        for {
          patchResult <- patchClouseau
        } yield assertTrue(patchResult.is(_.right).dir == expectedDir) &&
          assertTrue(patchResult.is(_.right).search_allowed_timeout_msecs == expectedTimeout)
      }
    ).provideLayer(
      TestSystem.live(
        Data(properties =
          Map(
            "clouseau.dir"                          -> expectedDir,
            "clouseau.close_if_idle"                -> expectedCloseFlag.toString,
            "clouseau.idle_check_interval_secs"     -> expectedInterval.toString,
            "clouseau.search_allowed_timeout_msecs" -> expectedTimeout.toString
          )
        )
      )
    )
  }

  val boostrapSuite: Spec[Any, Throwable] =
    suite("Clouseau bootstrap tests")(
      test("Clouseau can start without arguments") {
        assert(())(anything)
      }.provideLayer(AppCfg.layer)
        .provideEnvironment(ZEnvironment(ZIOAppArgs(Chunk()))),
      test("Clouseau can start when config file is set as argument") {
        assert(())(anything)
      }.provideLayer(AppCfg.layer)
        .provideEnvironment(ZEnvironment(ZIOAppArgs(Chunk("src/test/resources/testApp.conf")))),
      test("Clouseau picks up node index from system properties") {
        for {
          config <- ZIO.service[AppCfg]
        } yield assertTrue(config.configIndex == 1)
      }.provideLayer(AppCfg.layer)
        .provideEnvironment(ZEnvironment(ZIOAppArgs(Chunk("src/test/resources/testApp.conf"))))
        .provideLayer(TestSystem.live(Data(properties = Map("node" -> "2")))),
      test("Node index defaults to 0 if not set in system properties") {
        for {
          config <- ZIO.service[AppCfg]
        } yield assertTrue(config.configIndex == 0)
      }.provideLayer(AppCfg.layer)
        .provideEnvironment(ZEnvironment(ZIOAppArgs(Chunk("src/test/resources/testApp.conf")))),
      test("Clouseau can handle conflicting property keys") {
        assert(())(anything)
      }.provideLayer(AppCfg.layer)
        .provideEnvironment(ZEnvironment(ZIOAppArgs(Chunk("src/test/resources/testApp.conf"))))
        .provideLayer(
          TestSystem.live(Data(properties = Map("java.version.date" -> "2026.08.03", "java.version" -> "21.0.2")))
        ),
      test("Clouseau can handle nonexistent property key") {
        assert(())(anything)
      }.provideLayer(AppCfg.layer)
        .provideEnvironment(ZEnvironment(ZIOAppArgs(Chunk("src/test/resources/testApp.conf"))))
        .provideLayer(TestSystem.live(Data(properties = Map("clouseau.magic" -> "0x1234"))))
    )

  def spec: Spec[Any, Throwable] =
    suite("MainSpec")(getConfigSuite, mergeConfigWithPropsSuite, boostrapSuite)
}

/**
 * ```shell
 * rm artifacts/clouseau_*.jar ; make jartest
 * java -cp artifacts/clouseau_*_test.jar com.cloudant.ziose.clouseau.MainSpecMain
 * ```
 */
object MainSpecMain {
  def main(args: Array[String]): Unit = {
    TestRunner.runSpec("MainSpec", zio.test.suite("MainSpec")(new MainSpec().spec))
  }
}
