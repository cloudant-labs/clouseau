package com.cloudant.ziose.clouseau

/*
 * sbt -DZIOSE_TEST_DEBUG=true "testOnly com.cloudant.ziose.clouseau.ClouseauAdapterSpec"
 */

import com.cloudant.ziose.core.Codec._
import com.cloudant.ziose.scalang.Adapter
import com.cloudant.ziose.test.helpers.Utils
import org.junit.runner.RunWith
import zio._
import zio.test._
import zio.test.junit._
import zio.ZIO._
import org.apache.lucene.util.BytesRef
import zio.test.Gen.alphaNumericString

@RunWith(classOf[ZTestJUnitRunner])
class ClouseauAdapterSpec extends JUnitRunnableSpec {
  val logger      = Utils.logger
  val environment = ZLayer.succeed(Clock.ClockLive) ++ ZLayer.succeed(Random.RandomLive) ++ logger
  val adapter     = Adapter.mockAdapterWithFactory(ClouseauTypeFactory)

  def spec = {
    suite("Adapter term encoding")(
      test("Correctly encode BytesRef into erlang binary")(
        check(for { xs <- alphaNumericString } yield EBinary(xs)) { case term: EBinary =>
          for {
            _ <- logDebug(term.toString)
            bytes = new BytesRef(term.asBytes)
            decoded <- succeed(adapter.fromScala(bytes))
          } yield assertTrue(
            decoded == term
          )
        }
      ).provideLayer(environment),
      test("Correctly encode null")(
        assertTrue(EAtom("null").asInstanceOf[ETerm] == adapter.fromScala(null))
      ),
      test("Correctly encode nested null")(
        assertTrue(
          EList(ETuple(EAtom("null"), EList(ENumber(0)))).asInstanceOf[ETerm] == adapter.fromScala(
            List((null, List(0)))
          )
        )
      )
    )
  }
}
