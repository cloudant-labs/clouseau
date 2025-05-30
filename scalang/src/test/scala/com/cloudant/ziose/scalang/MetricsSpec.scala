/*
 * $ sbt "scalang/testOnly com.cloudant.ziose.scalang.MetricsSpec"
 */

package com.cloudant.ziose.scalang

import java.io.IOException
import java.io.FileNotFoundException

import org.junit.runner.RunWith
import zio._
import zio.test._
import zio.test.Assertion._
import zio.test.junit._

@RunWith(classOf[ZTestJUnitRunner])
class MetricsSpec extends JUnitRunnableSpec {
  val environment = ZLayer.succeed(Clock.ClockLive) ++ ZLayer.succeed(Random.RandomLive)

  def metricsRegistry = ScalangMeterRegistry.testRegistry
  val metrics         = MetricsGroup(getClass, metricsRegistry)

  case class MyException(m: String) extends Exception(m)

  val metricsSuite = suite("metrics testing")(
    test("testing Timer.time throws exception") {
      var timer = metrics.timer("Exception")
      val result = {
        try {
          Right(timer.time {
            throw new Exception("myReason")
          })
        } catch {
          case e: Throwable =>
            Left(e)
        }
      }
      assert(result)(isLeft) ?? "result should be an error" &&
      assert(result.swap.toOption.get)(isSubtype[Exception](anything)) &&
      assert(result.swap.toOption.get.getMessage())(equalTo("myReason")) &&
      assert(timer.getCount)(isGreaterThanEqualTo(1L)) &&
      assert(timer.getMeanRate)(isGreaterThan(0.0))
    },
    test("testing Timer.time throws custom exception") {
      var timer = metrics.timer("myException")
      val result = {
        try {
          Right(timer.time {
            throw new MyException("myCustomReason")
          })
        } catch {
          case e: Throwable =>
            Left(e)
        }
      }
      assert(result)(isLeft) ?? "result should be an error" &&
      assert(result.swap.toOption.get)(isSubtype[MyException](anything)) &&
      assert(result.swap.toOption.get.getMessage())(equalTo("myCustomReason")) &&
      assert(timer.getCount)(isGreaterThanEqualTo(1L)) &&
      assert(timer.getMeanRate)(isGreaterThan(0.0))
    },
    test("testing Timer.time throws FileNotFoundException exception") {
      var timer = metrics.timer("FileNotFoundException")
      val result = {
        try {
          Right(timer.time {
            throw new FileNotFoundException("myFileNotFound")
          })
        } catch {
          case e: Throwable =>
            Left(e)
        }
      }
      assert(result)(isLeft) ?? "result should be an error" &&
      assert(result.swap.toOption.get)(isSubtype[IOException](anything)) &&
      assert(result.swap.toOption.get.getMessage())(equalTo("myFileNotFound")) &&
      assert(timer.getCount)(isGreaterThanEqualTo(1L)) &&
      assert(timer.getMeanRate)(isGreaterThan(0.0))
    },
    test("testing Timer.time returns result") {
      var timer = metrics.timer("testTimer")
      val result = {
        try {
          Right(timer.time {
            2 + 4
          })
        } catch {
          case e: Throwable =>
            Left(e)
        }
      }
      assert(result)(isRight) ?? "result should be success" &&
      assert(result.toOption.get)(equalTo(6)) &&
      assert(timer.getCount)(isGreaterThanEqualTo(1L)) &&
      assert(timer.getMeanRate)(isGreaterThan(0.0))
    }
  )

  def spec = suite("metrics")(metricsSuite).provideLayer(environment)
}
