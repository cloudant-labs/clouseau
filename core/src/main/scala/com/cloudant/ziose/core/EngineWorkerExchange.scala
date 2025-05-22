package com.cloudant.ziose.core

import com.cloudant.ziose.macros.CheckEnv
import zio.{Scope, Trace, UIO, ZIO}

class EngineWorkerExchange private (
  exchange: Exchange[Address, MessageEnvelope, ForwardWithId[Address, MessageEnvelope]]
) extends Exchange.WithConstructor[Address, MessageEnvelope, ForwardWithId[Address, MessageEnvelope]] {

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
