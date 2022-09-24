/*
 gradle clean :actors:test --tests 'com.cloudant.zio.actors.CodecSpec'

 to debug generated terms use

 ZIOSE_TEST_DEBUG=1 gradle clean :actors:test --tests 'com.cloudant.zio.actors.CodecSpec'
 */
package com.cloudant.zio.actors

import Codec._
import helpers.Generators
import org.junit.runner.RunWith
import zio._
import zio.test._
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}
import zio.ZIO.logDebug

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
          _ <- logDebug(eTerm.toString)
        } yield assertTrue(eTerm.toOtpErlangObject == jTerm) &&
          assertTrue(toETerm(eTerm.toOtpErlangObject) == eTerm)
      }
    },
    test("circle round trip from OtpErlangObject to ETerm") {
      check(Generators.anyPairGen(4)) { case (eTerm, jTerm) =>
        for {
          _ <- logDebug(eTerm.toString)
        } yield assertTrue(toETerm(jTerm) == eTerm) &&
          assertTrue(toETerm(jTerm).toOtpErlangObject == jTerm)
      }
    },
    test("toString should be the same for most ETerm") {
      check(Generators.anyPairGen(4, withPid = false)) { case (eTerm, jTerm) =>
        for {
          _ <- logDebug(eTerm.toString)
          _ <- logDebug(jTerm.toString)
        } yield assertTrue(eTerm.toString == jTerm.toString)
      }
    },
    test("toString should be different for EPid") {
      check(Generators.pidPairGen) { case (eTerm, jTerm) =>
        for {
          _ <- logDebug(eTerm.toString)
          _ <- logDebug(jTerm.toString)
        } yield assertTrue(eTerm.toString != jTerm.toString)
      }
    }
  ).provideCustomLayer(environment)
}
