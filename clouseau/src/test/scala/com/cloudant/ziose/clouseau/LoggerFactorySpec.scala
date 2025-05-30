/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.LoggerFactorySpec'
 */
package com.cloudant.ziose.clouseau

import org.junit.runner.RunWith
import zio._
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}

import com.cloudant.ziose.core
import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect
import com.cloudant.ziose.test.helpers.TestRunner

@RunWith(classOf[ZTestJUnitRunner])
class LoggerFactorySpec extends JUnitRunnableSpec {
  val loggerSuite: Spec[Any, Throwable] = {
    val logLevelTests = (List("debug", "info", "warn", "error")).map(level => {
      test(s"crash in '${level}' doesn't terminate actor")(
        for {
          node   <- Utils.clouseauNode
          handle <- TestService.start(node, "echo")
          success <- handle
            .doTestCall(
              core.Codec.ETuple(core.Codec.EAtom(s"crashLogger.${level}"), core.Codec.EBinary("myReason"))
            )
          payload = core.Codec.ETuple(core.Codec.EAtom("echo"), core.Codec.EBinary(s"echo.${level}"))
          echoReply <- handle.doTestCall(payload)
        } yield assert(success.payload)(isSome) ?? "Expected to receive something from the service"
          && assert(success.payload.get)(
            equalTo(core.Codec.EAtom(level))
          ) ?? s"Expected to receive '${level}' in the reply"
      )
    })
    suite("logger testing")(logLevelTests).provideLayer(
      Utils.testEnvironment(1, 1, "serviceSpawn")
    ) @@ TestAspect.withLiveClock
  }

  def spec: Spec[Any, Throwable] = {
    suite("LoggerFactorySpec")(
      loggerSuite
    ) @@ TestAspect.timeout(15.minutes)
  }

}

/**
 * ```shell
 * rm artifacts/clouseau_*.jar ; make jartest
 * java -cp artifacts/clouseau_*_test.jar com.cloudant.ziose.clouseau.LoggerFactorySpecMain
 * ```
 */
object LoggerFactorySpecMain {
  def main(args: Array[String]): Unit = {
    TestRunner.runSpec("LoggerFactorySpec", new LoggerFactorySpec().spec)
  }
}
