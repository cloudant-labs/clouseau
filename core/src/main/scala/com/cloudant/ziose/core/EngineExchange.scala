package com.cloudant.ziose.core

import com.cloudant.ziose.macros.checkEnv
import zio.{Queue, Scope, Trace, UIO, ZIO}

class EngineExchange private (
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
  def list = exchange.list
  // def run =
  //   for {
  //     e <- exchange.run
  //     fibers <- exchange.map(worker => {
  //       ZIO.debug(s"starting: ${worker}")
  //       worker.run
  //     })
  //     _ <- ZIO.forkAll(fibers.toList)
  //     _ <- e.join.debug("engine result")
  //   } yield ()
  def get(id: Engine.WorkerId)                          = exchange.get(id)
  def foreach(fn: (EngineWorker) => Unit): UIO[Unit]    = exchange.foreach(fn)
  def map[B](fn: (EngineWorker) => B): UIO[Iterable[B]] = exchange.map(fn)

  @checkEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"exchange=$exchange"
  )
}

object EngineExchange {
  def make(capacity: Int) = {
    for {
      exchange <- Exchange.make[Engine.WorkerId, MessageEnvelope, EngineWorker](capacity, EngineExchange.getKey)
    } yield new EngineExchange(exchange)
  }

  def makeWithQueue(queue: Queue[MessageEnvelope]) = {
    for {
      exchange <- Exchange.makeWithQueue[Engine.WorkerId, MessageEnvelope, EngineWorker](queue, EngineExchange.getKey)
    } yield new EngineExchange(exchange)
  }
  def getKey(msg: MessageEnvelope) = msg.workerId
}
