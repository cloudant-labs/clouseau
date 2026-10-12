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

object Engine {
  type EngineId = Int
  type WorkerId = Int
}
