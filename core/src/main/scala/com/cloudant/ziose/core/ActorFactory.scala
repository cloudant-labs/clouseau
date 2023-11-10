package com.cloudant.ziose.core

import zio._

trait ActorFactory {
  def create[A <: Actor, C <: ProcessContext](
    builder: ActorBuilder.Sealed[A],
    ctx: C
  ): ZIO[Any, _ <: Node.Error, AddressableActor[A, _ <: ProcessContext]]
}

object ActorFactory {
  def create[A <: Actor, C <: ProcessContext](
    builder: ActorBuilder.Sealed[A],
    ctx: C
  ): ZIO[ActorFactory, _ <: Node.Error, AddressableActor[A, _ <: ProcessContext]] = {
    ZIO.environmentWithZIO[ActorFactory](_.get.create(builder, ctx))
  }
}
