/*
 ZIOSE_TEST_DEBUG=1 gradle clean :actors:test --tests 'com.cloudant.zio.actors.ClouseauTypeFactorySpec'
 */
package com.cloudant.zio.actors

import ClouseauTypeFactory._
import com.cloudant.zio.actors.Codec._
import helpers.Generators._
import org.junit.runner.RunWith
import zio._
import zio.test._
import zio.test.junit._
import zio.ZIO._

@RunWith(classOf[ZTestJUnitRunner])
class ClouseauTypeFactorySpec extends JUnitRunnableSpec {
  val logger = Runtime.addLogger(
    ZLogger.default.map(
      if (sys.env contains "ZIOSE_TEST_DEBUG") println else _ => null
    )
  )
  val environment = ZLayer.succeed(Clock.ClockLive) ++ ZLayer.succeed(Random.RandomLive) ++ logger

  def spec =
    suite("TypeFactory term encoding")(
      test("Correctly create clouseau type from ETerm")(
        check(anyMessagePairGen(4)) { case (term, msg) =>
          for {
            _     <- logDebug(term.toString)
            event <- succeed(parse(term))
          } yield assertTrue(event.isDefined) &&
            assertTrue(event.get.isInstanceOf[ClouseauMessage]) &&
            assertTrue(event.get == msg)
        }
      ).provideLayer(environment),
      test("Undefined ClouseauMessage type should return None") {
        for {
          event <- succeed(parse(ETuple(List(EAtom(Symbol("wrong"))))))
        } yield assertTrue(event.isEmpty)
      }
    )
}
