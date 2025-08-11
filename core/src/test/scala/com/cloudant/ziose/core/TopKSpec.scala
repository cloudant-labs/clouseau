/*
 * $ sbt "testOnly com.cloudant.ziose.core.TopKSpec"
 *
 */

package com.cloudant.ziose.core

import org.junit.runner.RunWith

import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}
import zio.test.{Gen, assertTrue, check}
import zio.test.Gen._

import helpers.TestRunner

@RunWith(classOf[ZTestJUnitRunner])
class TopKSpec extends JUnitRunnableSpec {
  def sorted(tuples: List[(String, Long)]): List[(String, Long)] = {
    val ordering: Ordering[(String, Long)] = Ordering.by(_._2)
    tuples.sorted(ordering.reverse)
  }

  def tuples(): Gen[Any, (String, Long)] = for {
    key   <- alphaNumericString
    value <- long(0, 1234)
  } yield (key, value)

  def listOfTuples(size: Int): Gen[Any, List[(String, Long)]] = listOfN(size)(tuples())

  val topKSuite = suite("sorted top K")(
    test("lesser than K are in order") {
      check(listOfTuples(8)) { values =>
        val expected = sorted(values).take(8)
        var top      = TopK[String](10)
        for ((k, v) <- values) {
          top.add(k, v)
        }
        val (_keys, vals)      = top.asList.unzip
        val (_, expected_vals) = expected.unzip
        assertTrue(vals == expected_vals, vals.sorted == expected_vals.sorted)
      }
    },
    test("smaller than K are in order") {
      check(listOfTuples(18)) { values =>
        val expected = sorted(values).take(10)
        var top      = TopK[String](10)
        for ((k, v) <- values) {
          top.add(k, v)
        }
        val (_keys, vals)      = top.asList.unzip
        val (_, expected_vals) = expected.unzip
        assertTrue(vals.sorted == expected_vals.sorted)
      }
    }
  )

  def spec = suite("TopKSpec")(
    topKSuite
  )
}

/**
 * ```shell
 * rm artifacts/clouseau_*.jar ; make jartest
 * java -cp artifacts/clouseau_*_test.jar com.cloudant.ziose.core.TopKSpecMain
 * ```
 */
object TopKSpecMain {
  def main(args: Array[String]): Unit = {
    TestRunner.runSpec("TopKSpec", new TopKSpec().spec)
  }
}
