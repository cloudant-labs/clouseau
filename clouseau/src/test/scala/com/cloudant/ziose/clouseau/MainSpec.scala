/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.MainSpec'
 */
package com.cloudant.ziose.clouseau

import com.cloudant.ziose.clouseau.Main.AppCfg
import org.junit.runner.RunWith
import zio.{Config, System}
import zio.test.Assertion.{anything, dies, equalTo, fails, hasMessage, isSubtype, succeeds}
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}
import zio.test.TestSystem.{Data, DefaultData}
import zio.test.{Spec, TestSystem, assert, assertTrue}

import java.io.FileNotFoundException

@RunWith(classOf[ZTestJUnitRunner])
class MainSpec extends JUnitRunnableSpec {
  val getCfgFileSuite: Spec[Any, Nothing] = {
    suite("getCfgFile")(
      test("getCfgFile: Use specified config file if it exists") {
        for {
          file <- Main.getCfgFile(Some("src/test/resources/testApp.conf"))
        } yield assertTrue(file == "src/test/resources/testApp.conf")
      },
      test("getCfgFile: Throws an error if the argument is not a file") {
        for {
          result <- Main.getCfgFile(Some("not_exist.conf")).exit
        } yield assert(result)(
          dies(isSubtype[FileNotFoundException](hasMessage(equalTo("The system cannot find the file specified"))))
        )
      },
      test("getCfgFile: Use default config file if no arguments are provided") {
        for {
          file <- Main.getCfgFile(None)
        } yield assertTrue(file == "app.conf")
      }
    )
  }

  val getConfigSuite: Spec[Any, Config.Error] = {
    suite("getConfig")(
      test("getConfig success: config file exists") {
        for {
          nodes <- Main.getConfig("src/test/resources/testApp.conf")
          node1 = nodes.config.head
          node2 = nodes.config(1)
        } yield assertTrue(
          nodes.config.size == 2,
          node1.node.name == "ziose1",
          node1.node.domain == "127.0.0.1",
          node1.clouseau.get.close_if_idle.contains(false),
          node1.clouseau.get.max_indexes_open.contains(10),
          !node1.clouseau.get.getBoolean("clouseau.count_locks", false),
          node2.node.domain == "bss1.cloudant.com",
          node2.clouseau.get.dir.contains(RootDir("ziose/src")),
          node1.clouseau.get.getString("clouseau.dir", "defaultDir") == "defaultDir",
          node1.clouseau.get.getString("clouseau.dir_class", "defaultDirClass") == "defaultDirClass",
          node1.clouseau.get.getString("clouseau.lock_class", "defaultLockClass") == "defaultLockClass",
          node2.clouseau.get.getString("clouseau.dir", "default") == "ziose/src",
          node2.clouseau.get.getString("clouseau.dir_class", "default") == "com.cloudant.ziose.store.NIOFSDirectory",
          node2.clouseau.get
            .getString("clouseau.lock_class", "default") == "com.cloudant.ziose.store.NativeFSLockFactory",
          node2.clouseau.get.getBoolean("clouseau.count_locks", false)
        )
      },
      test("getConfig success: no cookie in the config file") {
        for {
          result <- Main.getConfig("src/test/resources/testNoCookieApp.conf").exit
        } yield assert(result)(succeeds(isSubtype[AppCfg](anything)))
      },
      test("getConfig failure: malformed config file") {
        for {
          result <- Main.getConfig("src/test/resources/testMalformedApp.conf").exit
        } yield assert(result)(fails(isSubtype[Config.Error](anything)))
      },
      test("Can get logger config") {
        for {
          appConfig <- Main.getConfig("src/test/resources/testApp.conf")
        } yield assertTrue(appConfig.logger.level == Some(zio.LogLevel.Debug))
      }
    )
  }

  val nodeIdxSuite: Spec[Any, Throwable] = {
    suite("nodeIdx")(
      test("default value should be 0") {
        for {
          prop  <- System.property("node")
          index <- Main.getNodeIdx
        } yield assertTrue(prop.isEmpty, index == 0)
      }.provideLayer(TestSystem.live(DefaultData)),
      test("nodeIdx should be 'node number - 1'") {
        for {
          prop  <- System.property("node")
          index <- Main.getNodeIdx
        } yield assertTrue(prop.contains("ziose3"), index == 2)
      }.provideLayer(TestSystem.live(Data(properties = Map("node" -> "ziose3")))),
      test("nodeIdx should be 0 when node number is not in [1 to 3]") {
        for {
          prop  <- System.property("node")
          index <- Main.getNodeIdx
        } yield assertTrue(prop.contains("n4"), index == 0)
      }.provideLayer(TestSystem.live(Data(properties = Map("node" -> "n4")))),
      test("nodeIdx should be 0 when node property don't contain number") {
        for {
          prop  <- System.property("node")
          index <- Main.getNodeIdx
        } yield assertTrue(prop.contains("ziose"), index == 0)
      }.provideLayer(TestSystem.live(Data(properties = Map("node" -> "ziose"))))
    )
  }

  def spec: Spec[Any, Throwable] = {
    suite("MainSpec")(getCfgFileSuite, getConfigSuite, nodeIdxSuite)
  }
}
