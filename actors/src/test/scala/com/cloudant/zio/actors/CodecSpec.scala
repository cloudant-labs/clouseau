/*
 gradle clean :actors:test --tests 'com.cloudant.zio.actors.CodecSpec'

 to debug generated terms use

 ZIOSE_TEST_DEBUG=1 gradle clean :actors:test --tests 'com.cloudant.zio.actors.CodecSpec'
 */

package com.cloudant.zio.actors

import com.cloudant.zio.actors.Codec._;
import com.cloudant.zio.actors.helpers;

import zio.test._
import _root_.com.ericsson.otp.erlang._;

import zio.test.junit.JUnitRunnableSpec
import zio.test.junit.ZTestJUnitRunner
import zio.test.Assertion._
import org.junit.runner.RunWith

import zio.ZLayer
import zio.ZIO.logDebug

import zio.Random
import zio.Clock
import helpers.Generators

@RunWith(classOf[ZTestJUnitRunner])
class CodecSpec extends JUnitRunnableSpec {
  val logger = if (sys.env contains "ZIOSE_TEST_DEBUG") {
    zio.Runtime.addLogger(zio.ZLogger.default.map(println))
  } else {
    zio.Runtime.addLogger(zio.ZLogger.default.map(_ => null))
  }
  val environment = ZLayer.succeed(Clock.ClockLive) ++ ZLayer.succeed(Random.RandomLive) ++ logger
  def spec = suite("term encoding")(
    test("circle round trip from ETerm to OtpErlangObject") {
      check(Generators.anyPairGen(4)) { case (eTerm, jTerm) =>
        for {
          _ <- logDebug(eTerm.toString())
        } yield assertTrue(eTerm.toJava() == jTerm)
      }
    },
    test("circle round trip from OtpErlangObject to ETerm") {
      check(Generators.anyPairGen(4)) { case (eTerm, jTerm) =>
        for {
          _ <- logDebug(eTerm.toString())
        } yield assertTrue(fromJava(jTerm) == eTerm)
      }
    }

  ).provideCustomLayer(environment)
}
