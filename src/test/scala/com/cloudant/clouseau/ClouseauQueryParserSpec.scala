/*
 * Copyright 2012 Cloudant. All rights reserved.
 */

package com.cloudant.clouseau

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search._
import org.apache.lucene.util.NumericUtils
import org.specs._
import java.lang.{Double => JDouble}

class ClouseauQueryParserSpec extends SpecificationWithJUnit {
  "ClouseauQueryParser" should {
    var analyzer : Analyzer = null
    var parser : QueryParser = null

    doBefore {
      analyzer = new StandardAnalyzer(IndexService.version)
      parser = new ClouseauQueryParser(IndexService.version, "default", analyzer)
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

    "support numeric term queries (integer)" in {
      val query = parser.parse("foo:12")
      query must haveClass[TermQuery]
      query.asInstanceOf[TermQuery].getTerm() must be equalTo(Utils.doubleToTerm("foo", 12.0))
    }

    "quoted string is not a number" in {
      val query = parser.parse("foo:\"12\"")
      query must haveClass[TermQuery]
      query.asInstanceOf[TermQuery].getTerm().text() must be equalTo("12")
    }

  }
}
