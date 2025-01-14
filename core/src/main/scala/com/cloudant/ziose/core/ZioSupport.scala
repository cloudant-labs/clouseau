package com.cloudant.ziose.core

import zio.{IO, Runtime, Unsafe}

trait ZioSupport {
  implicit final class ZioOps[E, A](self: IO[E, A]) {
    def unsafeRun: A = Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(self).getOrThrowFiberFailure())
  }
}
