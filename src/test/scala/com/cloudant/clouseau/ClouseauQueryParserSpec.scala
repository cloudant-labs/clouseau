/*
 * Copyright 2012 Cloudant. All rights reserved.
 */

package com.cloudant.clouseau

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search._
import org.specs2.mutable.SpecificationWithJUnit
import java.lang.{ Double => JDouble }
import org.specs2.specification.Scope

class ClouseauQueryParserSpec extends SpecificationWithJUnit {
  "ClouseauQueryParser" should {

    "support term queries" in new parser {
      parser.parse("foo") must haveClass[TermQuery]
    }

    "support boolean queries" in new parser {
      parser.parse("foo AND bar") must haveClass[BooleanQuery]
    }

    "support range queries" in new parser {
      parser.parse("foo:[bar TO baz]") must haveClass[TermRangeQuery]
    }

    "support numeric range queries (integer)" in new parser {
      parser.parse("foo:[1 TO 2]") must haveClass[NumericRangeQuery[JDouble]]
    }

    "support numeric range queries (float)" in new parser {
      parser.parse("foo:[1.0 TO 2.0]") must haveClass[NumericRangeQuery[JDouble]]
    }

    "support numeric term queries (integer)" in new parser {
      val query = parser.parse("foo:12")
      query must haveClass[TermQuery]
      query.asInstanceOf[TermQuery].getTerm() must be equalTo (Utils.doubleToTerm("foo", 12.0))
    }

    "support negative numeric term queries (integer)" in new parser {
      val query = parser.parse("foo:\\-12")
      query must haveClass[TermQuery]
      query.asInstanceOf[TermQuery].getTerm() must be equalTo (Utils.doubleToTerm("foo", -12.0))
    }

    "quoted string is not a number" in new parser {
      val query = parser.parse("foo:\"12\"")
      query must haveClass[TermQuery]
      query.asInstanceOf[TermQuery].getTerm().text() must be equalTo ("12")
    }

  }
}

trait parser extends Scope {
  val analyzer = new StandardAnalyzer(IndexService.version)
  val parser = new ClouseauQueryParser(IndexService.version, "default", analyzer)
}