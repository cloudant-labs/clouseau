package com.cloudant.ziose.core

import zio._

sealed trait EngineWorkerError            extends Exception
case class NameAlreadyInUse(name: String) extends EngineWorkerError

trait EngineWorker extends EnqueueWithId[Engine.WorkerId, MessageEnvelope] {
  type Context <: ProcessContext
  val exchange: EngineWorkerExchange
  def acquire: UIO[Unit]
  def release: UIO[Unit]
  def spawn[A <: Actor](
    builder: ActorBuilder.Sealed[A]
  ): ZIO[Any with Node, _ <: Node.Error, AddressableActor[A, _ <: ProcessContext]]
  def kind: ZIO[EngineWorker, Nothing, String]
  override def awaitShutdown(implicit trace: Trace): UIO[Unit]             = exchange.awaitShutdown
  def isShutdown(implicit trace: Trace): UIO[Boolean]                      = exchange.isShutdown
  def shutdown(implicit trace: Trace): UIO[Unit]                           = exchange.shutdown
  def offer(msg: MessageEnvelope)(implicit trace: zio.Trace): UIO[Boolean] = exchange.offer(msg)
  def offerAll[A1 <: MessageEnvelope](as: Iterable[A1])(implicit trace: zio.Trace): UIO[zio.Chunk[A1]] = {
    exchange.offerAll(as)
  }
  def size(implicit trace: zio.Trace): UIO[Int] = exchange.size
  def capacity: Int                             = exchange.capacity
}

object EngineWorker {
  def spawn[A <: Actor: Tag](
    builder: ActorBuilder.Sealed[A]
  ): ZIO[EngineWorker with Node, _ <: Node.Error, AddressableActor[A, _ <: ProcessContext]] = {
    ZIO.environmentWithZIO[EngineWorker](_.get.spawn(builder))
  }
  def kind[C <: ProcessContext: Tag]: ZIO[EngineWorker, Nothing, String] = {
    ZIO.environmentWithZIO[EngineWorker](_.get.kind)
  }
}
