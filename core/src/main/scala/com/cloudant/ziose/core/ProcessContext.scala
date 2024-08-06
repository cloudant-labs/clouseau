package com.cloudant.ziose.core

import zio._
import zio.stream.ZStream

trait ProcessContext extends EnqueueWithId[Address, MessageEnvelope] {
  val id: Address // FIXME
  // Only accessed from AddressableActor
  val worker: EngineWorker
  def name: Option[String]
  def self: PID
  def call(msg: MessageEnvelope.Call): ZIO[Node, _ <: Node.Error, MessageEnvelope.Response]
  def cast(msg: MessageEnvelope.Cast): UIO[Unit]
  def send(msg: MessageEnvelope.Send): UIO[Unit]
  def stream: ZStream[Any, Throwable, MessageEnvelope]
  def exit(reason: Codec.ETerm): UIO[Unit]
  def unlink(to: Codec.EPid): UIO[Unit]
  def link(to: Codec.EPid): ZIO[Any, _ <: Node.Error, Unit]
  def monitor(monitored: Address): ZIO[Node, _ <: Node.Error, Codec.ERef]
  def demonitor(ref: Codec.ERef): UIO[Unit]
  def start(): ZIO[Any with zio.Scope, Nothing, Unit]
}
