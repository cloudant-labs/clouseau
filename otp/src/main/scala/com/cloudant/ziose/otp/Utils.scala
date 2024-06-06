package com.cloudant.ziose.otp

import zio.{&, TaskLayer, ZLayer}

import com.cloudant.ziose.core.{Engine, EngineWorker, Node, ActorFactory}
import com.cloudant.ziose.otp.{OTPLayers, OTPNodeConfig}

object Utils {
  def testEnvironment(
    engineId: Engine.EngineId,
    workerId: Engine.WorkerId
  ): TaskLayer[EngineWorker & Node & ActorFactory & OTPNodeConfig] = {
    val nodeCfg = OTPNodeConfig("test", "127.0.0.1", "testCookie")
    OTPLayers.nodeLayers(engineId, workerId, nodeCfg) ++ ZLayer.succeed(nodeCfg)
  }
}
