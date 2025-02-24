package com.cloudant.ziose.core

import zio._

trait ProcessContext extends EnqueueWithId[Address, MessageEnvelope] {
  val id: Address // FIXME
  // Only accessed from AddressableActor
  val worker: EngineWorker
  def status(): UIO[Map[Symbol, Fiber.Status]]
  def name: Option[String]
  def self: PID
  def lookUpName(name: String): UIO[Option[Address]]
  def forkScoped[R, E, A](effect: ZIO[R, E, A]): URIO[R, Fiber.Runtime[E, A]]
  def call(msg: MessageEnvelope.Call): ZIO[Node, _ <: Node.Error, MessageEnvelope.Response]
  def cast(msg: MessageEnvelope.Cast): UIO[Unit]
  def send(msg: MessageEnvelope.Send): UIO[Unit]
  def nextEvent: ZIO[Any, Nothing, Option[MessageEnvelope]]
  def exit(reason: Codec.ETerm): UIO[Unit]
  def exit(msg: MessageEnvelope.Exit): UIO[Unit]
  def unlink(to: Codec.EPid): UIO[Unit]
  def link(to: Codec.EPid): ZIO[Any, _ <: Node.Error, Unit]
  def monitor(monitored: Address): ZIO[Node, _ <: Node.Error, Codec.ERef]
  def demonitor(ref: Codec.ERef): UIO[Unit]
  def start(actorLoopFiber: Fiber.Runtime[_, _]): ZIO[Any with zio.Scope, Nothing, Unit]
  def onExit(exit: Exit[_, _]): UIO[Unit]
  def onStop(reason: ActorResult): UIO[Unit]
}
