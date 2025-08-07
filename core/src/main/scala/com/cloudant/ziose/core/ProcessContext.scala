package com.cloudant.ziose.core

import zio._
import com.cloudant.ziose.core.Codec.EPid

trait ProcessContext extends ForwardWithId[Address, MessageEnvelope] with WithProcessInfo[Address] {
  val id: Address // FIXME
  // Only accessed from AddressableActor
  val worker: EngineWorker
  def addressFromEPid(epid: EPid) = Address.fromPid(epid, worker.id, worker.nodeName)
  def awaitShutdown(implicit trace: Trace): UIO[Unit]
  def capacity: Int
  def status(): UIO[Map[Symbol, Fiber.Status]]
  def getTags: List[String]
  def setTag(tag: String): Unit
  def name: Option[String]
  def self: PID
  def lookUpName(name: String): UIO[Option[Address]]
  def forkScoped[R, E, A](effect: ZIO[R, E, A]): URIO[R, Fiber.Runtime[E, A]]
  def forkScopedWithFinalizerExit[R, E, A, R1 <: R](
    effect: ZIO[R, E, A],
    finalizer: Exit[Any, Any] => UIO[Any]
  )(implicit trace: Trace): URIO[R, Fiber.Runtime[E, A]]
  def call(msg: MessageEnvelope.Call): ZIO[Node, _ <: Node.Error, MessageEnvelope.Response]
  def cast(msg: MessageEnvelope.Cast): UIO[Unit]
  def send(msg: MessageEnvelope.Send): UIO[Unit]
  def nextEvent: ZIO[Any, Nothing, Option[MessageEnvelope]]
  def exit(reason: Codec.ETerm): UIO[Unit]
  def exit(msg: MessageEnvelope.Exit): UIO[Unit]
  def unlink(to: Codec.EPid): UIO[Unit]
  def unlink(msg: MessageEnvelope.Unlink): UIO[Unit]
  def link(to: Codec.EPid): ZIO[Any, _ <: Node.Error, Unit]
  def link(msg: MessageEnvelope.Link): ZIO[Any, _ <: Node.Error, Unit]
  def monitor(monitored: Address): ZIO[Node, _ <: Node.Error, Codec.ERef]
  def demonitor(ref: Codec.ERef): UIO[Unit]
  def start(actorLoopFiber: Fiber.Runtime[_, _]): ZIO[Any with zio.Scope, Nothing, Unit]
  def onExit(exit: Exit[_, _]): UIO[Unit]
  def onStop(reason: ActorResult): UIO[Unit]

  /*
   * Use it for tests only
   */
  def interruptNamedFiber(fiberName: Symbol)(implicit trace: Trace): UIO[Unit]

}
