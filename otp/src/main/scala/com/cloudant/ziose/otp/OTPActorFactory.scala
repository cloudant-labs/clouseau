package com.cloudant.ziose.otp

import com.cloudant.ziose.core.ActorFactory
import com.cloudant.ziose.core.Actor
import com.cloudant.ziose.core.ActorBuilder
import com.cloudant.ziose.core.Node
import com.cloudant.ziose.core.AddressableActor
import com.cloudant.ziose.core.ProcessContext
import com.cloudant.ziose.macros.checkEnv
import zio.{UIO, ZIO, ZLayer}

class OTPActorFactory(name: String) extends ActorFactory {
  def acquire: UIO[Unit] = {
    ZIO.logDebug(s"Acquired")
  }
  def release: UIO[Unit] = {
    ZIO.logDebug(s"Released")
  }
  def create[A <: Actor, C <: ProcessContext](
    builder: ActorBuilder.Sealed[A],
    ctx: C
  ): ZIO[Any, _ <: Node.Error, AddressableActor[A, _ <: ProcessContext]] = {
    for {
      _ <- ZIO.succeed(())
      // _ <- ZIO.logDebug(s"Creating new actor (${builder.name})")
    } yield builder.toActor(ctx)
  }

  @checkEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"name=$name"
  )
}

object OTPActorFactory {
  def live(name: String, cfg: OTPNodeConfig): ZLayer[Any, Throwable, ActorFactory] = ZLayer.scoped {
    for {
      _ <- ZIO.logDebug("Constructing")
      service = new OTPActorFactory(name)
      _ <- service.acquire
      _ <- ZIO.addFinalizer(service.release)
      _ <- ZIO.logDebug("Adding to the environment")
    } yield service
  }
}
