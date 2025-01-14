package com.cloudant.ziose.core

/*
Inspired by Either and Some implementations

The order of types follows the order in other Scala types
(the error type goes before success type).

- Promise[E, T]
- ZIO[Any, E, T]
- cats.data.Validated[E, T]

 */

import com.cloudant.ziose.macros.CheckEnv
import zio.ZIO

sealed abstract class Result[+E, +T] extends Product {
  def isSuccess: Boolean = this match {
    case Failure(_) => false
    case Success(_) => true
  }
  def isFailure: Boolean = this match {
    case Failure(_) => true
    case Success(_) => false
  }
  @inline final def mapSuccess[T1](f: T => T1): Result[E, T1] = this match {
    case Success(v)   => Success(f(v))
    case Failure(err) => Failure(err)
  }
  @inline final def mapFailure[E1](f: E => E1): Result[E1, T] = this match {
    case Success(v)   => Success(v)
    case Failure(err) => Failure(f(err))
  }
  @inline final def toZIO(): ZIO[Any, E, T] = this match {
    case Success(v)   => ZIO.succeed(v)
    case Failure(err) => ZIO.fail(err)
  }
}

final case class Success[E, +T](value: T) extends Result[E, T] {
  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"Result.${getClass.getSimpleName}",
    s"value=$value"
  )
}

final case class Failure[+E, T](reason: E) extends Result[E, T] {
  @CheckEnv(System.getProperty("env"))
  def toStringMacro: List[String] = List(
    s"Result.${getClass.getSimpleName}",
    s"reason=$reason"
  )
}
