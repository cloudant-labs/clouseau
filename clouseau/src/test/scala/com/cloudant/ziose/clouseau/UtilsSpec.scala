/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.UtilsSpec'
 */
package com.cloudant.ziose.clouseau

import org.junit.runner.RunWith
import zio._
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}

import conversions._
import Utils.ensureElementsType
import scala.collection.mutable

import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect
import com.cloudant.ziose.test.helpers.TestRunner

@RunWith(classOf[ZTestJUnitRunner])
class UtilsSpec extends JUnitRunnableSpec {
  val TIMEOUT_SUITE = 5.minutes

  val asListOfStrings: PartialFunction[Any, List[String]] = ensureElementsType(
    { case string: String => string },
    { case (container, element) =>
      throw new Exception(container.toString + " contains non-string element " + element.toString)
    }
  )

  val extractor = asListOfStrings.Extractor

  val utilsSuite: Spec[Any, Throwable] = {
    suite("ensureElementsType")(
      test("throw exception when encounter non matching case") {
        val input = List("1", "2", 3, "4")

        assert(input match {
          case extractor(input) => ???
          case _                => ???
        })(throws(hasMessage(containsString("List(1, 2, 3, 4) contains non-string element 3"))))
      },
      test("skip processing of elements after first element of wrong type") {
        val input                             = List("1", "2", 3, "4")
        val recorder: mutable.ListBuffer[Any] = mutable.ListBuffer.empty
        val condition: PartialFunction[Any, List[String]] = ensureElementsType(
          {
            case string: String => {
              recorder += string
              string
            }
          },
          {
            case (_container, element) => {
              recorder += element
              throw new MatchError(element)
            }
          }
        )
        val extractor = condition.Extractor

        assert(input match {
          case extractor(input) => ???
          case _                => ???
        })(throwsA[MatchError]) &&
        assert(recorder)(equalTo(mutable.ListBuffer("1", "2", 3)))
      },
      test("usable when orElse doesn't throw exception") {
        /*
         This is not supported use case for the function.
         Because there is no way to distinguish the success and failure.

         This test is written only to verify that orElse function works correctly.
         */
        val condition: PartialFunction[Any, List[String]] = ensureElementsType(
          {
            case string: String => { string }
          },
          {
            case (container, element) => { List(element.toString) }
          }
        )
        val extractor = condition.Extractor

        assert(List("1", "2", "3", "4") match {
          case extractor(input) => input
          case _                => ???
        })(equalTo(List("1", "2", "3", "4"))) &&
        assert(List("1", "2", 3, "4") match {
          case extractor(input) => input
          case _                => ???
        })(equalTo(List("3"))) &&
        assert(List("1") match {
          case extractor(input) => input
          case _                => ???
        })(equalTo(List("1"))) &&
        assert(List(1) match {
          case extractor(input) => input
          case _                => ???
        })(equalTo(List("1")))
      }
    )
  }

  def spec: Spec[Any, Throwable] = {
    suite("UtilsSpec")(
      utilsSuite
    ) @@ TestAspect.timeout(TIMEOUT_SUITE)
  }
}

/**
 * ```shell
 * rm artifacts/clouseau_*.jar ; make jartest
 * java -cp artifacts/clouseau_*_test.jar com.cloudant.ziose.clouseau.UtilsSpecMain
 * ```
 */
object UtilsSpecMain {
  def main(args: Array[String]): Unit = {
    TestRunner.runSpec("UtilsSpec", new UtilsSpec().spec)
  }
}
