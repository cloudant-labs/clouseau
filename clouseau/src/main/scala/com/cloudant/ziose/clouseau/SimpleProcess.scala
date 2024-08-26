package com.cloudant.ziose.clouseau

import com.cloudant.ziose.core
import com.cloudant.ziose.scalang
import zio._

class SimpleProcess(fun: scalang.Process => Unit)(implicit adapter: scalang.Adapter[_, _])
    extends scalang.Process()(adapter) {
  override def onInit[PContext <: core.ProcessContext](ctx: PContext): ZIO[Any, Throwable, _ <: core.ActorResult] = {
    ZIO.succeedBlocking {
      fun(this)
      core.ActorResult.Stop()
    }
  }
  override def onTermination[PContext <: core.ProcessContext](reason: core.Codec.ETerm, ctx: PContext): UIO[Unit] = {
    ZIO.logTrace(s"reason = ${reason}")
  }
}

object SimpleProcess extends core.ActorConstructor[scalang.Process] {
  def make(node: scalang.SNode, fun: scalang.Process => Unit) = {
    def maker[PContext <: core.ProcessContext](process_context: PContext): scalang.Process = {
      new SimpleProcess(fun)(scalang.Adapter(process_context, node, ClouseauTypeFactory))
    }
    core
      .ActorBuilder()
      // TODO get capacity from config
      .withCapacity(16)
      .withMaker(maker)
      .build(this)
  }
}
