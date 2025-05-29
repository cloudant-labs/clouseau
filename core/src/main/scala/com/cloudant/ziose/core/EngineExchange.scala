package com.cloudant.ziose.core

import com.cloudant.ziose.macros.CheckEnv
import zio.{Scope, Trace, UIO, ZIO}

class EngineExchange(
  exchange: Exchange[Engine.WorkerId, MessageEnvelope, EngineWorker]
) extends Exchange.WithConstructor[Engine.WorkerId, MessageEnvelope, EngineWorker] {

  def buildWith(builderFn: Int => ZIO[Any with Scope, Throwable, EngineWorker]) = {
    exchange.buildWith(builderFn)
  }

  def buildAndRegisterZIO[W <: EngineWorker](
    builderFn: Int => ZIO[Any with Scope, Throwable, W]
  ): ZIO[Any with Scope, Throwable, W] = {
    this.buildWith(builderFn).asInstanceOf[ZIO[Any with Scope, Throwable, W]]
  }

  def buildAndRegister[W <: EngineWorker](builderFn: Int => ZIO[Any with Scope, Throwable, W]): Unit = {
    this.buildWith(builderFn)
  }

  def shutdown(implicit trace: Trace): UIO[Unit] = {
    exchange.shutdown
  }
  def forward(msg: MessageEnvelope)(implicit trace: zio.Trace): UIO[Boolean] = {
    exchange.forward(msg)
  }
  def list                                              = exchange.list
  def get(id: Engine.WorkerId)                          = exchange.get(id)
  def foreach(fn: (EngineWorker) => Unit): UIO[Unit]    = exchange.foreach(fn)
  def map[B](fn: (EngineWorker) => B): UIO[Iterable[B]] = exchange.map(fn)

  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"exchange=$exchange"
  )
}

object EngineExchange {
  def make = {
    for {
      exchange <- Exchange.make[Engine.WorkerId, MessageEnvelope, EngineWorker](EngineExchange.getKey)
    } yield new EngineExchange(exchange)
  }

  def getKey(msg: MessageEnvelope) = msg.workerId
}
