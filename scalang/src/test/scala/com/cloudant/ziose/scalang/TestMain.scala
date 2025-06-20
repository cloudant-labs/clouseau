package com.cloudant.ziose.scalang

import com.cloudant.ziose.test.helpers.TestRunner
import com.cloudant.ziose.scalang.MetricsSpec

/**
 * ```shell
 * rm artifacts/clouseau_*.jar ; make jartest
 * java -cp artifacts/clouseau_*_test.jar com.cloudant.ziose.scalang.TestMain
 * ```
 */
object TestMain {
  def main(args: Array[String]): Unit = {
    TestRunner.runSpecs(
      Map(
        "MetricsSpec" -> new MetricsSpec().spec
      )
    )
  }
}
