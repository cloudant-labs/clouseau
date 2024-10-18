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

import com.cloudant.ziose.core.Codec._
import com.cloudant.ziose.test.helpers.Generators._
import com.cloudant.ziose.test.helpers.Utils
import com.ericsson.otp.erlang._
import org.junit.runner.RunWith
import zio.test.Gen.{listOf, oneOf}
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}
import zio.test.TestAspect.ifPropSet
import zio.test.{Gen, assertTrue, check}
import zio.ZIO.logDebug
import zio.{Clock, Random, ZLayer}

import java.nio.charset.StandardCharsets

@RunWith(classOf[ZTestJUnitRunner])
class CodecSpec extends JUnitRunnableSpec {
  val logger      = Utils.logger
  val environment = ZLayer.succeed(Clock.ClockLive) ++ ZLayer.succeed(Random.RandomLive) ++ logger

  def allButPid: Gen[Any, (ETerm, OtpErlangObject)] = {
    termP(10, oneOf(stringP, atomP, atomSP, booleanP, intP, refP))
  }

  /*
   * This function is used in round trip test. Unfortunately it is not possible
   * to implement a round trip test involving long. Since on the decoding side
   * we cannot decide between Long and BigInt representation. Therefore we exclude
   * Long and BigInt.
   */
  def allButRef(size: Int): Gen[Any, (ETerm, OtpErlangObject)] = {
    termP(size, oneOf(stringP, atomP, atomSP, booleanP, smallIntP, pidP))
  }

  val listContainer = suite("list container:")(
    test("testing list container generators (erlang -> scala)") {
      check(listContainerE(listOf(intE))) { eTerm =>
        assertTrue(eTerm.isInstanceOf[EList])
      }
    },
    test("testing list container generators (scala -> erlang)") {
      check(listContainerO(listOf(oneOf(intO, longO)))) { oTerm =>
        assertTrue(oTerm.isInstanceOf[OtpErlangList])
      }
    },
    test("testing list container generators (scala <-> erlang)") {
      check(listContainerP(listOf(intP))) { case (eTerm, oTerm) =>
        assertTrue(eTerm.isInstanceOf[EList])
        assertTrue(oTerm.isInstanceOf[OtpErlangList])
      }
    }
  ) @@ ifPropSet("ZIOSE_TEST_Generators")

  val tupleContainer = suite("tuple container:")(
    test("testing tuple container generators (erlang -> scala)") {
      check(tupleContainerE(listOf(intE))) { eTerm =>
        assertTrue(eTerm.isInstanceOf[ETuple])
      }
    },
    test("testing tuple container generators (scala -> erlang)") {
      check(tupleContainerO(listOf(oneOf(intO, longO)))) { oTerm =>
        assertTrue(oTerm.isInstanceOf[OtpErlangTuple])
      }
    },
    test("testing tuple container generators (scala <-> erlang)") {
      check(tupleContainerP(listOf(intP))) { case (eTerm, oTerm) =>
        assertTrue(eTerm.isInstanceOf[ETuple])
        assertTrue(oTerm.isInstanceOf[OtpErlangTuple])
      }
    }
  )

