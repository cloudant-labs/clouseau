/*
 * $ sbt "testOnly com.cloudant.ziose.otp.ActorSpec"
 */

package com.cloudant.ziose.otp

import _root_.com.cloudant.ziose.test.helpers
import helpers.Utils
import helpers.Aspects._
import org.junit.runner.RunWith
import zio._
import zio.test.junit._

@RunWith(classOf[ZTestJUnitRunner])
class ActorSpec extends JUnitRunnableSpec {
  val logger      = Utils.logger
  val environment = ZLayer.succeed(Clock.ClockLive) ++ ZLayer.succeed(Random.RandomLive) ++ logger

  val onMessageSuite = suite("Actor onMessage callback")(
    test("testing throwing onMessage") {
      ???
    } @@ needsTest
  )

  val onTerminateSuite = suite("Actor onTerminate callback")(
    test("test catching of Throwable from throwing onMessage") {
      ???
    } @@ needsTest,
    test("do not brutally crash on throwing onTerminate") {
      ???
    } @@ needsTest,
    test("do not call onTerminate second time after throwing onTerminate") {
      ???
    } @@ needsTest
  )

  def spec = suite("Actor callbacks")(onMessageSuite, onTerminateSuite).provideLayer(environment)
}
