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

package com.cloudant.ziose.clouseau

import java.util.regex.Pattern

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.NumericRangeQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.util.Version
import org.apache.lucene.analysis.core.KeywordAnalyzer

class ClouseauQueryParser(version: Version,
                          defaultField: String,
                          analyzer: Analyzer)
    extends QueryParser(version, defaultField, analyzer) {

  // regexp from java.lang.Double
  val Digits = "(\\p{Digit}+)"
  val HexDigits = "(\\p{XDigit}+)"
  val Exp = "[eE][+-]?" + Digits
  val fpRegexStr = ("[\\x00-\\x20]*" + "[+-]?(" + "NaN|"
    + "Infinity|" + "((("
    + Digits
    + "(\\.)?("
    + Digits
    + "?)("
    + Exp
    + ")?)|"
    + "(\\.("
    + Digits
    + ")("
    + Exp
    + ")?)|"
    + "(("
    + "(0[xX]"
    + HexDigits
    + "(\\.)?)|"
    + "(0[xX]"
    + HexDigits
    + "?(\\.)"
    + HexDigits
    + ")"
    + ")[pP][+-]?" + Digits + "))" + "[fFdD]?))" + "[\\x00-\\x20]*")
  val fpRegex = Pattern.compile(fpRegexStr)

  override def getRangeQuery(field: String,
                             lower: String,
                             upper: String,
                             startInclusive: Boolean,
                             endInclusive: Boolean): Query = {
    val lb = Option(lower).getOrElse("-Infinity")
    val ub = Option(upper).getOrElse("Infinity")
    if (isNumber(lb) && isNumber(ub)) {
      NumericRangeQuery.newDoubleRange(
        field, 8, lb.toDouble, ub.toDouble, startInclusive, endInclusive)
    } else {
      setLowercaseExpandedTerms(field)
      super.getRangeQuery(field, lb, ub, startInclusive, endInclusive)
    }
  }

  override def getFieldQuery(field: String,
                             queryText: String,
                             quoted: Boolean): Query = {
    if (!quoted && isNumber(queryText)) {
      new TermQuery(Utils.doubleToTerm(field, queryText.toDouble))
    } else {
      super.getFieldQuery(field, queryText, quoted)
    }
  }

  override def getFuzzyQuery(field: String, termStr: String,
                             minSimilarity: Float): Query = {
    setLowercaseExpandedTerms(field)
    super.getFuzzyQuery(field, termStr, minSimilarity)
  }

  override def getPrefixQuery(field: String, termStr: String): Query = {
    setLowercaseExpandedTerms(field)
    super.getPrefixQuery(field, termStr)
  }

  override def getRegexpQuery(field: String, termStr: String): Query = {
    setLowercaseExpandedTerms(field)
    super.getRegexpQuery(field, termStr)
  }

  override def getWildcardQuery(field: String, termStr: String): Query = {
    setLowercaseExpandedTerms(field)
    super.getWildcardQuery(field, termStr)
  }

  private def isNumber(str: String): Boolean = {
    fpRegex.matcher(str).matches()
  }

  private def setLowercaseExpandedTerms(field: String): Unit = {
    getAnalyzer match {
      case a: PerFieldAnalyzer =>
        setLowercaseExpandedTerms(a.getWrappedAnalyzer(field))
      case _: Analyzer =>
        setLowercaseExpandedTerms(analyzer)
    }
  }

  private def setLowercaseExpandedTerms(analyzer: Analyzer): Unit = {
    setLowercaseExpandedTerms(!analyzer.isInstanceOf[KeywordAnalyzer])
  }

}
