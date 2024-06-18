package com.cloudant.ziose.clouseau

import com.cloudant.ziose.core
import com.cloudant.ziose.scalang

object SimpleProcess extends core.ActorConstructor[scalang.Process] {
  def make(node: scalang.SNode, fun: scalang.Process => Unit) = {
    def maker[PContext <: core.ProcessContext](process_context: PContext): scalang.Process = {
      val proc = new scalang.Process()(scalang.Adapter(process_context, node, ClouseauTypeFactory))
      fun(proc)
      proc
    }
    core
      .ActorBuilder()
      // TODO get capacity from config
      .withCapacity(16)
      .withMaker(maker)
      .build(this)
  }
}
