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

import com.cloudant.ziose.macros.checkEnv
import zio.{Queue, Scope, UIO, ZIO}

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
  def offer(msg: MessageEnvelope)(implicit trace: zio.Trace): UIO[Boolean] = {
    exchange.offer(msg)
  }

  @checkEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"exchange=$exchange",
    s"engineId=$engineId"
  )

  // def run = {
  //   for {
  //     //p <- Promise.make[Nothing, Unit] // FIXME I think we need a different mechanism here
  //     e <- exchange.run.fork.debug("Engine exchange started") // if root exchange die we cannot recover
  //     _ <- ZIO.debug("hh")
  //     //_ <- exchange.map(worker => worker.run.debug("eeee"))
  //     //_ <- p.await
  //     _ <- e.join.debug("engine result")
  //   } yield ()
  // }
}

object Engine {
  private var once = false
  type EngineId = Int
  type WorkerId = Int

  trait Error                    extends Throwable
  case class EngineIsSingleton() extends Error

  def make(capacity: Int): zio.ZIO[Any, Error, com.cloudant.ziose.core.Engine] = {
    if (once) {
      ZIO.fail(EngineIsSingleton())
    } else {
      this.once = true
      for {
        exchange <- EngineExchange.make(capacity)
      } yield new Engine(exchange)
    }
  }

  // TODO Enforce singleton somehow
  def makeWithQueue(queue: Queue[MessageEnvelope]) = {
    for {
      exchange <- EngineExchange.makeWithQueue(queue)
    } yield new Engine(exchange)
  }
}

object Supervisor {
  // TODO: Add exponential back-off
  // def supervise(worker: EngineWorker) =
  //   ZIO(worker.run).fork.flatMap(_.join).retry(Schedule.forever)
}
