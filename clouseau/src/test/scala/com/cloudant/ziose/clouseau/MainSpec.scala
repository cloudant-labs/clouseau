/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.MainSpec'
 */
package com.cloudant.ziose.clouseau

import org.junit.runner.RunWith
import zio.test.Assertion.{anything, fails, isSubtype}
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}
import zio.test.{Spec, assert, assertTrue}

import java.io.FileNotFoundException

@RunWith(classOf[ZTestJUnitRunner])
class MainSpec extends JUnitRunnableSpec {
  def spec: Spec[Any, FileNotFoundException] = {
    suite("MainSpec")(
      test("getConfig success") {
        for {
          nodes <- Main.getConfig("clouseau/src/test/resources/testApp.conf")
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
}
