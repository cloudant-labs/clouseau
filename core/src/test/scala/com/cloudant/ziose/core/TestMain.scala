package com.cloudant.ziose.core

import zio.Scope

import com.cloudant.ziose.core.helpers.TestRunner

import com.cloudant.ziose.core.RegistrySpec
import com.cloudant.ziose.core.MessageEnvelopeSpec

/**
 * ```shell
 * rm artifacts/clouseau_*.jar ; make jartest
 * java -cp artifacts/clouseau_*_test.jar com.cloudant.ziose.core.TestMain
 * ```
 */
object TestMain {
  def main(args: Array[String]): Unit = {
    TestRunner.runSpecs(
      Map(
        "RegistrySpec"        -> new RegistrySpec().spec.provideLayer(Scope.default),
        "MessageEnvelopeSpec" -> new MessageEnvelopeSpec().spec.provideLayer(Scope.default)
      )
    )
  }
}
