package com.cloudant.ziose.test.helpers

import scala.concurrent.TimeoutException

import zio._
import zio.test._

import com.cloudant.ziose.core

object Asserts {

  /**
   * Matcher which matches on inner elements of a structure.
   *
   * Receives a `PartialFunction[Any, Boolean]`
   *
   * The assertion succeeds if both conditions are satisfied:
   *   1. The shape of provided value matches the partial function (i.e. the function is defined on the value domain)
   *   1. The result of the partial function invocation is `true`
   *
   * {{{
   * assert(history.get.headOption)(hasShape {
   *   case Some((_, _, reason: String)) =>
   *     reason.contains("OnMessageResult") && reason.contains("HandleCallCBError")
   * }) ?? "reason has to contain 'OnMessageResult' and 'HandleCallCBError'"
   * }}}
   */
  def hasShape(shape: PartialFunction[Any, Boolean]) = {
    val matcher: (=> Option[Any]) => Boolean = { matchedValue =>
      shape.lift(matchedValue).getOrElse(false)
    }
    Assertion.assertion("hasShape")(matcher)
  }

  /**
   * Iterates over all elements of an iterator and try to find the element which matches on inner elements of a
   * structure.
   *
   * Receives a `PartialFunction[Any, Boolean]`
   *
   * The assertion succeeds iff both conditions are satisfied
   *   1. The shape of provided value matches the partial function (i.e. the function is defined on the value domain)
   *   1. The result of the partial function invocation is `true`
   *
   * {{{
   * && assert (history.get)(containsShape { case (pid: Pid, ref, _) =>
   *   pid == Pid.toScala(echoPid)
   * }) ?? "has to contain elements of expected shape"
   * }}}
   */
  def containsShape(shape: PartialFunction[Any, Boolean]) = {
    val matcher: (=> Iterable[Any]) => Boolean = { it =>
      it.collect(shape).exists(_ == true)
    }
    Assertion.assertion("hasShape")(matcher)
  }

  /**
   * Iterates over all elements of an iterator wrapped in an Option and try to find the element which matches on inner
   * elements of a structure.
   *
   * Receives a `PartialFunction[Any, Boolean]`
   *
   * The assertion succeeds if both conditions are satisfied
   *   1. The shape of provided value matches the partial function (i.e. the function is defined on the value domain)
   *   1. The result of the partial function invocation is `true`
   *
   * {{{
   * && assert(history)(containsShapeOption { case (pid: Pid, ref, _) =>
   *       pid == Pid.toScala(echoPid)
   *     }) ?? "has to contain elements of expected shape"
   * }}}
   */
  def containsShapeOption(shape: PartialFunction[Any, Boolean]) = {
    val matcher: (=> Option[Iterable[Any]]) => Boolean = { option =>
      option match {
        case Some(it) => it.collect(shape).exists(_ == true)
        case None     => false
      }
    }
    Assertion.assertion("hasShape")(matcher)
  }

  /**
   * Assertion to verify that the actor with given address is currently alive
   *
   * {{{
   * _ <- assertAlive(echo.id).debug("IsAlive(echo)")
   * }}}
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

  /**
   * Assertion to verify that the actor with given address is currently NOT alive
   *
   * {{{
   * _ <- assertNotAlive(echo.id).debug("IsAlive(echo)")
   * }}}
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

  /**
   * Assertion which fails if log contains Errors
   *
   * Example of checking that log doesn't contain errors:
   *
   * {{{
   * && assert(logHistory)(
   *   helpers.Asserts.assertHasNoErrors
   * ) ?? "should not log any errors"
   * }}}
   *
   * Example of checking that there were no errors on specific callback of specific actor:
   *
   * {{{
   * && assert(logHistory.withActorCallback("TestActor", ActorCallback.OnMessage))(
   *   helpers.Asserts.assertHasNoErrors
   * ) ?? "'TestService.onTermination' callback should not log any errors"
   * }}}
   */
  def assertHasNoErrors = {
    Assertion.assertion("hasNoErrors")(LogHistory.hasNoErrors)
  }

}
