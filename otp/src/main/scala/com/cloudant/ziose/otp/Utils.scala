package com.cloudant.ziose.otp

import zio.{&, TaskLayer, ZLayer}

import com.cloudant.ziose.core.{Engine, EngineWorker, Node, ActorFactory}
import com.cloudant.ziose.otp.{OTPLayers, OTPNodeConfig}

object Utils {
  def testEnvironment(
    engineId: Engine.EngineId,
    workerId: Engine.WorkerId,
    nodeName: String = "test"
  ): TaskLayer[EngineWorker & Node & ActorFactory & OTPNodeConfig] = {
    val nodeCfg = OTPNodeConfig(nodeName, "127.0.0.1", Some("testCookie"))
    OTPLayers.nodeLayers(engineId, workerId, nodeCfg) ++ ZLayer.succeed(nodeCfg)
  }
}
