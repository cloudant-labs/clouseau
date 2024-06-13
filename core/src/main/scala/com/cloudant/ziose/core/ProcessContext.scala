package com.cloudant.ziose.core

import zio._
import zio.stream.ZStream

trait ProcessContext extends EnqueueWithId[Address, MessageEnvelope] {
  val id: Address // FIXME
  val workerId: Engine.WorkerId
  val engineId: Engine.EngineId
  def name: Option[String]
  def self: PID
  def call(msg: MessageEnvelope.Call): UIO[MessageEnvelope.Response]
  def cast(msg: MessageEnvelope.Cast): UIO[Unit]
  def send(msg: MessageEnvelope.Send): UIO[Unit]
  def stream: ZStream[Any, Throwable, MessageEnvelope]
  def exit(reason: Codec.ETerm): UIO[Unit]
  def unlink(to: Codec.EPid): ZIO[Any with Scope, Nothing, Boolean]
  def link(to: Codec.EPid): ZIO[Any with Scope, Nothing, Boolean]
  def monitor(monitored: Address): Codec.ERef
  def demonitor(ref: Codec.ERef): ZIO[Any with Scope, Nothing, Boolean]
  def start(scope: Scope): ZIO[Any with zio.Scope, Nothing, Unit]
}
