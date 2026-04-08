package com.cloudant.ziose.core

import zio.{Exit, IO, Runtime, Unsafe, ZIO}

trait Adapter[C, F] {
  def runtime: Runtime[Any]
}

trait ZioSupport {
  implicit final class ZioOps[E <: Throwable, A](self: IO[E, A]) {
    def unsafeRun: Exit[E, A] = {
      Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(self))
    }
  }

  implicit final class ZioOpsAdapter[E <: Throwable, A](self: IO[E, A]) {
    def unsafeRunAdapterGetOrThrow(implicit adapter: Adapter[_, _]): A = {
      Unsafe.unsafe(implicit u => adapter.runtime.unsafe.run(self).getOrThrow())
    }
  }

  implicit final class ZioOpsCustomRuntime[R, E <: Throwable, A](self: ZIO[R, E, A]) {
    def unsafeRunWith(runtime: Runtime[R]): Exit[E, A] = {
      Unsafe.unsafe(implicit u => runtime.unsafe.run(self))
    }

    def unsafeRunCustomRuntimeGetOrThrow(runtime: Runtime[R]): A = {
      Unsafe.unsafe(implicit u => runtime.unsafe.run(self).getOrThrow())
    }
  }
}
