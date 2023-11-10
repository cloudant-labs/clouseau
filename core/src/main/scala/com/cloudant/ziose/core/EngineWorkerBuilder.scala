package com.cloudant.ziose.core

import zio._

trait EngineWorkerBuilder[W <: EngineWorker] {
  type Type = W
  def build(engineId: Int, workerId: Int): ZIO[Any with Scope, Throwable, W]
}
