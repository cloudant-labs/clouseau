package com.cloudant.ziose.core

import zio._
import zio.stream.ZStream

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
  def stream: ZStream[Any, Throwable, MessageEnvelope]
  def start(scope: Scope): ZIO[Any with Scope, Nothing, Unit]
  def exit(msg: MessageEnvelope.Exit): ZIO[Any with Scope, Nothing, Unit]
  def unlink(to: Codec.EPid): ZIO[Any with Scope, Nothing, Boolean]
  def link(to: Codec.EPid): ZIO[Any with Scope, Nothing, Boolean]
  def monitor(monitored: Address): Codec.ERef
  def demonitor(ref: Codec.ERef): ZIO[Any with Scope, Nothing, Boolean]
  def call(msg: MessageEnvelope.Call)(implicit trace: zio.Trace): ZIO[Node, _ <: Node.Error, MessageEnvelope.Response]
  def cast(msg: MessageEnvelope.Cast)(implicit trace: zio.Trace): UIO[Unit]
  def send(msg: MessageEnvelope.Send)(implicit trace: zio.Trace): UIO[Unit]
}
