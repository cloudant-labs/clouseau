package com.cloudant.ziose.test.helpers

import zio._
import zio.test._

object TestRunner {
  def runSpec(label: String, spec: Spec[Any, Throwable]): Summary = Unsafe.unsafe(implicit u => {
    defaultTestRunner.runtime.unsafe
      .run(
        defaultTestRunner.executor
          .run(label, spec, ExecutionStrategy.Sequential)
      )
      .getOrThrowFiberFailure()
  })
}
