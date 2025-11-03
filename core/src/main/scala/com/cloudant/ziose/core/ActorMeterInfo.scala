package com.cloudant.ziose.core

import zio._

import Codec._

case class ActorMeterInfo(
  pid: PID,
  name: Option[Symbol],
  tags: List[String],
  meterName: Symbol,
  value: Double
) {
  def asETerm = {
    val nameAtom = name.getOrElse(Symbol("none"))
    ETuple(
      EAtom("meter_info"),
      Codec.fromScala(
        Map(
          Symbol("pid")        -> pid.pid,
          Symbol("name")       -> EAtom(nameAtom),
          Symbol("tags")       -> asPrettyPrintedETerm(tags),
          Symbol("meter_name") -> EAtom(meterName),
          Symbol("value")      -> EDouble(value)
        )
      )
    )
  }

  /*
   * This is used for cases where we print terms on the console and want to make it machine-readable.
   * Special handling is done to:
   *   - pid
   *   - binaries
   */
  def asPrettyPrintedETerm: ETerm = {
    val nameAtom = name.getOrElse(Symbol("none"))
    ETuple(
      EAtom("meter_info"),
      Codec.fromScala(
        Map(
          Symbol("pid")        -> EString(pid.pid.toString()),
          Symbol("name")       -> EAtom(nameAtom),
          Symbol("tags")       -> asPrettyPrintedETerm(tags),
          Symbol("meter_name") -> EAtom(meterName),
          Symbol("value")      -> EDouble(value)
        )
      )
    )
  }

  def asPrettyPrintedETerm(term: Any): ETerm = {
    term match {
      case xs: List[_] => EList(xs.map(asPrettyPrintedETerm(_)))
      case xs: Map[_, _] =>
        EMap(xs.map { case (k, v) =>
          asPrettyPrintedETerm(k) -> asPrettyPrintedETerm(v)
        })
      case Symbol(string) => EAtom(string)
      case string: String => EString(string)
      case other          => Codec.fromScala(other)
    }
  }

}

object ActorMeterInfo {
  trait Error extends Throwable {
    def asETerm: Codec.ETerm
  }

  object Error {
    case class InvalidClassName(name: Symbol) extends Error {
      def asETerm: Codec.ETerm = Codec.fromScala(this)
    }
    case class InvalidMeterName(name: Symbol) extends Error {
      def asETerm: Codec.ETerm = Codec.fromScala(this)
    }
    case class InvalidKey(key: Symbol) extends Error {
      def asETerm: Codec.ETerm = Codec.fromScala(this)
    }
  }

  trait Query[V] {
    val meterName: String
    val klass: String
    def select: AddressableActor[_, _] => Metrics.Search = { (actor: AddressableActor[_, _]) =>
      actor.findMeter(meterName).tag("class", klass)
    }
    def run(search: Metrics.Search)(implicit num: Numeric[V]): Double
  }

  class MailboxQuery[V](val meterName: String, val klass: String) extends Query[V] {
    def run(search: Metrics.Search)(implicit num: Numeric[V]): Double = {
      search.counter().count()
    }
  }

  object MailboxQuery {
    def create(meterName: String): Either[Error, ActorMeterInfo.Query[Double]] = {
      if (!Set(Symbol("internal"), Symbol("external"), Symbol("composite")).contains(Symbol(meterName))) {
        Left(Error.InvalidMeterName(Symbol(meterName)))
      } else {
        Right(new MailboxQuery(meterName, "mailbox"))
      }
    }
  }

  def select(klass: Symbol, meterName: Symbol, _key: Option[Symbol] = None) = klass match {
    case Symbol("mailbox") => MailboxQuery.create(meterName.name)
    case _                 => Left(Error.InvalidClassName(klass))
  }

  def from(
    actor: AddressableActor[_ <: Actor, _ <: ProcessContext],
    query: Query[_],
    value: Double
  ): UIO[ActorMeterInfo] = {
    ZIO.succeed(
      ActorMeterInfo(
        actor.self,
        actor.name.map(Symbol(_)),
        query.klass +: actor.getTags,
        Symbol(query.meterName),
        value
      )
    )
  }

  def fromMeter[M <: Metrics.Meter[_]](
    actor: AddressableActor[_ <: Actor, _ <: ProcessContext],
    meter: M
  ): ActorMeterInfo = {
    val klass = meter.getTags().get("class").getOrElse("undefined")
    ActorMeterInfo(
      actor.self,
      actor.name.map(Symbol(_)),
      klass +: actor.getTags,
      meter.getName(),
      meter.getCount().get
    )
  }

}
