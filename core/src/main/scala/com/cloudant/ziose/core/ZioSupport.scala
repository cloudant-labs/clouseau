package com.cloudant.ziose.core

import zio.{Exit, IO, Runtime, Unsafe, ZIO}

trait Adapter[C, F] {
  def runtime: Runtime[Any]
}

trait ZioSupport {
  implicit final class ZioOps[E <: Throwable, A](self: IO[E, A]) {
    def unsafeRun: Exit[E, A] =
      self.unsafeRunWith(Runtime.default)

    def unsafeRunAndGet: A =
      self.unsafeRunAndGetWith(Runtime.default)
  }

  implicit final class ZioOpsWith[R, E <: Throwable, A](self: ZIO[R, E, A]) {
    def unsafeRunWith(runtime: Runtime[R]): Exit[E, A] = {
      Unsafe.unsafe(implicit u => runtime.unsafe.run(self))
    }

    def unsafeRunAndGetOrThrowWith(runtime: Runtime[R]): A = {
      Unsafe.unsafe(implicit u => runtime.unsafe.run(self).getOrThrow())
    }

    def unsafeRunAndGetWith(runtime: Runtime[R]): A = {
      Unsafe.unsafe(implicit u => runtime.unsafe.run(self).getOrThrowFiberFailure())
    }
  }
}
