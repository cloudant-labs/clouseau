package com.cloudant.ziose.experiments

import zio.test.junit.ZTestJUnitRunner
import zio.test.junit.JUnitRunnableSpec
import org.junit.runner.RunWith
import zio._
import zio.test._
import zio.test.Assertion._

@RunWith(classOf[ZTestJUnitRunner])
class HelloSpec extends JUnitRunnableSpec {
  def spec = suite("Population Exercise")(
    test("successful divide") {
      for {
        result <- Hello.divide(4, 2)
      } yield {
        assertTrue(result == 2)
      }
    },
    test("failed divide") {
      assertZIO(Hello.divide(4, 0).exit)(
        fails(isSubtype[ArithmeticException](anything))
      )
    },
    test("test logger") {
      for {
        code   <- Hello.run
        logs   <- ZTestLogger.logOutput
        output <- TestConsole.output
      } yield assertTrue(
        output.nonEmpty &&
          code == ExitCode.success &&
          logs(0).logLevel == LogLevel.Info &&
          logs(0).message() == "name: Ziose" &&
          logs(1).logLevel == LogLevel.Error
      )
    }
  )
}

/*
gradle clean test --tests 'ziose.HelloSpec'
 */
