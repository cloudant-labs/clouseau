package com.cloudant.ziose.clouseau

import com.cloudant.ziose.{core, scalang}
import core.{ProcessInfo, EngineWorker}
import scalang.Adapter
import com.cloudant.ziose.otp.OTPProcessContext

class ClouseauControl[F <: scalang.TypeFactory](worker: EngineWorker, factory: F) {
  import ClouseauControl.Error

  def handleTop(args: List[_])(implicit adapter: Adapter[_, _]): Either[Error, List[ProcessInfo]] = args match {
    case List(key: Symbol) =>
      collectInfo(key)
    case List(other) =>
      Left(Error.InvalidArgumentType("atom()", other))
    case args: List[_] =>
      Left(Error.InvalidNumerOfArguments(1, args.size))
  }

  def collectInfo(valueKey: Symbol)(implicit adapter: Adapter[_, _]): Either[Error, List[ProcessInfo]] = {
    val worker = adapter.ctx.asInstanceOf[OTPProcessContext].worker
    core.ProcessInfo.valueFun(valueKey) match {
      case Some(valueFun) =>
        Right(worker.processInfoTopK(valueFun))
      case None =>
        Left(Error.InvalidKey(valueKey))
    }
  }
}

object ClouseauControl {
  trait Error extends Throwable

  object Error {
    case class InvalidKey(key: Symbol)                          extends Error
    case class InvalidNumerOfArguments(expected: Int, got: Int) extends Error
    case class InvalidArgumentType(expected: String, got: Any)  extends Error
  }
}
