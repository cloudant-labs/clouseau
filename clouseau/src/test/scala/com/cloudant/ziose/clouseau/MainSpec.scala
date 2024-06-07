/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.MainSpec'
 */
package com.cloudant.ziose.clouseau

import org.junit.runner.RunWith
import zio.System
import zio.test.Assertion.{anything, fails, isSubtype}
import zio.test.TestSystem.{Data, DefaultData}
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}
import zio.test.{Spec, TestSystem, assert, assertTrue}

import com.cloudant.ziose.core

import java.io.FileNotFoundException

@RunWith(classOf[ZTestJUnitRunner])
class MainSpec extends JUnitRunnableSpec {
  val getConfigSuite: Spec[Any, FileNotFoundException] = {
    suite("getConfig")(
      test("getConfig success") {
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
          node2.node.domain == "bss1.cloudant.com",
          node2.clouseau.get.dir.contains(RootDir("ziose/src"))
        )
      },
      test("getConfig failure") {
        for {
          result <- Main.getConfig("not_exist.conf").exit
        } yield assert(result)(fails(isSubtype[FileNotFoundException](anything)))
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

  val serviceSpawnSuite: Spec[Any, Throwable] = {
    suite("serviceSpawn")(
      test("Start Echo")(
        for {
          node <- Utils.testClouseauNode
          cfg  <- Utils.testConfiguration
          zio  <- EchoService.startZIO(node, "echo", cfg)
        } yield assertTrue(zio.isInstanceOf[core.AddressableActor[_, _]])
      )
    ).provideLayer(Utils.testEnvironment(1, 1))
  }

  def spec: Spec[Any, Throwable] = {
    suite("MainSpec")(getConfigSuite, nodeIdxSuite, serviceSpawnSuite)
  }
}
