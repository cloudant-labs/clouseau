package com.cloudant.ziose.core

/*
 *
 * The Exchange class receives the messages and forward them based on the destination id.
 *
 * This class is using generics because we want to use it in two different contexts.
 * 1. As part of an Engine. In this case it does the routing based on workerId.
 * 2. As part of EngineWorker. In this case it does routing based on Address.
 */

import com.cloudant.ziose.macros.CheckEnv
import zio.{Scope, Trace, UIO, ZIO}
import java.util.concurrent.atomic.AtomicBoolean
import zio.Unsafe
import zio.Runtime

class Exchange[K, M, E <: ForwardWithId[K, M]](registry: Registry[K, M, E], val keyFn: M => K)
    extends Exchange.WithConstructor[K, M, E] {
  private var isFinalized: AtomicBoolean = new AtomicBoolean(false)
  def add(entity: E): UIO[Unit] = {
    registry.add(entity)
  }
  def remove(key: K): ZIO[Any, Nothing, Option[E]] = {
    registry.remove(key)
  }
  def replace(entity: E): ZIO[Any, Nothing, Option[E]] = {
    registry.replace(entity)
  }
  def isKnown(key: K): UIO[Boolean] = {
    registry.isKnown(key)
  }
  def foreach(fn: E => Unit): UIO[Unit]    = registry.foreach(fn)
  def map[B](fn: E => B): UIO[Iterable[B]] = registry.map(fn)
  /*
    Returns list of keys known to the exchange
   */
  def list = registry.list
  /*
    Returns a registered entity based on passed `key`
   */
  def get(key: K) = registry.get(key)
  def buildWith(builderFn: Int => ZIO[Any with Scope, Throwable, E]) = {
    registry.buildWith(builderFn)
  }
  private def maybeForward(destination: Option[E], msg: M): UIO[Boolean] = destination match {
    case Some(dst) => {
      dst.forward(msg)
    }
    case None => {
      ZIO.succeed(false)
    }
  }
  def shutdown(implicit trace: Trace): UIO[Unit] = {
    if (!isFinalized.getAndSet(true)) {
      foreach(x => {
        Unsafe.unsafe(implicit unsafe => {
          Runtime.default.unsafe.run(x.shutdown)
        })
      })
    } else { ZIO.unit }
  }

  def forward(msg: M)(implicit trace: zio.Trace): UIO[Boolean] = for {
    destination <- this.get(keyFn(msg))
    _           <- maybeForward(destination, msg)
  } yield true

  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"registry=$registry",
    s"keyFn=$keyFn"
  )
}

object Exchange {
  trait WithConstructor[K, M, E <: ForwardWithId[K, M]] {
    def buildWith(builderFn: Int => ZIO[Any with Scope, Throwable, E]): ZIO[Any with Scope, Throwable, E]
  }

  def make[K, M, E <: ForwardWithId[K, M]](keyFn: M => K): ZIO[Any, Nothing, Exchange[K, M, E]] = {
    for {
      registry <- Registry.make[K, M, E]
    } yield new Exchange(registry, keyFn)
  }
}
