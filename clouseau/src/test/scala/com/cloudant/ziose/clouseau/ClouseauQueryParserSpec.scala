/*
sbt 'clouseau/testOnly com.cloudant.ziose.clouseau.ClouseauQueryParserSpec'
 */
package com.cloudant.ziose.clouseau

import org.junit.runner.RunWith
import zio._
import zio.test.junit.{JUnitRunnableSpec, ZTestJUnitRunner}

import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.search._
import java.lang.{Double => JDouble}

import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect
import com.cloudant.ziose.test.helpers.TestRunner

@RunWith(classOf[ZTestJUnitRunner])
class ClouseauQueryParserSpec extends JUnitRunnableSpec {
  val TIMEOUT_SUITE = 5.minutes
  val analyzer      = new StandardAnalyzer(IndexService.version)
  def parser        = new ClouseauQueryParser(IndexService.version, "default", analyzer)

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
        assert(parser.parse("foo:[1 TO 2]"))(isSubtype[NumericRangeQuery[JDouble]](anything))
      ),
      test("support numeric range queries (float)")(
        assert(parser.parse("foo:[1.0 TO 2.0]"))(isSubtype[NumericRangeQuery[JDouble]](anything))
      ),
      test("support open-ended numeric range queries (*)")(
        assert(parser.parse("foo:[0.0 TO *]"))(isSubtype[NumericRangeQuery[JDouble]](anything))
      ),
      test("support open-ended numeric range queries (Infinity)")(
        assert(parser.parse("foo:[-Infinity TO 0]"))(isSubtype[NumericRangeQuery[JDouble]](anything))
      ),
      test("support numeric term queries (integer)") {
        val result = parser.parse("foo:12")
        assert(result)(isSubtype[TermQuery](anything)) &&
        assert(result.asInstanceOf[TermQuery].getTerm)(equalTo(Utils.doubleToTerm("foo", 12.0)))
      },
      test("support negative numeric term queries (integer)") {
        val result = parser.parse("foo:\\-12")
        assert(result)(isSubtype[TermQuery](anything)) &&
        assert(result.asInstanceOf[TermQuery].getTerm)(equalTo(Utils.doubleToTerm("foo", -12.0)))
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
