package com.cloudant.ziose.core

import zio._

sealed trait EngineWorkerError            extends Exception
case class NameAlreadyInUse(name: String) extends EngineWorkerError

trait EngineWorker extends ForwardWithId[Engine.WorkerId, MessageEnvelope] {
  type Context <: ProcessContext
  val id: Engine.WorkerId
  val nodeName: Symbol
  val engineId: Engine.EngineId
  val exchange: EngineWorkerExchange
  def acquire: UIO[Unit]
  def release: UIO[Unit]
  def register(entity: ForwardWithId[Address, MessageEnvelope]): UIO[Unit] = exchange.add(entity)
  def unregister(addr: Address): ZIO[Any, Nothing, Option[ForwardWithId[Address, MessageEnvelope]]] = {
    exchange.remove(addr)
  }
  def spawn[A <: Actor](
    builder: ActorBuilder.Sealed[A]
  ): ZIO[Node & EngineWorker, _ <: Node.Error, AddressableActor[A, _ <: ProcessContext]]
  def kind: URIO[EngineWorker, String]
  def shutdown(implicit trace: Trace): UIO[Unit]                         = exchange.shutdown
  def forward(msg: MessageEnvelope)(implicit trace: Trace): UIO[Boolean] = exchange.forward(msg)
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
