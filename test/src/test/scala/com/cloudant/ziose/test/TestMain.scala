package com.cloudant.ziose.test

import com.cloudant.ziose.test.helpers.TestRunner
import com.cloudant.ziose.test.CodecSpec

/**
 * ```shell
 * rm artifacts/clouseau_*.jar ; make jartest
 * java -cp artifacts/clouseau_*_test.jar com.cloudant.ziose.test.TestMain
 * ```
 */
object TestMain {
  def main(args: Array[String]): Unit = {
    TestRunner.runSpecs(
      Map("CodecSpec" -> new CodecSpec().spec)
    )
  }
}
