package com.cloudant.ziose.otp

import com.cloudant.ziose.core
import core.ActorResult
import core.Codec._
import zio.Cause
import zio.Exit

object OTPError {
  def fromCause(cause: Cause[_], tag: Option[String] = None) = {
    ActorResult.recoverFromCause(cause) match {
      case Some(result)          => encode(result)
      case None if tag.isDefined => ETuple(EAtom("error"), EBinary(tag.get), EBinary(cause.prettyPrint))
      case None                  => ETuple(EAtom("error"), EBinary(cause.prettyPrint))
    }
  }

  def fromExit(exit: Exit[_, _], tag: Option[String] = None) = {
    exit match {
      case Exit.Failure(cause)               => fromCause(cause, tag)
      case Exit.Success(result: ActorResult) => encode(result)
      case Exit.Success(_result)             => EAtom("normal") // ???
    }
  }

  def encode(result: ActorResult, tag: Option[String] = None) = result match {
    case ActorResult.StopWithCause(callback, cause) => {
      cause.failureTraceOption match {
        case Some((failure, stackTrace)) =>
          ETuple(
            EAtom("error"),
            EBinary(callback.toString()),
            EBinary(failure.toString()),
            EBinary(stackTrace.toString())
          )
        case None =>
          ETuple(
            EAtom("error"),
            EBinary(callback.toString()),
            EBinary(cause.prettyPrint)
          )
      }
    }
    case ActorResult.StopWithReasonTerm(reason)   => reason
    case ActorResult.StopWithReasonString(reason) => EBinary(reason)
    case _: ActorResult.Shutdown                  => EAtom("shutdown")
    case _: ActorResult.Stop                      => EAtom("normal")
  }
}
