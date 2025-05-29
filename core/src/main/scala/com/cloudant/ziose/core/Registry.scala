package com.cloudant.ziose.core

import com.cloudant.ziose.macros.CheckEnv
import zio.{Ref, Scope, UIO, ZIO}

private[core] case class State[K, E](index: Int = 0, size: Int = 0, entries: Map[K, E])

class Registry[K, M, E <: ForwardWithId[K, M]](private val state: Ref[State[K, E]]) {
  def add(entry: E): UIO[Unit] = {
    for {
      _ <- state.update(s => {
        if (!s.entries.contains(entry.id)) {
          State(s.index + 1, s.size + 1, s.entries + (entry.id -> entry))
        } else {
          s
        }
      })
    } yield ()
  }

  def buildWith(builderFn: Int => ZIO[Any with Scope, Throwable, E]): ZIO[Any with Scope, Throwable, E] = {
    // We never decrement index so there is no way we would get duplicate ids
    // In that sense even though a combination of modify and update is not atomic
    // it shouldn't cause wrong allocations
    for {
      id    <- state.modify(s => (s.index, State(s.index + 1, s.size, s.entries)))
      entry <- builderFn(id)
      _     <- state.update(s => State(s.index, s.size + 1, s.entries + (entry.id -> entry)))
    } yield entry
  }

  def foreach[U](fn: E => U): UIO[Unit] = {
    for {
      s <- state.get
    } yield s.entries.values.foreach(fn)
  }

  def get(key: K): UIO[Option[E]] = {
    for {
      s <- state.get
    } yield s.entries.get(key)
  }

  def index: UIO[Int] = for {
    s <- state.get
  } yield s.index

  def list: ZIO[Any with Scope, Throwable, Iterable[K]] = {
    for {
      s <- state.get
    } yield s.entries.keys
  }

  def map[B](fn: E => B): UIO[List[B]] = {
    for {
      s      <- state.get
      result <- ZIO.succeed(s.entries.values.map(fn).toList)
    } yield result
  }

  def remove(key: K): UIO[Option[E]] = {
    for {
      s <- state.getAndUpdate(s => {
        if (s.entries.contains(key)) {
          State(s.index, s.size - 1, s.entries.removed(key))
        } else {
          s
        }
      })
    } yield s.entries.get(key)
  }

  def replace(entry: E): UIO[Option[E]] = {
    for {
      s <- state.getAndUpdate(s => {
        if (s.entries.contains(entry.id)) {
          State(s.index, s.size, s.entries.updated(entry.id, entry))
        } else {
          s
        }
      })
    } yield s.entries.get(entry.id)
  }

  def size: UIO[Int] = for {
    s <- state.get
  } yield s.entries.size

  def isKnown(key: K): UIO[Boolean] = for {
    s <- state.get
  } yield s.entries.contains(key)

  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"${getClass.getSimpleName}",
    s"state=$state"
  )
}

object Registry {
  def emptyState[K, E]: State[K, E] = State(0, 0, Map.empty)

  def make[K, M, E <: ForwardWithId[K, M]]: ZIO[Any, Nothing, Registry[K, M, E]] = {
    for {
      stateRef <- Ref.make(emptyState[K, E])
    } yield new Registry(stateRef)
  }
}
