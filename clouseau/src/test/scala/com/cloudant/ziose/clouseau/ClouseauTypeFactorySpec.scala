package com.cloudant.ziose.clouseau

/*
 * sbt -DZIOSE_TEST_DEBUG=true "testOnly com.cloudant.ziose.clouseau.ClouseauTypeFactorySpec"
 */

import ClouseauTypeFactory._
import org.apache.lucene.document.Field._
import com.cloudant.ziose.core.Codec._
import com.cloudant.ziose.scalang.Adapter
import com.cloudant.ziose.test.helpers.Utils
import helpers.Generators._
import org.junit.runner.RunWith
import zio._
import zio.test._
import zio.test.Assertion._
import zio.test.junit._
import zio.ZIO._
import com.cloudant.ziose.test.helpers.TestRunner

@RunWith(classOf[ZTestJUnitRunner])
class ClouseauTypeFactorySpec extends JUnitRunnableSpec {
  val TIMEOUT_SUITE = 5.minutes
  val environment   = ZLayer.succeed(Clock.ClockLive) ++ ZLayer.succeed(Random.RandomLive) ++ Utils.logger
  val adapter       = Adapter.mockAdapterWithFactory(ClouseauTypeFactory)

  val termEncodingSuite: Spec[Any, Throwable] = {
    suite("TypeFactory term encoding")(
      test("Correctly create clouseau type from ETerm")(
        check(anyMessagePairGen(4)) { case (term, msg) =>
          for {
            _     <- logDebug(term.toString)
            event <- succeed(parse(term)(adapter))
          } yield assertTrue(
            event.isDefined,
            event.get.isInstanceOf[ClouseauMessage],
            event.get == msg
          )
        }
      ).provideLayer(environment),
      test("Undefined ClouseauMessage type should return None") {
        for {
          event <- succeed(parse(ETuple(EAtom("wrong")))(adapter))
        } yield assertTrue(event.isEmpty)
      }
    )
  }

  val legacySuite: Spec[Any, Throwable] = {
    suite("Legacy scenarios")(
      test("support true for store")(
        assert(toStore(Map("store" -> true)))(equalTo(Store.YES))
      ),
      test("support false for store")(
        assert(toStore(Map("store" -> false)))(equalTo(Store.NO))
      ),
      test("support all enumeration values for store") {
        val converted = Store.values.map(store => toStore(Map("store" -> store.name)))
        val names     = Store.values.map(store => Store.valueOf(store.name))
        assert(converted)(equalTo(names))
      },
      test("support all enumeration values for store (case insensitively)") {
        val converted = Store.values.map(store => toStore(Map("store" -> store.name.toLowerCase)))
        val names     = Store.values.map(store => Store.valueOf(store.name))
        assert(converted)(equalTo(names))
      },
      test("use the default if store string is not recognized")(
        assert(toStore(Map("store" -> "hello")))(equalTo(Store.NO))
      ),
      test("use the default if store value is not recognized")(
        assert(toStore(Map("store" -> 12)))(equalTo(Store.NO))
      ),
      test("support true for index")(
        assert(toIndex(Map("index" -> true)))(isTrue)
      ),
      test("support false for index")(
        assert(toIndex(Map("index" -> false)))(isFalse)
      ),
      test("support ANALYZED for index")(
        assert(toIndex(Map("index" -> "ANALYZED")))(isTrue) &&
        assert(toIndex(Map("index" -> "analyzed")))(isTrue)
      ),
      test("support NOT_ANALYZED for index")(
        assert(toIndex(Map("index" -> "NOT_ANALYZED")))(isFalse) &&
        assert(toIndex(Map("index" -> "not_analyzed")))(isFalse)
      ),
      test("support ANALYZED_NO_NORMS for index")(
        assert(toIndex(Map("index" -> "ANALYZED_NO_NORMS")))(isTrue) &&
        assert(toIndex(Map("index" -> "analyzed_no_norms")))(isTrue)
      ),
      test("support NOT_ANALYZED_NO_NORMS for index")(
        assert(toIndex(Map("index" -> "NOT_ANALYZED_NO_NORMS")))(isFalse) &&
        assert(toIndex(Map("index" -> "not_analyzed_no_norms")))(isFalse)
      ),
      test("use the default if index string is not recognized")(
        assert(toIndex(Map("index" -> "hello")))(isTrue)
      ),
      test("use the default if index value is not recognized")(
        assert(toIndex(Map("index" -> 12)))(isTrue)
      )
    )
  }

  def spec: Spec[Any, Throwable] = {
    suite("ClouseauTypeFactorySpec")(
      termEncodingSuite,
      legacySuite
    ) @@ TestAspect.timeout(TIMEOUT_SUITE)
  }
}

/**
 * ```shell
 * rm artifacts/clouseau_*.jar ; make jartest
 * java -cp artifacts/clouseau_*_test.jar com.cloudant.ziose.clouseau.ClouseauTypeFactorySpecMain
 * ```
 */
object ClouseauTypeFactorySpecMain {
  def main(args: Array[String]): Unit = {
    TestRunner.runSpec("ClouseauTypeFactorySpec", new ClouseauTypeFactorySpec().spec)
  }
}
