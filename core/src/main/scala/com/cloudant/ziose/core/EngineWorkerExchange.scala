package com.cloudant.ziose.core

import com.cloudant.ziose.macros.checkEnv
import zio.{Queue, Scope, Trace, UIO, ZIO}

class EngineWorkerExchange private (
  exchange: Exchange[Address, MessageEnvelope, EnqueueWithId[Address, MessageEnvelope]]
) extends Exchange.WithConstructor[Address, MessageEnvelope, EnqueueWithId[Address, MessageEnvelope]] {

  def buildWith(builderFn: Int => ZIO[Any with Scope, Throwable, EnqueueWithId[Address, MessageEnvelope]]) = {
    exchange.buildWith(builderFn)
  }

  def buildAndRegisterZIO(
    fn: Int => ZIO[Any with Scope, Throwable, EnqueueWithId[Address, MessageEnvelope]]
  ): ZIO[Any with Scope, Throwable, EnqueueWithId[Address, MessageEnvelope]] = {
    this
      .buildWith(address => fn(address))
      .asInstanceOf[ZIO[Any with Scope, Throwable, EnqueueWithId[Address, MessageEnvelope]]]
  }

  override def awaitShutdown(implicit trace: Trace): UIO[Unit] = {
    exchange.awaitShutdown
  }
  def capacity = exchange.capacity
  def isShutdown(implicit trace: Trace): UIO[Boolean] = {
    exchange.isShutdown
  }
  def shutdown(implicit trace: Trace): UIO[Unit] = {
    exchange.shutdown
  }
  def offer(msg: MessageEnvelope)(implicit trace: zio.Trace): UIO[Boolean] = {
    exchange.offer(msg)
  }
  def offerAll[A1 <: MessageEnvelope](as: Iterable[A1])(implicit trace: zio.Trace): UIO[zio.Chunk[A1]] = {
    exchange.offerAll(as)
  }
  def size(implicit trace: zio.Trace): UIO[Int] = {
    exchange.size
  }
  def run = exchange.run

  @checkEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"exchange=$exchange"
  )
}

object EngineWorkerExchange {
  def make[A <: Actor, C <: ProcessContext](capacity: Int) = {
    for {
      exchange <- Exchange.make(capacity, EngineWorkerExchange.getKey)
    } yield new EngineWorkerExchange(exchange)
  }

  def makeWithQueue[A <: Actor, C <: ProcessContext](queue: Queue[MessageEnvelope]) = {
    for {
      exchange <- Exchange.makeWithQueue(queue, EngineWorkerExchange.getKey)
    } yield new EngineWorkerExchange(exchange)
  }
  def getKey(msg: MessageEnvelope) = msg.to
}
