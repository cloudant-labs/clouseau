package com.cloudant.clouseau

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.queryParser.QueryParser
import org.apache.lucene.search._
import org.apache.lucene.util.Version._
import org.specs._
import java.lang.{Double => JDouble}

class ClouseauQueryParserSpec extends SpecificationWithJUnit {
  "ClouseauQueryParser" should {
    var analyzer : Analyzer = null
    var parser : QueryParser = null

    doBefore {
      analyzer = new StandardAnalyzer(LUCENE_36)
      parser = new ClouseauQueryParser(LUCENE_36, "default", analyzer)
    }

    "support term queries" in {
      parser.parse("foo") must haveClass[TermQuery]
    }

    "support boolean queries" in {
      parser.parse("foo AND bar") must haveClass[BooleanQuery]
    }

    "support range queries" in {
      parser.parse("foo:[bar TO baz]") must haveClass[TermRangeQuery]
    }

    "support numeric range queries (integer)" in {
      parser.parse("foo:[1 TO 2]") must haveClass[NumericRangeQuery[JDouble]]
    }

    "support numeric range queries (float)" in {
      parser.parse("foo:[1.0 TO 2.0]") must haveClass[NumericRangeQuery[JDouble]]
    }

  }
}
