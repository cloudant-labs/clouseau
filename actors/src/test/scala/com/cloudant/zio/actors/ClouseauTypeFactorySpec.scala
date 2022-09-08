/*
ZIOSE_TEST_DEBUG=1 gradle clean :actors:test --tests 'com.cloudant.zio.actors.ClouseauTypeFactorySpec'
*/
package com.cloudant.zio.actors

import _root_.com.ericsson.otp.erlang._;
import com.cloudant.zio.actors.{ClouseauTypeFactory, Codec, OpenIndexMsg, helpers};
import zio.test._
import zio.test.Assertion._
import zio.ZIO

import helpers.Generators

import zio.Random
import zio.ZIO.logDebug

import zio.test.junit.JUnitRunnableSpec
import zio.test.junit.ZTestJUnitRunner
import org.junit.runner.RunWith

import zio.ZLayer
import zio.Clock
import com.cloudant.zio.actors.ClouseauMessage

@RunWith(classOf[ZTestJUnitRunner])
class ClouseauTypeFactorySpec extends JUnitRunnableSpec {
  val logger = if (sys.env contains "ZIOSE_TEST_DEBUG") {
    zio.Runtime.addLogger(zio.ZLogger.default.map(println))
  } else {
    zio.Runtime.addLogger(zio.ZLogger.default.map(_ => null))
  }
  val environment = ZLayer.succeed(Clock.ClockLive) ++ ZLayer.succeed(Random.RandomLive) ++ logger
  def spec = suite("term encoding")(
    test("correctly create closeau type from ETerm") {
      check(Generators.anyMessagePairGen(4)) { case (term, msg) =>
        for {
          _ <- logDebug(term.toString())
          event <- ZIO.succeed(com.cloudant.zio.actors.ClouseauTypeFactory.parse(term))
        } yield assertTrue(event.isDefined) && assertTrue(event.get.isInstanceOf[ClouseauMessage]) && assertTrue(event.get == msg)
      }
    }
  ).provideCustomLayer(environment)
}
