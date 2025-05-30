package com.cloudant.ziose.core.helpers

import zio._
import zio.test._

object TestRunner {
  def runSpec(label: String, spec: Spec[TestEnvironment, Throwable]): Summary = Unsafe.unsafe(implicit u => {
    defaultTestRunner.runtime.unsafe
      .run(
        defaultTestRunner.executor
          .run(label, spec, ExecutionStrategy.Sequential)
      )
      .getOrThrowFiberFailure()
  })
}
