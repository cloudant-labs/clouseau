package com.cloudant.ziose.clouseau.helpers

import scala.concurrent.TimeoutException

import zio._
import zio.test._

import com.cloudant.ziose.core

object Asserts {
  /*
   * Matcher which matches on inner elements of a structure.
   *
   *  Receives a `PartialFunction[Any, Boolean]`
   * The assertion succeeds iff both conditions are satisfied
   *   1. The shape of provided value matches the partial function
   *      (i.e. the function is defined on the value domain)
   *   2. The result of the partial function invocation is `true`
   *
   * ```scala
   * assert(history.get.headOption)(hasShape {
   *   case Some((_, _, reason: String)) =>
   *     reason.contains("OnMessageError") && reason.contains("HandleCallCBError")
   * }) ?? "reason has to contain 'OnMessageError' and 'HandleCallCBError'"
   * ```
   */
  def hasShape(shape: PartialFunction[Any, Boolean]) = {
    val matcher: (=> Option[Any]) => Boolean = { matchedValue =>
      shape.lift(matchedValue).getOrElse(false)
    }
    Assertion.assertion("hasShape")(matcher)
  }

  /*
   * Iterates over all elements of an iterator and try to find the element which
   * matches on inner elements of a structure.
   *
   * Receives a `PartialFunction[Any, Boolean]`
   * The assertion succeeds iff both conditions are satisfied
   *   1. The shape of provided value matches the partial function
   *      (i.e. the function is defined on the value domain)
   *   2. The result of the partial function invocation is `true`
   *
   * ```scala
   * && assert(history.get)(containsShape { case (pid: Pid, ref, _) =>
   *       pid == Pid.toScala(echoPid)
   *     }) ?? "has to contain elements of expected shape"
   * ```
   */
  def containsShape(shape: PartialFunction[Any, Boolean]) = {
    val matcher: (=> Iterable[Any]) => Boolean = { it =>
      !it.collect(shape).isEmpty
    }
    Assertion.assertion("hasShape")(matcher)
  }

  /*
   * Iterates over all elements of an iterator wrapped in an Option and try
   * to find the element which matches on inner elements of a structure.
   *
   * Receives a `PartialFunction[Any, Boolean]`
   * The assertion succeeds iff both conditions are satisfied
   *   1. The shape of provided value matches the partial function
   *      (i.e. the function is defined on the value domain)
   *   2. The result of the partial function invocation is `true`
   *
   * ```scala
   * && assert(history)(containsShapeOption { case (pid: Pid, ref, _) =>
   *       pid == Pid.toScala(echoPid)
   *     }) ?? "has to contain elements of expected shape"
   * ```
   */
  def containsShapeOption(shape: PartialFunction[Any, Boolean]) = {
    val matcher: (=> Option[Iterable[Any]]) => Boolean = { option =>
      option match {
        case Some(it) => !it.collect(shape).isEmpty
        case None     => false
      }
    }
    Assertion.assertion("hasShape")(matcher)
  }

  /*
   * Assertion to verify that the actor with given address is currently alive
   *
   * ```scala
   * _       <- assertAlive(echo.id).debug("IsAlive(echo)")
   * ```
   */
  def assertAlive(address: core.Address, timeout: Duration = 2.seconds): ZIO[core.EngineWorker, Throwable, Unit] = for {
    worker <- ZIO.service[core.EngineWorker]
    result <- worker.exchange
      .isKnown(address)
      .repeatWhile(_ == false)
      .timeout(timeout)
    _ <- result match {
      case None        => ZIO.fail(new TimeoutException(s"assertAlive(${address}) timed out"))
      case Some(false) => ZIO.fail(new AssertionError(s"Expected ${address} to be alive"))
      case _           => ZIO.unit
    }
  } yield ()

  /*
   * Assertion to verify that the actor with given address is currently alive
   *
   * ```scala
   * _       <- assertNotAlive(echo.id).debug("IsAlive(echo)")
   * ```
   */
  def assertNotAlive(address: core.Address, timeout: Duration = 2.seconds): ZIO[core.EngineWorker, Throwable, Unit] = {
    for {
      worker <- ZIO.service[core.EngineWorker]
      result <- worker.exchange
        .isKnown(address)
        .repeatWhile(_ == true)
        .timeout(timeout)
      _ <- result match {
        case None       => ZIO.fail(new TimeoutException(s"assertAlive(${address}) timed out"))
        case Some(true) => ZIO.fail(new AssertionError(s"Expected ${address} to be alive"))
        case _          => ZIO.unit
      }
    } yield ()
  }
}
