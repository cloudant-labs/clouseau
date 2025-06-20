package com.cloudant.ziose.clouseau

import com.cloudant.ziose.test.helpers.TestRunner

/**
 * ```shell
 * rm artifacts/clouseau_*.jar ; make jartest
 * java -cp artifacts/clouseau_*_test.jar com.cloudant.ziose.clouseau.TestMain
 * ```
 */
object TestMain {
  def main(args: Array[String]): Unit = {
    TestRunner.runSpecs(
      Map(
        "ClouseauNodeSpec"        -> new ClouseauNodeSpec().spec,
        "ClouseauAdapterSpec"     -> new ClouseauAdapterSpec().spec,
        "SendEverySpec"           -> new SendEverySpec().spec,
        "MainSpec"                -> zio.test.suite("MainSpec")(new MainSpec().nodeIdxSuite),
        "ClouseauTypeFactorySpec" -> new ClouseauTypeFactorySpec().spec,
        "ClouseauSupervisorSpec"  -> new ClouseauSupervisorSpec().spec,
        "ServiceSpec"             -> new ServiceSpec().spec,
        "ConfigSpec"              -> new ConfigSpec().spec,
        "LoggerFactorySpec"       -> new LoggerFactorySpec().spec
      )
    )
  }
}
