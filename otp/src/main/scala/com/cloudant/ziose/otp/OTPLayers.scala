package com.cloudant.ziose.otp

import com.cloudant.ziose.core.Engine

// TODO: I couldn't make it to work (tried in ClouseauEchoExperiment), so maybe we need to remove it.

object OTPLayers {
  def liveEngineWorker(engineId: Engine.EngineId, workerId: Engine.WorkerId, cfg: OTPNodeConfig) = {
    val name = s"${cfg.node}${engineId}.${workerId}@${cfg.domain}"
    OTPEngineWorker.live(engineId, workerId, name, cfg)
  }
  def liveActorFactory(engineId: Engine.EngineId, workerId: Engine.WorkerId, cfg: OTPNodeConfig) = {
    val name = s"${cfg.node}${engineId}.${workerId}@${cfg.domain}"
    OTPActorFactory.live(name, cfg)
  }
  def liveNode(engineId: Engine.EngineId, workerId: Engine.WorkerId, cfg: OTPNodeConfig) = {
    val name = s"${cfg.node}${engineId}.${workerId}@${cfg.domain}"
    OTPNode.live(name, engineId, workerId, cfg)
  }
}
