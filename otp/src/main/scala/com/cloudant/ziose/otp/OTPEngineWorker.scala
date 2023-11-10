package com.cloudant.ziose.otp

import zio._
import com.cloudant.ziose.core.Engine
import com.cloudant.ziose.core.EngineWorker
import com.cloudant.ziose.core.ActorBuilder
import java.util.concurrent.TimeUnit
import com.cloudant.ziose.core.Actor
import com.cloudant.ziose.core.Node
import com.cloudant.ziose.core.EngineWorkerExchange
import com.cloudant.ziose.core.MessageEnvelope

final class OTPEngineWorker private (
  engineId: Engine.EngineId,
  workerId: Engine.WorkerId,
  name: String,
  node: Node,
  val exchange: EngineWorkerExchange
) extends EngineWorker {
  type Context = OTPProcessContext
  val id = workerId
  def acquire: UIO[Unit] = {
    ZIO.debug(s"Acquired OTPEngineWorker ${name}")
  }
  def release: UIO[Unit] = {
    ZIO.debug(s"Released OTPEngineWorker ${name}")
  }
  def spawn[A <: Actor](builder: ActorBuilder.Sealed[A]) = {
    // TODO call .withEngineId and .withWorkerId here???
    for {
      addressable <- ZIO.scoped(node.spawn(builder))
    } yield addressable
  }
  def kind = ZIO.succeed("OTP")
}

object OTPEngineWorker {
  def live(
    engineId: Engine.EngineId,
    workerId: Engine.WorkerId,
    name: String,
    cfg: OTPNodeConfig
  ): ZLayer[Node, Throwable, EngineWorker] = ZLayer {
    for {
      _        <- ZIO.debug("Constructing OTPEngineWorker")
      node     <- ZIO.service[Node]
      queue    <- Queue.bounded[MessageEnvelope](16) // TODO retrieve capacity from config
      exchange <- EngineWorkerExchange.makeWithQueue(queue)
      service = new OTPEngineWorker(engineId, workerId, name, node, exchange)
      _ <- service.acquire
      _ <- ZIO.succeed(ZIO.addFinalizer(service.release))
      _ <- ZIO.debug("Adding OTPEngineWorker to the environment")
    } yield service
  }

  def config(provider: ConfigProvider) = {
    provider.load(OTPNodeConfig.config).cached(Duration(3, TimeUnit.MINUTES))
  }
}
