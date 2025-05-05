package com.cloudant.ziose.otp

import com.cloudant.ziose.core.{ActorFactory, Engine, EngineWorker, Node}
import zio.{&, TaskLayer, ZLayer}

object Utils {
  def testEnvironment(
    engineId: Engine.EngineId,
    workerId: Engine.WorkerId,
    nodeName: String = "test"
  ): TaskLayer[EngineWorker & Node & ActorFactory & OTPNodeConfig] = {
    val nodeCfg          = OTPNodeConfig(nodeName, "127.0.0.1", Some("testCookie"))
    val exchangeCapacity = None
    OTPLayers.nodeLayers(engineId, workerId, exchangeCapacity, nodeCfg) ++ ZLayer.succeed(nodeCfg)
  }
}
