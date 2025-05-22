package com.cloudant.ziose.core

/*
Engine
  |- EngineExchange (WorkerId)
    |- EngineWorker1 (OTP node1) --- TCP --- dreyfus
    |- EngineWorker2 (OTP node2) --- TCP --- dreyfus
        |- WorkerExchange (Pid)
          |-  Fiber1/Service ZIO - Enqueue - MessageBox
          |-  Fiber2

MessageBox
  ---> erlang.OtpMBox -----Queue----> Fiber
  ---> Queue -----------/

Engine needs EngineExchange
EngineWorker needs Engine

 */

import com.cloudant.ziose.macros.CheckEnv
import zio.{Scope, UIO, ZIO}

class Engine(exchange: EngineExchange) {
  val engineId: Engine.EngineId = 1

  def terminate = ZIO.succeed(())

  def makeAndAddWorkersUnit[W <: EngineWorker](
    numWorkers: Int,
    builder: EngineWorkerBuilder[W]
  ): ZIO[Any with zio.Scope, Throwable, Unit] = {
    val effects = (1 to numWorkers).toList.map(_ => makeAndAddWorkerZIO(builder))
    ZIO.mergeAll(effects)(zero = List.empty[W])((acc: List[W], w: W) => w :: acc).unit
  }

  def makeAndAddWorkerZIO[W <: EngineWorker](builder: EngineWorkerBuilder[W]): ZIO[Any with Scope, Throwable, W] = {
    exchange.buildAndRegisterZIO(workerId => builder.build(engineId, workerId))
  }

  def makeAndAddWorker[W <: EngineWorker](builder: EngineWorkerBuilder[W]): Unit = {
    exchange.buildAndRegister(workerId => builder.build(engineId, workerId))
  }

  def list                     = exchange.list
  def get(id: Engine.WorkerId) = exchange.get(id)
  def forward(msg: MessageEnvelope)(implicit trace: zio.Trace): UIO[Boolean] = {
    exchange.forward(msg)
  }

  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"exchange=$exchange",
    s"engineId=$engineId"
  )
}

object Engine {
  private var once = false
  type EngineId = Int
  type WorkerId = Int

  trait Error                   extends Throwable
  case object EngineIsSingleton extends Error

  def make(): zio.ZIO[Any, Error, com.cloudant.ziose.core.Engine] = {
    if (once) {
      ZIO.fail(EngineIsSingleton)
    } else {
      this.once = true
      for {
        exchange <- EngineExchange.make
      } yield new Engine(exchange)
    }
  }
}
