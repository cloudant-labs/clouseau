package com.cloudant.ziose.experiments

import org.junit.runner.RunWith
import zio.{ExitCode, LogLevel}
import zio.test.Assertion.{anything, fails, isSubtype}
import zio.test.TestAspect.withLiveClock
import zio.test.{TestAspect, TestConsole, ZTestLogger, assertTrue, assertZIO}
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}

@RunWith(classOf[ZTestJUnitRunner])
class HelloSpec extends JUnitRunnableSpec {
  val divisionSuite =
    suite("Division Tests")(
      test("successful divide") {
        for {
          result <- Hello.divide(4, 2)
        } yield assertTrue(result == 2)
      },
      test("failed divide") {
        assertZIO(Hello.divide(4, 0).exit)(
          fails(isSubtype[ArithmeticException](anything))
        )
      }
    )

  val loggerSuite =
    suite("Logger Tests")(
      test("test logger") {
        for {
          _      <- Hello.run
          logs   <- ZTestLogger.logOutput
          output <- TestConsole.output
        } yield assertTrue(
          output.nonEmpty &&
            (logs(0).logLevel == LogLevel.Info && logs(0).message() == "name: Ziose") &&
            (logs(1).logLevel == LogLevel.Error && logs(1).message() == "n: 2") &&
            (logs(2).logLevel == LogLevel.Warning && logs(2).message() == "Counter: 2") &&
            (logs(3).logLevel == LogLevel.Warning && logs(3).message() == "Timer Count: 2")
        )
      } @@ withLiveClock @@ TestAspect.flaky @@ TestAspect.silent
    )

  def spec = suite("HelloTests")(divisionSuite, loggerSuite)
}

/*
gradle :experiments:test --tests HelloSpec
 */
