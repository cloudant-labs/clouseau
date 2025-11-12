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

  def processInfoZIO[A <: Actor](addr: Address): UIO[Option[ProcessInfo]] = {
    exchange.info(addr)
  }

  def processInfo[A <: Actor](addr: Address): Option[ProcessInfo] = {
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(processInfoZIO(addr))
        .getOrThrowFiberFailure()
    }.asInstanceOf[Option[ProcessInfo]]
  }

  def actorMetersZIO[A <: Actor](addr: Address): UIO[Option[List[ActorMeterInfo]]] = {
    exchange.actorMeters(addr)
  }

  def actorMeters[A <: Actor](addr: Address): Option[List[ActorMeterInfo]] = {
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(actorMetersZIO(addr))
        .getOrThrowFiberFailure()
    }.asInstanceOf[Option[List[ActorMeterInfo]]]
  }

  def processInfoTopKZIO[A <: Actor](
    valueFun: ProcessInfo => Int
  ): UIO[List[ProcessInfo]] = {
    var acc = TopK[ProcessInfo, Long](10)
    exchange.foreachZIO(it => {
      // we know our exchange holds AddressableActor instances
      val actor = it.asInstanceOf[AddressableActor[A, _ <: ProcessContext]]
      for {
        info <- ProcessInfo.from(actor)
        _ = acc.add(info, valueFun(info))
      } yield ()
    }) *> ZIO.succeed(acc.asList.unzip._1)
  }

  def processInfoTopK(valueFun: ProcessInfo => Int): List[ProcessInfo] = {
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(processInfoTopKZIO(valueFun))
        .getOrThrowFiberFailure()
    }.asInstanceOf[List[ProcessInfo]]
  }

  def actorMeterInfoTopKZIO[A <: Actor](
    query: ActorMeterInfo.Query[Double]
  ): UIO[List[ActorMeterInfo]] = {
    var acc = TopK[ActorMeterInfo, Double](10)
    exchange.foreachZIO(it => {
      // we know our exchange holds AddressableActor instances
      val actor  = it.asInstanceOf[AddressableActor[A, _ <: ProcessContext]]
      val search = query.select(actor)
      val value  = query.run(search)
      for {
        info <- ActorMeterInfo.from(actor, query, value)
        _ = acc.add(info, value)
      } yield ()
    }) *> ZIO.succeed(acc.asList.unzip._1)
  }

  def actorMeterInfoTopK(query: ActorMeterInfo.Query[Double]): List[ActorMeterInfo] = {
    Unsafe.unsafe { implicit unsafe =>
      Runtime.default.unsafe
        .run(actorMeterInfoTopKZIO(query))
        .getOrThrowFiberFailure()
    }.asInstanceOf[List[ActorMeterInfo]]
  }

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
