package com.cloudant.ziose.experiments

import zio.test.junit.ZTestJUnitRunner
import zio.test.junit.JUnitRunnableSpec
import org.junit.runner.RunWith
//import zio.test.DefaultRunnableSpec
import zio.test._
import zio.test.Assertion._
import zio.ZIO

@RunWith(classOf[ZTestJUnitRunner])
class HelloSpec extends JUnitRunnableSpec {
  def spec = suite("Population Exercise")(
    test("successful divide") {
      for {
        result <- Hello.divide(4, 2)
      } yield {
        assert(result)(equalTo(2))
      }
    },
    test("failed divide") {
      assert(Hello.divide(4, 0))(
        throws(isSubtype[ArithmeticException](anything))
      )
    }
  )
}

/*
gradle clean test --tests 'ziose.HelloSpec'
 */
