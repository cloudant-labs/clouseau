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
import zio.stream.ZStream
import zio.{Enqueue, Queue, Scope, Trace, UIO, ZIO}

class Exchange[K, M, E <: EnqueueWithId[K, M]](val queue: Queue[M], registry: Registry[K, M, E], val keyFn: M => K)
    extends Exchange.WithConstructor[K, M, E] {
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
  def capacity: Int                        = queue.capacity
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
  def stream = ZStream
    .fromQueueWithShutdown(queue)
    // .tap(x => printLine(s"exchange event: $x"))
    .mapZIO(loop)
  def loop(msg: M) = for {
    destination <- this.get(keyFn(msg))
    _           <- maybeForward(destination, msg)
  } yield ()
  def maybeForward(destination: Option[E], msg: M): UIO[Unit] = destination match {
    case Some(dst) => {
      dst.offer(msg).unit
    }
    case None => {
      ZIO.succeed(())
    }
  }

  def run = stream.runDrain

  override def awaitShutdown(implicit trace: Trace): UIO[Unit] = {
    queue.awaitShutdown
  }
  def isShutdown(implicit trace: Trace): UIO[Boolean] = {
    queue.isShutdown
  }
  def shutdown(implicit trace: Trace): UIO[Unit] = {
    queue.shutdown
  }
  def offer(msg: M)(implicit trace: zio.Trace): UIO[Boolean] = {
    queue.offer(msg)
  }
  def offerAll[A1 <: M](as: Iterable[A1])(implicit trace: zio.Trace): UIO[zio.Chunk[A1]] = {
    queue.offerAll(as)
  }
  def size(implicit trace: zio.Trace): UIO[Int] = {
    queue.size
  }

  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"queue=$queue",
    s"registry=$registry",
    s"keyFn=$keyFn"
  )
}

object Exchange {
  trait WithConstructor[K, M, E <: EnqueueWithId[K, M]] extends Enqueue[M] {
    def buildWith(builderFn: Int => ZIO[Any with Scope, Throwable, E]): ZIO[Any with Scope, Throwable, E]
  }

  def make[K, M, E <: EnqueueWithId[K, M]](capacity: Int, keyFn: M => K): ZIO[Any, Nothing, Exchange[K, M, E]] = {
    for {
      queue    <- Queue.bounded[M](capacity)
      registry <- Registry.make[K, M, E]
    } yield new Exchange(queue, registry, keyFn)
  }
  def makeWithQueue[K, M, E <: EnqueueWithId[K, M]](
    queue: Queue[M],
    keyFn: M => K
  ): ZIO[Any, Nothing, Exchange[K, M, E]] = {
    for {
      registry <- Registry.make[K, M, E]
    } yield new Exchange(queue, registry, keyFn)
  }
}
