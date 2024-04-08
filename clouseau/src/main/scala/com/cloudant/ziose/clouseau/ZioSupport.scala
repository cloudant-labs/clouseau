package com.cloudant.ziose.clouseau

import com.cloudant.ziose.scalang.Adapter
import zio.{IO, Unsafe}

trait ZioSupport {
  implicit class ZioOps[E, A](self: IO[E, A]) {
    def unsafeRun(implicit adapter: Adapter[_, _]): A = {
      Unsafe.unsafe(implicit u => adapter.runtime.unsafe.run(self).getOrThrowFiberFailure)
    }
  }
}
