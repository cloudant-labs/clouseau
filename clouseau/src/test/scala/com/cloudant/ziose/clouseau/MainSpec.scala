/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.MainSpec'
 */
package com.cloudant.ziose.clouseau

import org.junit.runner.RunWith
import zio.test.TestSystem.Data
import zio.test._
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}

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

  val nodeIdxSuite: Spec[Any, Throwable] = {
    suite("nodeIdx")(
      test("default value should be 0") {
        for {
          prop <- System.property("node")
          index = 0
        } yield assertTrue(prop.isEmpty, index == 0)
      }.provideLayer(TestSystem.live(DefaultData)),
      test("nodeIdx should be 'node number - 1'") {
        for {
          prop <- System.property("node")
          index = 2
        } yield assertTrue(prop.contains("ziose3"), index == 2)
      }.provideLayer(TestSystem.live(Data(properties = Map("node" -> "ziose3")))),
      test("nodeIdx should be 0 when node number is not in [1 to 3]") {
        for {
          prop <- System.property("node")
          index = 0
        } yield assertTrue(prop.contains("n4"), index == 0)
      }.provideLayer(TestSystem.live(Data(properties = Map("node" -> "n4")))),
      test("nodeIdx should be 0 when node property don't contain number") {
        for {
          prop <- System.property("node")
          index = 0
        } yield assertTrue(prop.contains("ziose"), index == 0)
      }.provideLayer(TestSystem.live(Data(properties = Map("node" -> "ziose"))))
    )
  }

  def spec: Spec[Any, Throwable] = suite("MainSpec")(getConfigSuite, nodeIdxSuite)
}

/**
 * ```shell
 * rm artifacts/clouseau_*.jar ; make jartest
 * java -cp artifacts/clouseau_*_test.jar com.cloudant.ziose.clouseau.MainSpecMain
 * ```
 */
object MainSpecMain {
  def main(args: Array[String]): Unit = {
    // We cannot test getConfigSuite because it rely on resource files which we don't have when we run from jar
    TestRunner.runSpec("MainSpec", zio.test.suite("MainSpec")(new MainSpec().nodeIdxSuite))
  }
}
