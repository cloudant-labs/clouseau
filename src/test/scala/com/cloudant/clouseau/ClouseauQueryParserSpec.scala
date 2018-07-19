// Licensed under the Apache License, Version 2.0 (the "License"); you may not
// use this file except in compliance with the License. You may obtain a copy of
// the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations under
// the License.

package com.cloudant.clouseau

import org.apache.lucene.analysis.standard.StandardAnalyzer
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