  val mapContainer = suite("map container:")(
    test("testing map container generators (erlang -> scala)") {
      check(mapContainerE(listOf(oneOf(stringE, intE)))) { eTerm =>
        assertTrue(eTerm.isInstanceOf[EMap])
      }
    },
    test("testing map container generators (scala -> erlang) use long") {
      check(mapContainerO(listOf(oneOf(stringO, longO)))) { oTerm =>
        assertTrue(oTerm.isInstanceOf[OtpErlangMap])
      }
    },
    test("testing map container generators (scala -> erlang) use int") {
      check(mapContainerO(listOf(oneOf(stringO, intO)))) { oTerm =>
        assertTrue(oTerm.isInstanceOf[OtpErlangMap])
      }
    },
    test("testing map container generators (scala <-> erlang)") {
      check(mapContainerP(listOf(oneOf(intP, intP)))) { case (eTerm, oTerm) =>
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
    test("equals function of ETerm") {
      check(anyEq(10)) { case (aTerm, bTerm) =>
        for {
          _ <- logDebug(s"${aTerm.toString} == ${bTerm.toString}?")
        } yield assertTrue(aTerm == bTerm)
      }
    },
    test("hashCode function of ETerm") {
      check(anyEq(10)) { case (aTerm, bTerm) =>
        for {
          _ <- logDebug(
            s"${aTerm.toString}.hashCode (${aTerm.hashCode}) == ${bTerm.toString}.hashCode (${bTerm.hashCode})?"
          )
        } yield assertTrue(aTerm.hashCode == bTerm.hashCode)
      }
    },
    test("circle round trip from ETerm to OtpErlangObject") {
      // Exclude Ref because it become different instance on recreation
      check(allButRef(10)) { case (eTerm, jTerm) =>
        for {
          _ <- logDebug(eTerm.toString)
        } yield assertTrue(
          eTerm.toOtpErlangObject == jTerm,
          fromErlang(eTerm.toOtpErlangObject) == eTerm
        )
      }
    },
    test("circle round trip from OtpErlangObject to ETerm") {
      // Exclude Ref because it become different instance on recreation
      check(allButRef(10)) { case (eTerm, jTerm) =>
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

  val listSuite = suite("EList suite:")(
    test("Proper EList construction") {
      val list     = new EList(List(EInt(1), EString("hello"), EBoolean(false)), true)
      val elements = list.elems.toArray
      assertTrue(list.isProper) &&
      assertTrue(EInt(1) == elements(0).asInstanceOf[EInt]) &&
      assertTrue(EString("hello") == elements(1).asInstanceOf[EString]) &&
      assertTrue(EBoolean(false) == elements(2).asInstanceOf[EBoolean])
    },
    test("Proper EList construction via apply of a List") {
      val list     = EList(List(EInt(1), EString("hello"), EBoolean(false)))
      val elements = list.elems.toArray
      assertTrue(list.isProper) &&
      assertTrue(EInt(1) == elements(0).asInstanceOf[EInt]) &&
      assertTrue(EString("hello") == elements(1).asInstanceOf[EString]) &&
      assertTrue(EBoolean(false) == elements(2).asInstanceOf[EBoolean])
    },
    test("Proper EList construction via apply of a variable number of args") {
      val list     = EList(EInt(1), EString("hello"), EBoolean(false))
      val elements = list.elems.toArray
      assertTrue(list.isProper) &&
      assertTrue(EInt(1) == elements(0).asInstanceOf[EInt]) &&
      assertTrue(EString("hello") == elements(1).asInstanceOf[EString]) &&
      assertTrue(EBoolean(false) == elements(2).asInstanceOf[EBoolean])
    },
    test("Proper EList matching untyped args") {
      val list = EList(EInt(1), EString("hello"), EBoolean(false))
      // TODO there must be something better than assertTrue (spec2???)
      val match_result = list match {
        case EList(i, s, b) => Option((i, s, b))
        case _              => None
      }
      assertTrue(match_result.isDefined)
      val (i, s, b) = match_result.get
      assertTrue(EInt(1) == i.asInstanceOf[EInt]) &&
      assertTrue(EString("hello") == s.asInstanceOf[EString]) &&
      assertTrue(EBoolean(false) == b.asInstanceOf[EBoolean])
    },
    test("Proper EList matching typed args") {
      val list = EList(EInt(1), EString("hello"), EBoolean(false))
      // TODO there must be something better than assertTrue (spec2???)
      val match_result = list match {
        case EList(i: EInt, s: EString, b: EBoolean) => Option((i, s, b))
        case _                                       => None
      }
      assertTrue(match_result.isDefined)
      val (i, s, b) = match_result.get
      assertTrue(EInt(1) == i) &&
      assertTrue(EString("hello") == s) &&
      assertTrue(EBoolean(false) == b)
    },
    test("Proper EList matching type") {
      val list = EList(EInt(1), EString("hello"), EBoolean(false))
      // TODO there must be something better than assertTrue (spec2???)
      val match_result = list match {
        case list: EList if list.isProper => Option(list)
        case _                            => None
      }
      assertTrue(match_result.isDefined)
      val elements = match_result.get.elems.toArray
      assertTrue(EInt(1) == elements(0).asInstanceOf[EInt]) &&
      assertTrue(EString("hello") == elements(1).asInstanceOf[EString]) &&
      assertTrue(EBoolean(false) == elements(2).asInstanceOf[EBoolean])
    },
    test("Proper EList doesn't match untyped args of EListImproper") {
      val list = EList(EInt(1), EString("hello"), EBoolean(false))
      // TODO there must be something better than assertTrue (spec2???)
      val match_result = list match {
        case EListImproper(i, s, b) => Option((i, s, b))
        case _                      => None
      }
      assertTrue(match_result.isEmpty)
    },
    test("Proper EList doesn't match typed args") {
      val list = EList(EInt(1), EString("hello"), EBoolean(false))
      // TODO there must be something better than assertTrue (spec2???)
      val match_result = list match {
        case EListImproper(i: EInt, s: EString, b: EBoolean) => Option((i, s, b))
        case _                                               => None
      }
      assertTrue(match_result.isEmpty)
    },
    test("Proper EList doesn't match type") {
      val list = EList(EInt(1), EString("hello"), EBoolean(false))
      // TODO there must be something better than assertTrue (spec2???)
      val match_result = list match {
        case list: EList if !list.isProper => Option(list)
        case _                             => None
      }
      assertTrue(match_result.isEmpty)
    },
    test("Improper EList construction") {
      val list     = new EList(List(EInt(1), EString("hello"), EBoolean(false)), false)
      val elements = list.elems.toArray
      assertTrue(!list.isProper) &&
      assertTrue(EInt(1) == elements(0).asInstanceOf[EInt]) &&
      assertTrue(EString("hello") == elements(1).asInstanceOf[EString]) &&
      assertTrue(EBoolean(false) == elements(2).asInstanceOf[EBoolean])
    },
    test("Improper EList construction via apply of a List") {
      val list     = EListImproper(List(EInt(1), EString("hello"), EBoolean(false)))
      val elements = list.elems.toArray
      assertTrue(!list.isProper) &&
      assertTrue(EInt(1) == elements(0).asInstanceOf[EInt]) &&
      assertTrue(EString("hello") == elements(1).asInstanceOf[EString]) &&
      assertTrue(EBoolean(false) == elements(2).asInstanceOf[EBoolean])
    },
    test("Improper EList construction via apply of a variable number of args") {
      val list     = EListImproper(EInt(1), EString("hello"), EBoolean(false))
      val elements = list.elems.toArray
      assertTrue(!list.isProper) &&
      assertTrue(EInt(1) == elements(0).asInstanceOf[EInt]) &&
      assertTrue(EString("hello") == elements(1).asInstanceOf[EString]) &&
      assertTrue(EBoolean(false) == elements(2).asInstanceOf[EBoolean])
    },
    test("Improper EList matching untyped args") {
      val list = EListImproper(EInt(1), EString("hello"), EBoolean(false))
      // TODO there must be something better than assertTrue (spec2???)
      val match_result = list match {
        case EListImproper(i, s, b) => Option((i, s, b))
        case _                      => None
      }
      assertTrue(match_result.isDefined)
      val (i, s, b) = match_result.get
      assertTrue(EInt(1) == i.asInstanceOf[EInt]) &&
      assertTrue(EString("hello") == s.asInstanceOf[EString]) &&
      assertTrue(EBoolean(false) == b.asInstanceOf[EBoolean])
    },
    test("Improper EList matching typed args") {
      val list = EListImproper(EInt(1), EString("hello"), EBoolean(false))
      // TODO there must be something better than assertTrue (spec2???)
      val match_result = list match {
        case EListImproper(i: EInt, s: EString, b: EBoolean) => Option((i, s, b))
        case _                                               => None
      }
      assertTrue(match_result.isDefined)
      val (i, s, b) = match_result.get
      assertTrue(EInt(1) == i) &&
      assertTrue(EString("hello") == s) &&
      assertTrue(EBoolean(false) == b)
    },
    test("Improper EList matching type") {
      val list = EListImproper(EInt(1), EString("hello"), EBoolean(false))
      // TODO there must be something better than assertTrue (spec2???)
      val match_result = list match {
        case list: EList if !list.isProper => Option(list)
        case _                             => None
      }
      assertTrue(match_result.isDefined)
      val elements = match_result.get.elems.toArray
      assertTrue(EInt(1) == elements(0).asInstanceOf[EInt]) &&
      assertTrue(EString("hello") == elements(1).asInstanceOf[EString]) &&
      assertTrue(EBoolean(false) == elements(2).asInstanceOf[EBoolean])
    },
    test("Improper EList doesn't match untyped args of EListImproper") {
      val list = EListImproper(EInt(1), EString("hello"), EBoolean(false))
      // TODO there must be something better than assertTrue (spec2???)
      val match_result = list match {
        case EList(i, s, b) => Option((i, s, b))
        case _              => None
      }
      assertTrue(match_result.isEmpty)
    },
    test("Improper EList doesn't match typed args") {
      val list = EListImproper(EInt(1), EString("hello"), EBoolean(false))
      // TODO there must be something better than assertTrue (spec2???)
      val match_result = list match {
        case EList(i: EInt, s: EString, b: EBoolean) => Option((i, s, b))
        case _                                       => None
      }
      assertTrue(match_result.isEmpty)
    },
    test("Improper EList doesn't match type") {
      val list = EListImproper(EInt(1), EString("hello"), EBoolean(false))
      // TODO there must be something better than assertTrue (spec2???)
      val match_result = list match {
        case list: EList if list.isProper => Option(list)
        case _                            => None
      }
      assertTrue(match_result.isEmpty)
    }
  )

  val tupleSuite = suite("ETuple suite:")(
    test("ETuple construction") {
      val tuple    = new ETuple(List(EInt(1), EString("hello"), EBoolean(false)))
      val elements = tuple.elems.toArray
      assertTrue(EInt(1) == elements(0).asInstanceOf[EInt]) &&
      assertTrue(EString("hello") == elements(1).asInstanceOf[EString]) &&
      assertTrue(EBoolean(false) == elements(2).asInstanceOf[EBoolean])
    },
    test("ETuple construction via apply of a List") {
      val tuple    = EList(List(EInt(1), EString("hello"), EBoolean(false)))
      val elements = tuple.elems.toArray
      assertTrue(EInt(1) == elements(0).asInstanceOf[EInt]) &&
      assertTrue(EString("hello") == elements(1).asInstanceOf[EString]) &&
      assertTrue(EBoolean(false) == elements(2).asInstanceOf[EBoolean])
    },
    test("ETuple construction via apply of a variable number of args") {
      val tuple    = EList(EInt(1), EString("hello"), EBoolean(false))
      val elements = tuple.elems.toArray
      assertTrue(EInt(1) == elements(0).asInstanceOf[EInt]) &&
      assertTrue(EString("hello") == elements(1).asInstanceOf[EString]) &&
      assertTrue(EBoolean(false) == elements(2).asInstanceOf[EBoolean])
    },
    test("ETuple matching untyped args") {
      val tuple = ETuple(EInt(1), EString("hello"), EBoolean(false))
      // TODO there must be something better than assertTrue (spec2???)
      val match_result = tuple match {
        case ETuple(i, s, b) => Option((i, s, b))
        case _               => None
      }
      assertTrue(match_result.isDefined)
      val (i, s, b) = match_result.get
      assertTrue(EInt(1) == i.asInstanceOf[EInt]) &&
      assertTrue(EString("hello") == s.asInstanceOf[EString]) &&
      assertTrue(EBoolean(false) == b.asInstanceOf[EBoolean])
    },
    test("ETuple matching typed args") {
      val tuple = ETuple(EInt(1), EString("hello"), EBoolean(false))
      // TODO there must be something better than assertTrue (spec2???)
      val match_result = tuple match {
        case ETuple(i: EInt, s: EString, b: EBoolean) => Option((i, s, b))
        case _                                        => None
      }
      assertTrue(match_result.isDefined)
      val (i, s, b) = match_result.get
      assertTrue(EInt(1) == i) &&
      assertTrue(EString("hello") == s) &&
      assertTrue(EBoolean(false) == b)
    },
    test("ETuple matching type") {
      val tuple = ETuple(EInt(1), EString("hello"), EBoolean(false))
      // TODO there must be something better than assertTrue (spec2???)
      val match_result = tuple match {
        case tuple: ETuple => Option(tuple)
        case _             => None
      }
      assertTrue(match_result.isDefined)
      val elements = match_result.get.elems.toArray
      assertTrue(EInt(1) == elements(0).asInstanceOf[EInt]) &&
      assertTrue(EString("hello") == elements(1).asInstanceOf[EString]) &&
      assertTrue(EBoolean(false) == elements(2).asInstanceOf[EBoolean])
    },
    test("ETuple toScala/fromScala for arity 0") {
      val term = ETuple()
      assertTrue(() == toScala(term)) &&
      assertTrue(term == fromScala(()).asInstanceOf[ETuple])
    },
    test("ETuple special case when instead of TupleN we receive our own class") {
      case class MyClass(foo: Int)
      assertTrue(fromScala(MyClass(5)) == ETuple(EAtom("my_class"), EInt(5)))
      case class MyClass5(foo: Int)
      assertTrue(fromScala(MyClass5(5)) == ETuple(EAtom("my_class_5"), EInt(5)))
    },
    test("EBinary is encoded as UTF8 fromScala/toScala") {
      val s: String     = new String("some text here".getBytes(), StandardCharsets.UTF_8)
      val term: EBinary = fromScala(s).asInstanceOf[EBinary]
      assertTrue(s == toScala(term).asInstanceOf[String])
    },
    test("EBinary is encoded as UTF8 constructor") {
      val s: String     = new String("some text here".getBytes(), StandardCharsets.UTF_8)
      val term: EBinary = EBinary(s.getBytes())
      assertTrue(s == toScala(term).asInstanceOf[String])
    },
    test("EBinary is encoded as UTF8 fromScala") {
      val s: String     = new String("some text here".getBytes(), StandardCharsets.UTF_8)
      val term: EBinary = EBinary(s.getBytes())
      assertTrue(term == fromScala(s).asInstanceOf[EBinary])
    },
    test("EString is encoded as UTF8 toScala") {
      val s: String     = new String("some text here".getBytes(), StandardCharsets.UTF_8)
      val term: EString = EString(s)
      assertTrue(s == toScala(term).asInstanceOf[String])
    },
    test("EAtom(Symbol) is encoded as UTF8 fromScala/toScala") {
      val symbol: Symbol = Symbol(new String("some text here".getBytes(), StandardCharsets.UTF_8))
      val term: EAtom    = fromScala(symbol).asInstanceOf[EAtom]
      assertTrue(symbol == toScala(term).asInstanceOf[Symbol])
    },
    test("EAtom(Symbol) is encoded as UTF8 constructor") {
      val symbol: Symbol = Symbol(new String("some text here".getBytes(), StandardCharsets.UTF_8))
      val term: EAtom    = EAtom(symbol)
      assertTrue(symbol == toScala(term).asInstanceOf[Symbol])
    },
    test("toScala: EAtom(String).atom should be Symbol") {
      val str: String = "some text here"
      val term: EAtom = EAtom(str)
      assertTrue(Symbol(str) == toScala(term).asInstanceOf[Symbol])
    },
    test("fromScala(String) -> EBinary while fromScala(Symbol) -> EAtom") {
      val str: String    = "some text here"
      val symbol: Symbol = Symbol(str)
      val eTerm1: ETerm  = fromScala(str)
      val eTerm2: ETerm  = fromScala(symbol)
      assertTrue(eTerm1.isInstanceOf[EBinary]) &&
      assertTrue(eTerm2.isInstanceOf[EAtom])
    }
  )

  def spec = suite("CodecSpec")(
    listContainer,
    tupleContainer @@ ifPropSet("ZIOSE_TEST_Generators"),
    mapContainer,
    termSuite,
    listSuite,
    tupleSuite
  ).provide(environment)
}
