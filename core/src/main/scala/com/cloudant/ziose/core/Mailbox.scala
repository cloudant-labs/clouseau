package com.cloudant.ziose.core

import zio._

/*
 * - def stream: - is used by Actor to retrieve messages
 *
 * If we want to call handleMessage for each event we can use
 * the following
 *
 * ```
 * for {
 *   actor <- ...
 *   actorLoop = actor.mbox.stream.mapZIO(handleMessage).runDrain.forever
 *   _ <- actorLoop.forkScoped
 * } yield ()
 * ```
 */

trait Mailbox extends EnqueueWithId[Address, MessageEnvelope] {
  def nextEvent: ZIO[Any, Nothing, Option[MessageEnvelope]]
  def start(scope: Scope): ZIO[Any with Scope, Nothing, Unit]
  def exit(msg: MessageEnvelope.Exit): ZIO[Any with Scope, Nothing, Unit]
  def unlink(to: Codec.EPid): UIO[Unit]
  def link(to: Codec.EPid): ZIO[Any, _ <: Node.Error, Unit]
  def monitor(monitored: Address): ZIO[Node, _ <: Node.Error, Codec.ERef]
  def demonitor(ref: Codec.ERef): UIO[Unit]
  def call(msg: MessageEnvelope.Call)(implicit trace: zio.Trace): ZIO[Node, _ <: Node.Error, MessageEnvelope.Response]
  def cast(msg: MessageEnvelope.Cast)(implicit trace: zio.Trace): UIO[Unit]
  def send(msg: MessageEnvelope.Send)(implicit trace: zio.Trace): UIO[Unit]
}
