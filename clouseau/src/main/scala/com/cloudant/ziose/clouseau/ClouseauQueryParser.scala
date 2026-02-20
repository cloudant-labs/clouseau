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
import org.apache.lucene.search.Query
import org.apache.lucene.document.DoubleField
import org.apache.lucene.facet.range.DoubleRange

class ClouseauQueryParser(defaultField: String,
                          analyzer: Analyzer)
    extends QueryParser(defaultField, analyzer) {

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

  val doubleRangeStr = "^(?<minInc>[{\\[])(?<min>" + fpRegexStr + ") TO (?<max>" + fpRegexStr + ")(?<maxInc>[}\\]])$"
  val doubleRangeRegex = Pattern.compile(doubleRangeStr)

  override def getRangeQuery(field: String,
                             lower: String,
                             upper: String,
                             startInclusive: Boolean,
                             endInclusive: Boolean): Query = {
    val lb = Option(lower).getOrElse("-Infinity")
    val ub = Option(upper).getOrElse("Infinity")
    if (isNumber(lb) && isNumber(ub)) {
      var lv = lb.toDouble
      if (!startInclusive) {
        lv = Math.nextUp(lv)
      }
      var uv = ub.toDouble
      if (!endInclusive) {
        uv = Math.nextDown(uv)
      }
      DoubleField.newRangeQuery(field, lv, uv)
    } else {
      super.getRangeQuery(field, lb, ub, startInclusive, endInclusive)
    }
  }

  override def getFieldQuery(field: String,
                             queryText: String,
                             quoted: Boolean): Query = {
    if (!quoted && isNumber(queryText)) {
      DoubleField.newExactQuery(field, queryText.toDouble)
    } else {
      super.getFieldQuery(field, queryText, quoted)
    }
  }

  private def isNumber(str: String): Boolean = {
    fpRegex.matcher(str).matches()
  }

  def parseDoubleRange(field: String, value: String): Option[DoubleRange] = {
    val m = doubleRangeRegex.matcher(value)
    if (!m.matches()) {
      return None
    }
    Some(new DoubleRange(
      field,
      m.group("min").toDouble,
      m.group("minInc").equals("["),
      m.group("max").toDouble,
      m.group("maxInc").equals("]")))
  }

}
