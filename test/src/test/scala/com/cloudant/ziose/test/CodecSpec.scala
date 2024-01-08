/*
 * $ sbt "testOnly com.cloudant.ziose.test.CodecSpec"
 *
 * To debug generated terms use
 * $ sbt -DZIOSE_TEST_DEBUG=true "testOnly com.cloudant.ziose.test.CodecSpec"
 *
 * To run Generators tests use
 * $ sbt -DZIOSE_TEST_DEBUG=true -DZIOSE_TEST_Generators=1 "testOnly com.cloudant.ziose.test.CodecSpec"
 */
package com.cloudant.ziose.test

import com.cloudant.ziose.core.Codec.{EList, EMap, ETerm, ETuple, fromErlang}
import com.cloudant.ziose.test.helpers.Generators._
import com.cloudant.ziose.test.helpers.Utils
import com.ericsson.otp.erlang.{OtpErlangList, OtpErlangMap, OtpErlangObject, OtpErlangTuple}
import org.junit.runner.RunWith
import zio.test.Gen.{listOf, oneOf}
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}
import zio.test.TestAspect.ifPropSet
import zio.test.{Gen, assertTrue, check}
import zio.ZIO.logDebug
import zio.{Clock, Random, ZLayer}

@RunWith(classOf[ZTestJUnitRunner])
class CodecSpec extends JUnitRunnableSpec {
  val logger      = Utils.logger
  val environment = ZLayer.succeed(Clock.ClockLive) ++ ZLayer.succeed(Random.RandomLive) ++ logger

  def allButPid: Gen[Any, (ETerm, OtpErlangObject)] = {
    termP(10, oneOf(stringP, atomP, booleanP, intP, longP))
  }

  val listContainer = suite("list container:")(
    test("testing list container generators (erlang -> scala)") {
      check(listContainerE(listOf(oneOf(intE, longE)))) { eTerm =>
        assertTrue(eTerm.isInstanceOf[EList])
      }
    },
    test("testing list container generators (scala -> erlang)") {
      check(listContainerO(listOf(oneOf(intO, longO)))) { oTerm =>
        assertTrue(oTerm.isInstanceOf[OtpErlangList])
      }
    },
    test("testing list container generators (scala <-> erlang)") {
      check(listContainerP(listOf(oneOf(intP, longP)))) { case (eTerm, oTerm) =>
        assertTrue(eTerm.isInstanceOf[EList])
        assertTrue(oTerm.isInstanceOf[OtpErlangList])
      }
    }
  ) @@ ifPropSet("ZIOSE_TEST_Generators")

  val tupleContainer = suite("tuple container:")(
    test("testing tuple container generators (erlang -> scala)") {
      check(tupleContainerE(listOf(oneOf(intE, longE)))) { eTerm =>
        assertTrue(eTerm.isInstanceOf[ETuple])
      }
    },
    test("testing tuple container generators (scala -> erlang)") {
      check(tupleContainerO(listOf(oneOf(intO, longO)))) { oTerm =>
        assertTrue(oTerm.isInstanceOf[OtpErlangTuple])
      }
    },
    test("testing tuple container generators (scala <-> erlang)") {
      check(tupleContainerP(listOf(oneOf(intP, longP)))) { case (eTerm, oTerm) =>
        assertTrue(eTerm.isInstanceOf[ETuple])
        assertTrue(oTerm.isInstanceOf[OtpErlangTuple])
      }
    }
  )

  val mapContainer = suite("map container:")(
    test("testing map container generators (erlang -> scala)") {
      check(mapContainerE(listOf(oneOf(stringE, longE)))) { eTerm =>
        assertTrue(eTerm.isInstanceOf[EMap])
      }
    },
    test("testing map container generators (scala -> erlang)") {
      check(mapContainerO(listOf(oneOf(stringO, longO)))) { oTerm =>
        assertTrue(oTerm.isInstanceOf[OtpErlangMap])
      }
    },
    test("testing map container generators (scala <-> erlang)") {
      check(mapContainerP(listOf(oneOf(intP, longP)))) { case (eTerm, oTerm) =>
        assertTrue(eTerm.isInstanceOf[EMap])
        assertTrue(oTerm.isInstanceOf[OtpErlangMap])
      }
    }
  )

  val termSuite = suite("term encoding:")(
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
        } yield assertTrue(
          eTerm.toOtpErlangObject == jTerm,
          fromErlang(eTerm.toOtpErlangObject) == eTerm
        )
      }
    },
    test("circle round trip from OtpErlangObject to ETerm") {
      check(anyP(10)) { case (eTerm, jTerm) =>
        for {
          _ <- logDebug(eTerm.toString)
        } yield assertTrue(
          fromErlang(jTerm) == eTerm,
          fromErlang(jTerm).toOtpErlangObject == jTerm
        )
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
  )

  def spec = suite("CodecSpec")(
    listContainer,
    tupleContainer @@ ifPropSet("ZIOSE_TEST_Generators"),
    mapContainer,
    termSuite
  ).provide(environment)
}
