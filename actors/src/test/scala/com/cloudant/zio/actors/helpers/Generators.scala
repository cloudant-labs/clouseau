package com.cloudant.zio.actors.helpers

import zio.test.{Gen, Sized}
import zio.test.Gen._

import zio.Random
import _root_.com.ericsson.otp.erlang._;
import com.cloudant.zio.actors.Codec._;
import java.math.BigInteger;
import com.cloudant.zio.actors.OpenIndexMsg
import com.cloudant.zio.actors.ClouseauMessage

object Generators {
  def pidGen: Gen[Random with Sized, EPid] =
    for {
      node     <- Gen.alphaNumericString
      id       <- Gen.int(0, 10)
      serial   <- Gen.int(0, 10)
      creation <- Gen.int(0, 10)
    } yield EPid(node, id, serial, creation)
  def openIndexMsgPairGen(depth: Int): Gen[Random with Sized, (ETerm, ClouseauMessage)] =
    for {
      pid          <- pidGen
      path         <- Gen.alphaNumericString
      (options, _) <- anyPairGen(depth)
    } yield (
      ETuple(List(EAtom(Symbol("open")), pid, EString(path), options)),
      OpenIndexMsg(pid, path, options)
    )
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

  def anyMessagePairGen(depth: Int): Gen[Random with Sized, (ETerm, ClouseauMessage)] =
    small { _ =>
      oneOf(
        suspend(openIndexMsgPairGen(depth - 1))
      )
    }
}
