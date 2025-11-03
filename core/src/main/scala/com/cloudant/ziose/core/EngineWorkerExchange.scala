package com.cloudant.ziose.core

import com.cloudant.ziose.macros.CheckEnv
import zio.{Scope, Trace, UIO, ZIO}

class EngineWorkerExchange private (
  exchange: Exchange[Address, MessageEnvelope, ForwardWithId[Address, MessageEnvelope]]
) extends Exchange.WithConstructor[Address, MessageEnvelope, ForwardWithId[Address, MessageEnvelope]] {
  def info(key: Address): UIO[Option[ProcessInfo]] = {
    for {
      maybeActor <- getActor(key)
      i <- maybeActor match {
        case Some(actor) => ProcessInfo.from(actor).map(Some(_))
        case None        => ZIO.succeed(None)
      }
    } yield i
  }

  def actorMeters[A <: Actor](key: Address): UIO[Option[List[ActorMeterInfo]]] = {
    for {
      maybeActor <- getActor(key)
      i <- maybeActor match {
        case Some(actor) => {
          val info = actor.getMeters().map(meter => ActorMeterInfo.fromMeter(actor, meter))
          ZIO.succeed(Some(info))
        }
        case None => ZIO.succeed(None)
      }
    } yield i
  }

  private def getActor(key: Address) = {
    exchange.get(key).asInstanceOf[UIO[Option[AddressableActor[_ <: Actor, _ <: ProcessContext]]]]
  }

  def buildWith(builderFn: Int => ZIO[Any with Scope, Throwable, ForwardWithId[Address, MessageEnvelope]]) = {
    exchange.buildWith(builderFn)
  }

  def buildAndRegisterZIO(
    fn: Int => ZIO[Any with Scope, Throwable, ForwardWithId[Address, MessageEnvelope]]
  ): ZIO[Any with Scope, Throwable, ForwardWithId[Address, MessageEnvelope]] = {
    this
      .buildWith(address => fn(address))
      .asInstanceOf[ZIO[Any with Scope, Throwable, ForwardWithId[Address, MessageEnvelope]]]
  }

  def add(entity: ForwardWithId[Address, MessageEnvelope]): UIO[Unit] = {
    exchange.add(entity)
  }

  def remove(key: Address): ZIO[Any, Nothing, Option[ForwardWithId[Address, MessageEnvelope]]] = {
    exchange.remove(key)
  }

  def isKnown(key: Address): UIO[Boolean] = {
    exchange.isKnown(key)
  }

  def replace(
    entity: ForwardWithId[Address, MessageEnvelope]
  ): ZIO[Any, Nothing, Option[ForwardWithId[Address, MessageEnvelope]]] = {
    exchange.replace(entity)
  }

  def shutdown(implicit trace: Trace): UIO[Unit] = {
    exchange.shutdown
  }
  def forward(msg: MessageEnvelope)(implicit trace: zio.Trace): UIO[Boolean] = {
    exchange.forward(msg)
  }

  def foreach(fn: ForwardWithId[Address, MessageEnvelope] => Unit)(implicit trace: zio.Trace): UIO[Unit] = {
    exchange.foreach(fn)
  }
  def foreachZIO(fn: ForwardWithId[Address, MessageEnvelope] => UIO[Unit])(implicit trace: zio.Trace): UIO[Unit] = {
    exchange.foreachZIO(fn)
  }
  def map[B](fn: ForwardWithId[Address, MessageEnvelope] => B)(implicit trace: zio.Trace): UIO[Iterable[B]] = {
    exchange.map(fn)
  }
  def mapZIO[B](fn: ForwardWithId[Address, MessageEnvelope] => UIO[B])(implicit trace: zio.Trace): UIO[Iterable[B]] = {
    exchange.mapZIO(fn)
  }
  def fold[B](z: B)(op: (B, ForwardWithId[Address, MessageEnvelope]) => B)(implicit trace: zio.Trace): UIO[B] = {
    exchange.fold(z)(op)
  }
  def foldZIO[B](
    z: B
  )(op: (B, ForwardWithId[Address, MessageEnvelope]) => UIO[B])(implicit trace: zio.Trace): UIO[B] = {
    exchange.foldZIO(z)(op)
  }

  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"exchange=$exchange"
  )
}

object EngineWorkerExchange {
  def make[A <: Actor, C <: ProcessContext]() = {
    for {
      exchange <- Exchange.make(EngineWorkerExchange.getKey)
    } yield new EngineWorkerExchange(exchange)
  }

  def getKey(msg: MessageEnvelope) = msg.to
}
