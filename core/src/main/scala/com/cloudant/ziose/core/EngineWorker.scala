package com.cloudant.ziose.core

import zio._

sealed trait EngineWorkerError            extends Exception
case class NameAlreadyInUse(name: String) extends EngineWorkerError

trait EngineWorker extends EnqueueWithId[Engine.WorkerId, MessageEnvelope] {
  type Context <: ProcessContext
  val id: Engine.WorkerId
  val nodeName: Symbol
  val engineId: Engine.EngineId
  val exchange: EngineWorkerExchange
  def acquire: UIO[Unit]
  def release: UIO[Unit]
  def register(entity: EnqueueWithId[Address, MessageEnvelope]): UIO[Unit] = exchange.add(entity)
  def unregister(addr: Address): ZIO[Any, Nothing, Option[EnqueueWithId[Address, MessageEnvelope]]] = {
    exchange.remove(addr)
  }
  def spawn[A <: Actor](
    builder: ActorBuilder.Sealed[A]
  ): ZIO[Node & EngineWorker, _ <: Node.Error, AddressableActor[A, _ <: ProcessContext]]
  def kind: URIO[EngineWorker, String]
  override def awaitShutdown(implicit trace: Trace): UIO[Unit]         = exchange.awaitShutdown
  def isShutdown(implicit trace: Trace): UIO[Boolean]                  = exchange.isShutdown
  def shutdown(implicit trace: Trace): UIO[Unit]                       = exchange.shutdown
  def offer(msg: MessageEnvelope)(implicit trace: Trace): UIO[Boolean] = exchange.offer(msg)
  def offerAll[A1 <: MessageEnvelope](as: Iterable[A1])(implicit trace: Trace): UIO[Chunk[A1]] = {
    exchange.offerAll(as)
  }
  def size(implicit trace: Trace): UIO[Int] = exchange.size
  def capacity: Int                         = exchange.capacity
}

object EngineWorker {
  def spawn[A <: Actor: Tag](
    builder: ActorBuilder.Sealed[A]
  ): ZIO[EngineWorker & Node, _ <: Node.Error, AddressableActor[A, _ <: ProcessContext]] = {
    ZIO.serviceWithZIO[EngineWorker](_.spawn(builder))
  }
  def kind[C <: ProcessContext: Tag]: URIO[EngineWorker, String] = {
    ZIO.serviceWithZIO[EngineWorker](_.kind)
  }
}
