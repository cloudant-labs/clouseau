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

import java.lang.{ StringBuilder, String }
import org.apache.lucene.search._

object QueryExplainer {

  def explain(query: Query): String = {
    val builder: StringBuilder = new StringBuilder(300)
    explain(builder, query)
    builder.toString
  }

  private def planBooleanQuery(builder: StringBuilder, query: BooleanQuery) {
    for (clause <- query.getClauses) {
      builder.append(clause.getOccur)
      explain(builder, clause.getQuery)
      builder.append(" ")
    }
    builder.setLength(builder.length - 1)
  }

  private def planFuzzyQuery(builder: StringBuilder, query: FuzzyQuery) {
    builder.append(query.getTerm)
    builder.append(",prefixLength=")
    builder.append(query.getPrefixLength)
    builder.append(",maxEdits=")
    builder.append(query.getMaxEdits)
  }

  private def planNumericRangeQuery(builder: StringBuilder, query: NumericRangeQuery[_]) {
    builder.append(query.getMin)
    builder.append(" TO ")
    builder.append(query.getMax)
    builder.append(" AS ")
    builder.append(query.getMin.getClass.getSimpleName)
  }

  private def planPrefixQuery(builder: StringBuilder, query: PrefixQuery) {
    builder.append(query.getPrefix)
  }

  private def planTermQuery(builder: StringBuilder, query: TermQuery) {
    builder.append(query.getTerm)
  }

  private def planTermRangeQuery(builder: StringBuilder, query: TermRangeQuery) {
    builder.append(query.getLowerTerm.utf8ToString)
    builder.append(" TO ")
    builder.append(query.getUpperTerm.utf8ToString)
  }

  private def planWildcardQuery(builder: StringBuilder, query: WildcardQuery) {
    builder.append(query.getTerm)
  }

  private def explain(builder: StringBuilder, query: Query) {
    builder.append(query.getClass.getSimpleName)
    builder.append("(")
    query match {
      case query: TermQuery =>
        planTermQuery(builder, query)
      case query: BooleanQuery =>
        planBooleanQuery(builder, query)
      case query: TermRangeQuery =>
        planTermRangeQuery(builder, query)
      case query: PrefixQuery =>
        planPrefixQuery(builder, query)
      case query: WildcardQuery =>
        planWildcardQuery(builder, query)
      case query: FuzzyQuery =>
        planFuzzyQuery(builder, query)
      case query: NumericRangeQuery[_] =>
        planNumericRangeQuery(builder, query)
      case _ =>
        builder.append(query)
    }
    builder.append(",boost=" + query.getBoost + ")")
  }
}
