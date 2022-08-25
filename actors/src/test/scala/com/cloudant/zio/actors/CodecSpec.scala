/*
 gradle clean :actors:test --tests 'com.cloudant.zio.actors.CodecSpec'

 to debug generated terms use

 ZIOSE_TEST_DEBUG=1 gradle clean :actors:test --tests 'com.cloudant.zio.actors.CodecSpec'
 */

package com.cloudant.zio.actors

import com.cloudant.zio.actors.Codec._;

import zio.test._
import com.ericsson.otp.erlang._;
import java.math.BigInteger;

import zio.test.junit.JUnitRunnableSpec
import zio.test.junit.ZTestJUnitRunner
import zio.test.Assertion._
import org.junit.runner.RunWith

import zio.ZLayer
import zio.ZIO.logDebug

import zio.Random
import zio.Clock
import zio.test.{Gen, Sized}
import zio.test.Gen._

object Generators {
  def nonemptyStringGen: Gen[Random with Sized, String] = (string <*> char).map(elems => elems._1 + elems._2)
  def atomPairGen: Gen[Random with Sized, (ETerm, OtpErlangObject)] = nonemptyStringGen.map { xs =>
    (EAtom(Symbol(xs)), new OtpErlangAtom(xs))
  }
  def stringPairGen: Gen[Random with Sized, (ETerm, OtpErlangObject)] = asciiString.map { xs =>
    (EString(xs), new OtpErlangString(xs))
  }

  def longPairGen: Gen[Random with Sized, (ETerm, OtpErlangObject)] = bigInt(Long.MinValue, Long.MaxValue).map { i =>
    (ELong(i), new OtpErlangLong(new BigInteger(i.toString)))
  }

  def listPairGen(depth: Int): Gen[Random with Sized, (ETerm, OtpErlangObject)] =
    if (depth > 0) {
      listOf(anyPairGen(depth - 1)).map { e =>
        val (terms, objects) = e.unzip
        (EList(terms), new OtpErlangList(objects.toArray))
      }
    } else {
      primitivePairGen
    }

  def tuplePairGen(depth: Int): Gen[Random with Sized, (ETerm, OtpErlangObject)] =
    if (depth > 0) {
      listOf(anyPairGen(depth - 1)).map { e =>
        val (terms, objects) = e.unzip
        (ETuple(terms), new OtpErlangTuple(objects.toArray))
      }
    } else {
      primitivePairGen
    }

  /*
   TODO: Add the rest of the types
   */

  def primitivePairGen: Gen[Random with Sized, (ETerm, OtpErlangObject)] = oneOf(
    atomPairGen,
    stringPairGen,
    longPairGen
  )

  def anyPairGen(depth: Int): Gen[Random with Sized, (ETerm, OtpErlangObject)] =
    small { size =>
      if (size > 1)
        oneOf(
          primitivePairGen,
          suspend(listPairGen(depth - 1)),
          suspend(tuplePairGen(depth - 1))
        )
      else
        primitivePairGen
    }
}

@RunWith(classOf[ZTestJUnitRunner])
class CodecSpec extends JUnitRunnableSpec {
  val logger = if (sys.env contains "ZIOSE_TEST_DEBUG") {
    zio.Runtime.addLogger(zio.ZLogger.default.map(println))
  } else {
    zio.Runtime.addLogger(zio.ZLogger.default.map(_ => null))
  }
  val environment = ZLayer.succeed(Clock.ClockLive) ++ ZLayer.succeed(Random.RandomLive) ++ logger
  def spec = suite("term encoding")(
    test("circle round trip") {
      check(Generators.anyPairGen(4)) { case (term, oterm) =>
        for {
          _ <- logDebug(term.toString())
        } yield assertTrue(term.toJava() == oterm)
      }
    }
  ).provideCustomLayer(environment)
}
