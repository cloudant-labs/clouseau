/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.AnalyzerServiceSpec'
 */
package com.cloudant.ziose.clouseau

import org.junit.runner.RunWith
import zio._
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}

import com.cloudant.ziose.core._
import com.cloudant.ziose.scalang.{Adapter, ServiceContext}
import zio.test._
import zio.test.TestAspect
import com.cloudant.ziose.test.helpers.TestRunner

@RunWith(classOf[ZTestJUnitRunner])
class AnalyzerServiceSpec extends JUnitRunnableSpec {
  val TIMEOUT_SUITE = 5.minutes
  val environment   = Utils.testEnvironment(1, 1, "Tokenization") ++ Utils.logger
  val adapter       = Adapter.mockAdapterWithFactory(ClouseauTypeFactory)

  def runAnalyzerService(tokenizer: String, input: String) = {
    for {
      node   <- Utils.clouseauNode
      worker <- ZIO.service[EngineWorker]
      cfg    <- Utils.defaultConfig
      val ctx = new ServiceContext[ConfigurationArgs] { val args = ConfigurationArgs(cfg) }
      analyzer <- node.spawnServiceZIO[AnalyzerService, ConfigurationArgs](AnalyzerServiceBuilder.make(node, ctx))
      result <- analyzer
        .doTestCallTimeout(adapter.fromScala('analyze, tokenizer, input), 3.seconds)
        .delay(100.millis)
        .repeatUntil(_.isSuccess)
        .map(result => result.payload.get)
        .timeout(3.seconds)
      _ <- analyzer.exit(adapter.fromScala('normal))
    } yield (adapter.toScala(result.get) match {
      case ('ok, tokens: List[_]) => tokens
      case _                      => Nil
    })
  }

  val tokenizationSuite: Spec[Any, Throwable] = {
    suite("tokenization")(
      test("demonstrate standard tokenization")(
        for {
          tokens <- runAnalyzerService("standard", "foo bar baz")
        } yield assertTrue(tokens.equals(List("foo", "bar", "baz")))
      ),
      test("demonstrate keyword tokenization")(
        for {
          tokens <- runAnalyzerService("keyword", "foo bar baz")
        } yield assertTrue(tokens.equals(List("foo bar baz")))
      ),
      test("demonstrate simple_asciifolding tokenization")(
        for {
          tokens <- runAnalyzerService("simple_asciifolding", "Ayşegül Özbayır")
        } yield assertTrue(tokens.equals(List("aysegul", "ozbayir")))
      )
    ).provideLayer(environment) @@ TestAspect.withLiveClock @@ TestAspect.sequential
  }

  def spec: Spec[Any, Throwable] = {
    suite("AnalyzerServiceSpec")(
      tokenizationSuite
    ) @@ TestAspect.timeout(TIMEOUT_SUITE)
  }
}

/**
 * ```shell
 * rm artifacts/clouseau_*.jar ; make jartest
 * java -cp artifacts/clouseau_*_test.jar com.cloudant.ziose.clouseau.AnalyzerServiceSpecMain
 * ```
 */
object AnalyzerServiceSpecMain {
  def main(args: Array[String]): Unit = {
    TestRunner.runSpec("AnalyzerServiceSpec", new AnalyzerServiceSpec().spec)
  }
}
