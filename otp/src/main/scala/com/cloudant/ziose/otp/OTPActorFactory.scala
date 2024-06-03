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
    ZIO.debug(s"Acquired OTPActorFactory")
  }
  def release: UIO[Unit] = {
    ZIO.debug(s"Released OTPActorFactory")
  }
  def create[A <: Actor, C <: ProcessContext](
    builder: ActorBuilder.Sealed[A],
    ctx: C
  ): ZIO[Any, _ <: Node.Error, AddressableActor[A, _ <: ProcessContext]] = {
    for {
      _ <- ZIO.succeed(())
      // _ <- ZIO.debug(s"OTPActorFactory creating new actor (${builder.name})")
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
      _ <- ZIO.debug("Constructing OTPActorFactory")
      service = new OTPActorFactory(name)
      _ <- service.acquire
      _ <- ZIO.addFinalizer(service.release)
      _ <- ZIO.debug("Adding OTPActorFactory to the environment")
    } yield service
  }
}
