/*
 * $ sbt "testOnly com.cloudant.ziose.core.MessageEnvelopeSpec"
 */

package com.cloudant.ziose.core

import com.ericsson.otp.erlang

import org.junit.runner.RunWith
import zio._
import zio.test._
import zio.test.Assertion._
import zio.test.junit._

import Codec._
import helpers.TestRunner

@RunWith(classOf[ZTestJUnitRunner])
class MessageEnvelopeSpec extends JUnitRunnableSpec {
  /*
   * Matcher which matches on inner elements of a structure.
   *
   *  Receives a `PartialFunction[Any, Boolean]`
   * The assertion succeeds iff both conditions are satisfied
   *   1. The shape of provided value matches the partial function
   *      (i.e. the function is defined on the value domain)
   *   2. The result of the partial function invocation is `true`
   *
   * ```scala
   * assert(history.get.headOption)(hasShape {
   *   case Some((_, _, reason: String)) =>
   *     reason.contains("OnMessageResult") && reason.contains("HandleCallCBError")
   * }) ?? "reason has to contain 'OnMessageResult' and 'HandleCallCBError'"
   * ```
   */
  def hasShape(shape: PartialFunction[Any, Boolean]) = {
    val matcher: (=> Option[Any]) => Boolean = { matchedValue =>
      shape.lift(matchedValue).getOrElse(false)
    }
    Assertion.assertion("hasShape")(matcher)
  }

  def fromException(e: erlang.OtpErlangException): MessageEnvelope = {
    val dummyPid     = EPid(Symbol(""), 0, 0, 0)
    val dummyAddress = Address.fromName(EAtom("dummyAddress"), 1, Symbol("dummy@node"))
    MessageEnvelope.fromOtpException(e, dummyPid, dummyAddress)
  }

  val exceptionsSuite = suite("exceptionsSuite")(
    test("OtpErlangDecodeException") {
      val reason = fromException(new erlang.OtpErlangDecodeException("Some description")).getPayload
      assert(reason)(isSome) ?? "reason should be available"
      assert(reason)(hasShape {
        case Some(ETuple(EAtom("otp_erlang_decode_exception"), reason: EBinary, trace: EBinary)) =>
          trace.asString.contains("MessageEnvelopeSpec.scala:")
      }) ?? "has to include correct traceback"
      assert(reason)(hasShape {
        case Some(ETuple(EAtom("otp_erlang_decode_exception"), reason: EBinary, trace: EBinary)) =>
          reason.asString == "Some description"
      }) ?? "has to contain expected reason"
    },
    test("OtpErlangRangeException") {
      val reason = fromException(new erlang.OtpErlangRangeException("Some description")).getPayload
      assert(reason)(isSome) ?? "reason should be available"
      assert(reason)(hasShape {
        case Some(ETuple(EAtom("otp_erlang_range_exception"), reason: EBinary, trace: EBinary)) =>
          trace.asString.contains("MessageEnvelopeSpec.scala:")
      }) ?? "has to include correct traceback"
      assert(reason)(hasShape {
        case Some(ETuple(EAtom("otp_erlang_range_exception"), reason: EBinary, trace: EBinary)) =>
          reason.asString == "Some description"
      }) ?? "has to contain expected reason"

    }
  )

  def spec: Spec[Any with Scope, Throwable] = {
    suite("MessageEnvelopeSpec")(
      exceptionsSuite
    )
  }
}

/**
 * ```shell
 * rm artifacts/clouseau_*.jar ; make jartest
 * java -cp artifacts/clouseau_*_test.jar com.cloudant.ziose.core.MessageEnvelopeSpecMain
 * ```
 */
object MessageEnvelopeSpecMain {
  def main(args: Array[String]): Unit = {
    TestRunner.runSpec(
      "MessageEnvelopeSpec",
      new MessageEnvelopeSpec().spec.provideLayer(Scope.default)
    )
  }
}
