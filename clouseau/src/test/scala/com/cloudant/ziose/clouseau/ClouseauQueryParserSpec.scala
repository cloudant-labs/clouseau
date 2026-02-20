/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.ClouseauQueryParserSpec'
 */
package com.cloudant.ziose.clouseau

import org.junit.runner.RunWith
import zio._
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}

import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.search._

import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect
import com.cloudant.ziose.test.helpers.TestRunner

@RunWith(classOf[ZTestJUnitRunner])
class ClouseauQueryParserSpec extends JUnitRunnableSpec {
  val TIMEOUT_SUITE = 5.minutes
  val analyzer      = new StandardAnalyzer()
  def parser        = new ClouseauQueryParser("default", analyzer)

  def isNumericRangeQuery(result: Any) = {
    assert(result)(isSubtype[IndexOrDocValuesQuery](anything))
  }

  val queryParserSuite: Spec[Any, Throwable] = {
    suite("query parser")(
      test("support term queries")(
        assert(parser.parse("foo"))(isSubtype[TermQuery](anything))
      ),
      test("support boolean queries")(
        assert(parser.parse("foo AND bar"))(isSubtype[BooleanQuery](anything))
      ),
      test("support range queries")(
        assert(parser.parse("foo:[bar TO baz]"))(isSubtype[TermRangeQuery](anything))
      ),
      test("support numeric range queries (integer)")(
        isNumericRangeQuery(parser.parse("foo:[1 TO 2]"))
      ),
      test("support numeric range queries (float)")(
        isNumericRangeQuery(parser.parse("foo:[1.0 TO 2.0]"))
      ),
      test("support open-ended numeric range queries (*)")(
        isNumericRangeQuery(parser.parse("foo:[0.0 TO *]"))
      ),
      test("support open-ended numeric range queries (Infinity)")(
        isNumericRangeQuery(parser.parse("foo:[-Infinity TO 0]"))
      ),
      test("support exact numeric queries (integer)") {
        isNumericRangeQuery(parser.parse("foo:12"))
      },
      test("support exact negative numeric queries (integer)") {
        isNumericRangeQuery(parser.parse("foo:\\-12"))
      },
      test("support string is not a number") {
        val result = parser.parse("foo:\"12\"")
        assert(result)(isSubtype[TermQuery](anything)) &&
        assert(result.asInstanceOf[TermQuery].getTerm.text)(equalTo("12"))
      }
    )
  }

  def spec: Spec[Any, Throwable] = {
    suite("ClouseauQueryParserSpec")(
      queryParserSuite
    ) @@ TestAspect.timeout(TIMEOUT_SUITE)
  }
}

/**
 * ```shell
 * rm artifacts/clouseau_*.jar ; make jartest
 * java -cp artifacts/clouseau_*_test.jar com.cloudant.ziose.clouseau.ClouseauQueryParserSpecMain
 * ```
 */
object ClouseauQueryParserSpecMain {
  def main(args: Array[String]): Unit = {
    TestRunner.runSpec("ClouseauQueryParserSpec", new ClouseauQueryParserSpec().spec)
  }
}
