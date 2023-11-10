/*
 * $ sbt "testOnly com.cloudant.ziose.core.CodecSpec"
 *
 * To debug generated terms use
 * $ sbt -DZIOSE_TEST_DEBUG=true "testOnly com.cloudant.ziose.test.CodecSpec"
 *
 * To run Generators tests use
 * $ sbt -DZIOSE_TEST_DEBUG=true -DZIOSE_TEST_Generators=1 "testOnly com.cloudant.ziose.test.CodecSpec"
 */
package com.cloudant.ziose.test

import com.cloudant.ziose.core.Codec._
import com.ericsson.otp.erlang._
import com.cloudant.ziose.test.helpers.Utils
import helpers.Generators._
import org.junit.runner.RunWith
import zio._
import zio.test._
import zio.test.Gen._
import zio.test.junit._
import zio.test.TestAspect._
import zio.ZIO.logDebug

@RunWith(classOf[ZTestJUnitRunner])
class CodecSpec extends JUnitRunnableSpec {
  val logger      = Utils.logger
  val environment = ZLayer.succeed(Clock.ClockLive) ++ ZLayer.succeed(Random.RandomLive) ++ logger

  def allButPid: Gen[Any, (ETerm, OtpErlangObject)] = {
    termP(10, oneOf(stringP, atomP, booleanP, intP, longP))
  }

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
    test("testing map container generators (erlang -> scala)") {
      check(mapContainerE(listOf(oneOf(intE, longE)))) { eTerm =>
        assertTrue(eTerm.isInstanceOf[EMap])
      }
    } @@ ifPropSet("ZIOSE_TEST_Generators"),
    test("testing map container generators (scala -> erlang)") {
      check(mapContainerO(listOf(oneOf(intO, longO)))) { oTerm =>
        assertTrue(oTerm.isInstanceOf[OtpErlangMap])
      }
    } @@ ifPropSet("ZIOSE_TEST_Generators"),
    test("testing map container generators (scala <-> erlang)") {
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
          assertTrue(fromErlang(eTerm.toOtpErlangObject) == eTerm)
      }
    },
    test("circle round trip from OtpErlangObject to ETerm") {
      check(anyP(10)) { case (eTerm, jTerm) =>
        for {
          _ <- logDebug(eTerm.toString)
        } yield assertTrue(fromErlang(jTerm) == eTerm) &&
          assertTrue(fromErlang(jTerm).toOtpErlangObject == jTerm)
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
