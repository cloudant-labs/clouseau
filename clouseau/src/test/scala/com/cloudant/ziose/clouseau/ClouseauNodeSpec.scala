/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.ClouseauNodeSpec'
 */
package com.cloudant.ziose.clouseau

import org.junit.runner.RunWith
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}
import zio.test.{Spec, assertTrue}

import com.cloudant.ziose.core

@RunWith(classOf[ZTestJUnitRunner])
class ClouseauNodeSpec extends JUnitRunnableSpec {
  val serviceSpawnSuite: Spec[Any, Throwable] = {
    suite("serviceSpawn")(
      test("Start Echo")(
        for {
          node <- Utils.clouseauNode
          cfg  <- Utils.defaultConfig
          zio  <- EchoService.startZIO(node, "echo", cfg)
        } yield assertTrue(zio.isInstanceOf[core.AddressableActor[_, _]])
      )
    ).provideLayer(Utils.testEnvironment(1, 1, "serviceSpawn"))
  }

  def spec: Spec[Any, Throwable] = {
    suite("ClouseauNodeSpec")(serviceSpawnSuite)
  }
}
