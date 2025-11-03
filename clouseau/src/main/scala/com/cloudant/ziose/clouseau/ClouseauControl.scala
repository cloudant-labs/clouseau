package com.cloudant.ziose.clouseau

import com.cloudant.ziose.{core, scalang}
import core.{Address, Codec, ProcessInfo, EngineWorker, ActorMeterInfo}
import scalang.{Adapter, Pid}
import com.cloudant.ziose.otp.OTPProcessContext
import com.cloudant.ziose.scalang.Service

class ClouseauControl[F <: scalang.TypeFactory](worker: EngineWorker, factory: F) {
  import ClouseauControl.Error

  def getServiceInfo(pid: Pid)(implicit adapter: Adapter[_, _]): Either[Error, Option[ProcessInfo]] = {
    val worker                   = adapter.ctx.asInstanceOf[OTPProcessContext].worker
    val address                  = Address.fromPid(pid.fromScala, adapter.workerId, adapter.workerNodeName)
    val res: Option[ProcessInfo] = worker.processInfo(address)
    Right(res)
  }

  def getServiceInfo(name: Symbol)(implicit adapter: Adapter[_, _]): Either[Error, Option[ProcessInfo]] = {
    getService(name) match {
      case Right(Some(pid)) => getServiceInfo(pid)
      case Right(None)      => Right(None)
      case Left(error)      => Left(error)
    }
  }

  def getActorMeters(pid: Pid)(implicit adapter: Adapter[_, _]): Either[Error, Option[List[ActorMeterInfo]]] = {
    val worker  = adapter.ctx.asInstanceOf[OTPProcessContext].worker
    val address = Address.fromPid(pid.fromScala, adapter.workerId, adapter.workerNodeName)
    val res     = worker.actorMeters(address)
    Right(res)
  }

  def getActorMeters(name: Symbol)(implicit adapter: Adapter[_, _]): Either[Error, Option[List[ActorMeterInfo]]] = {
    getService(name) match {
      case Right(Some(pid)) => getActorMeters(pid)
      case Right(None)      => Right(None)
      case Left(error)      => Left(error)
    }
  }

  def listServices()(implicit adapter: Adapter[_, _]): Either[Error, Map[Symbol, Any]] = {
    Service.call(Symbol("sup"), Symbol("listChildren")) match {
      case result: Map[_, _] => Right(result.asInstanceOf[Map[Symbol, Any]])
      case error             => Left(Error.InternalError(error))
    }
  }

  def handleServiceInfoCommand(
    id: Any
  )(implicit adapter: Adapter[_, _]): Either[Error, Option[ProcessInfo]] = {
    id match {
      case id: Pid             => getServiceInfo(id)
      case serviceName: Symbol => getServiceInfo(serviceName)
      case other               => Left(Error.InvalidArgumentType("atom() | pid()", other))
    }
  }

  def handleServiceMetersCommand(
    id: Any
  )(implicit adapter: Adapter[_, _]): Either[Error, Option[List[ActorMeterInfo]]] = {
    id match {
      case id: Pid             => getActorMeters(id)
      case serviceName: Symbol => getActorMeters(serviceName)
      case other               => Left(Error.InvalidArgumentType("atom() | pid()", other))
    }
  }

  def handleTop(args: List[_])(implicit adapter: Adapter[_, _]): Either[Error, List[ProcessInfo]] = args match {
    case List(key: Symbol) =>
      collectInfo(key)
    case List(other) =>
      Left(Error.InvalidArgumentType("atom()", other))
    case args: List[_] =>
      Left(Error.InvalidNumerOfArguments(1, args.size))
  }

  def handleTopMeters(args: List[_])(implicit adapter: Adapter[_, _]): Either[Error, List[ActorMeterInfo]] = {
    args match {
      case List((klass: Symbol, meterName: Symbol)) =>
        collectMeters(klass, meterName)
      case List(other) =>
        Left(Error.InvalidArgumentType("{atom(), atom()}", other))
      case args: List[_] =>
        Left(Error.InvalidNumerOfArguments(1, args.size))
    }
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

  def collectMeters(
    klass: Symbol,
    meterName: Symbol
  )(implicit adapter: Adapter[_, _]): Either[Error, List[ActorMeterInfo]] = {
    val worker = adapter.ctx.asInstanceOf[OTPProcessContext].worker
    core.ActorMeterInfo.select(klass, meterName) match {
      case Right(query) =>
        Right(worker.actorMeterInfoTopK(query))
      case Left(error) =>
        Left(Error.InvalidMeterSelector(error))
    }
  }

  private def getService(name: Symbol)(implicit adapter: Adapter[_, _]): Either[Error, Option[Pid]] = {
    Service.call(Symbol("sup"), (Symbol("getChild"), name)) match {
      case pid: Pid           => Right(Some(pid))
      case Symbol("undefine") => Right(None)
      case error              => Left(Error.InternalError(error))
    }
  }

}

object ClouseauControl {
  trait Error extends Throwable {
    def asETerm: Codec.ETerm
  }

  object Error {
    case class InvalidKey(key: Symbol) extends Error {
      def asETerm: Codec.ETerm = Codec.fromScala(this)
    }
    case class InvalidNumerOfArguments(expected: Int, got: Int) extends Error {
      def asETerm: Codec.ETerm = Codec.ETuple(
        Codec.EAtom("invalid_number_of_arguments"),
        Codec.EMap(
          Map(
            Codec.EAtom("expected") -> Codec.ENumber(expected),
            Codec.EAtom("got")      -> Codec.ENumber(got)
          ).asInstanceOf[Map[Codec.ETerm, Codec.ETerm]]
        )
      )
    }
    case class InvalidArgumentType(expected: String, got: Any) extends Error {
      def asETerm: Codec.ETerm = Codec.ETuple(
        Codec.EAtom("invalid_argument_type"),
        Codec.EMap(
          Map(
            Codec.EAtom("expected") -> Codec.EString(expected),
            Codec.EAtom("got")      -> Codec.EString(got.toString())
          ).asInstanceOf[Map[Codec.ETerm, Codec.ETerm]]
        )
      )
    }
    case class InvalidMeterSelector(error: core.ActorMeterInfo.Error) extends Error {
      def asETerm: Codec.ETerm = error.asETerm
    }

    case class InternalError(error: Any) extends Error {
      def asETerm: Codec.ETerm = Codec.fromScala(error)
    }
  }
}
