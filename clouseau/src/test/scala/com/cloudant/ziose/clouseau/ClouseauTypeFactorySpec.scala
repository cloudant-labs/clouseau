package com.cloudant.ziose.clouseau

/*
 * sbt -DZIOSE_TEST_DEBUG=true "testOnly com.cloudant.ziose.clouseau.ClouseauTypeFactorySpec"
 */

import ClouseauTypeFactory._
import com.cloudant.ziose.core.Codec._
import com.cloudant.ziose.scalang.Adapter
import com.cloudant.ziose.test.helpers.Utils
import helpers.Generators._
import org.junit.runner.RunWith
import zio._
import zio.test._
import zio.test.junit._
import zio.ZIO._

@RunWith(classOf[ZTestJUnitRunner])
class ClouseauTypeFactorySpec extends JUnitRunnableSpec {
  val logger      = Utils.logger
  val environment = ZLayer.succeed(Clock.ClockLive) ++ ZLayer.succeed(Random.RandomLive) ++ logger
  val adapter     = Adapter.mockAdapterWithFactory(ClouseauTypeFactory)

  def spec = {
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
}
