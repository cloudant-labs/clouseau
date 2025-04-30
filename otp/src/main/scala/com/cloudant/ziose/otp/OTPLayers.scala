package com.cloudant.ziose.otp

import zio.{&, TaskLayer}

import com.cloudant.ziose.core.{Engine, EngineWorker, Node, ActorFactory}
import com.cloudant.ziose.core.Exponent

// TODO: I couldn't make it to work (tried in ClouseauEchoExperiment), so maybe we need to remove it.

object OTPLayers {
  def liveEngineWorker(
    engineId: Engine.EngineId,
    workerId: Engine.WorkerId,
    exchangeCapacity: Option[Exponent],
    cfg: OTPNodeConfig
  ) = {
    val name = s"${cfg.name}${engineId}.${workerId}@${cfg.domain}"
    OTPEngineWorker.live(engineId, workerId, name, exchangeCapacity, cfg)
  }
  def liveActorFactory(engineId: Engine.EngineId, workerId: Engine.WorkerId, cfg: OTPNodeConfig) = {
    val name = s"${cfg.name}${engineId}.${workerId}@${cfg.domain}"
    OTPActorFactory.live(name, cfg)
  }
  def liveNode(engineId: Engine.EngineId, workerId: Engine.WorkerId, cfg: OTPNodeConfig) = {
    val name = s"${cfg.name}${engineId}.${workerId}@${cfg.domain}"
    OTPNode.live(name, engineId, workerId, cfg)
  }

  def nodeLayers(
    engineId: Engine.EngineId,
    workerId: Engine.WorkerId,
    exchangeCapacity: Option[Exponent],
    nodeCfg: OTPNodeConfig
  ): TaskLayer[EngineWorker & Node & ActorFactory] = {
    val name    = s"${nodeCfg.name}@${nodeCfg.domain}"
    val factory = OTPActorFactory.live(name, nodeCfg)
    val node    = OTPNode.live(name, engineId, workerId, nodeCfg)
    val worker  = OTPEngineWorker.live(engineId, workerId, name, exchangeCapacity, nodeCfg)
    factory >+> node >+> worker
  }
}
