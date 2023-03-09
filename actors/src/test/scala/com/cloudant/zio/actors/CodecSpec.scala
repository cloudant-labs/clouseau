/*
 gradle clean :actors:test --tests 'com.cloudant.zio.actors.CodecSpec'

 to debug generated terms use
 ZIOSE_TEST_DEBUG=1 gradle clean :actors:test --tests 'com.cloudant.zio.actors.CodecSpec'

 To run Generators tests use
 ZIOSE_TEST_DEBUG=1 ZIOSE_TEST_Generators=1 gradle clean :actors:test --tests 'com.cloudant.zio.actors.CodecSpec'
 */
package com.cloudant.zio.actors

import Codec._
import com.ericsson.otp.erlang._
import helpers.Generators._
import helpers.Utils._
import org.junit.runner.RunWith
import zio._
import zio.test._
import zio.test.Gen._
import zio.test.junit._
import zio.test.TestAspect._
import zio.ZIO.logDebug

@RunWith(classOf[ZTestJUnitRunner])
class CodecSpec extends JUnitRunnableSpec {
  val logger = if (flag("ZIOSE_TEST_DEBUG")) {
    Runtime.addLogger(ZLogger.default.map(println))
  } else {
    Runtime.addLogger(ZLogger.default.map(_ => null))
  }
  val environment = ZLayer.succeed(Clock.ClockLive) ++ ZLayer.succeed(Random.RandomLive) ++ logger

  def allButPid: Gen[Any, (ETerm, OtpErlangObject)] =
    termP(10, oneOf(stringP, atomP, booleanP, intP, longP))

  def spec: Spec[Any, Any] = suite("term encoding")(
    test("testing list container generators") {
      check(listContainerE(listOf(oneOf(intE, longE)))) { eTerm =>
        assertTrue(eTerm.isInstanceOf[EList])
      }
      check(listContainerO(listOf(oneOf(intO, longO)))) { oTerm =>
        assertTrue(oTerm.isInstanceOf[OtpErlangList])
      }
      check(listContainerP(listOf(oneOf(intP, longP)))) { case (eTerm, oTerm) =>
        assertTrue(eTerm.isInstanceOf[EList])
        assertTrue(oTerm.isInstanceOf[OtpErlangList])
      }
    } @@ ifPropSet("ZIOSE_TEST_Generators"),
    test("testing tuple container generators") {
      check(tupleContainerE(listOf(oneOf(intE, longE)))) { eTerm =>
        assertTrue(eTerm.isInstanceOf[ETuple])
      }
      check(tupleContainerO(listOf(oneOf(intO, longO)))) { oTerm =>
        assertTrue(oTerm.isInstanceOf[OtpErlangTuple])
      }
      check(tupleContainerP(listOf(oneOf(intP, longP)))) { case (eTerm, oTerm) =>
        assertTrue(eTerm.isInstanceOf[ETuple])
        assertTrue(oTerm.isInstanceOf[OtpErlangTuple])
      }
    } @@ ifPropSet("ZIOSE_TEST_Generators"),
    test("testing map container generators") {
      check(mapContainerE(listOf(oneOf(intE, longE)))) { eTerm =>
        assertTrue(eTerm.isInstanceOf[EMap])
      }
      check(mapContainerO(listOf(oneOf(intO, longO)))) { oTerm =>
        assertTrue(oTerm.isInstanceOf[OtpErlangMap])
      }
      check(mapContainerP(listOf(oneOf(intP, longP)))) { case (eTerm, oTerm) =>
        assertTrue(eTerm.isInstanceOf[EMap])
        assertTrue(oTerm.isInstanceOf[OtpErlangMap])
      }
    } @@ ifPropSet("ZIOSE_TEST_Generators"),
    test("codec ETerm") {
      check(anyO(10)) { eTerm =>
        for {
          _ <- logDebug(eTerm.toString)
        } yield assertTrue(true)
      }
    },
    test("circle round trip from ETerm to OtpErlangObject") {
      check(anyP(10)) { case (eTerm, jTerm) =>
        for {
          _ <- logDebug(eTerm.toString)
        } yield assertTrue(eTerm.toOtpErlangObject == jTerm) &&
          assertTrue(toETerm(eTerm.toOtpErlangObject) == eTerm)
      }
    },
    test("circle round trip from OtpErlangObject to ETerm") {
      check(anyP(10)) { case (eTerm, jTerm) =>
        for {
          _ <- logDebug(eTerm.toString)
        } yield assertTrue(toETerm(jTerm) == eTerm) &&
          assertTrue(toETerm(jTerm).toOtpErlangObject == jTerm)
      }
    },
    test("toString should be the same for most ETerm") {
      check(allButPid) { case (eTerm, jTerm) =>
        for {
          _ <- logDebug(eTerm.toString)
          _ <- logDebug(jTerm.toString)
        } yield assertTrue(eTerm.toString == jTerm.toString)
      }
    },
    test("toString should be different for EPid") {
      check(pidP) { case (eTerm, jTerm) =>
        for {
          _ <- logDebug(eTerm.toString)
          _ <- logDebug(jTerm.toString)
        } yield assertTrue(eTerm.toString != jTerm.toString)
      }
    }
  ).provideLayer(environment)
}
