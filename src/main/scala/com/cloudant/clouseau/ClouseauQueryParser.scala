// Copyright Cloudant 2012

package com.cloudant.clouseau

import java.util.regex.Pattern

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.queryparser.classic.ParseException
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.NumericRangeQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.util.Version

class ClouseauQueryParser(version : Version, defaultField : String, analyzer : Analyzer)
  extends QueryParser(version, defaultField, analyzer) {

  // regexp from java.lang.Double
  val Digits = "(\\p{Digit}+)"
  val HexDigits = "(\\p{XDigit}+)"
  val Exp = "[eE][+-]?" + Digits
  val fpRegex = ("[\\x00-\\x20]*" + "[+-]?(" + "NaN|"
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

    override def getRangeQuery(field : String, lower : String, upper : String, startInclusive : Boolean, endInclusive : Boolean) : Query = {
      if (isNumber(lower) && isNumber(upper)) {
        NumericRangeQuery.newDoubleRange(field, 8, lower.toDouble, upper.toDouble, startInclusive, endInclusive)
      } else {
        super.getRangeQuery(field, lower, upper, startInclusive, endInclusive)
      }
    }

    override def getFieldQuery(field : String, queryText : String, quoted : Boolean) : Query = {
      if (!quoted && isNumber(queryText)) {
        new TermQuery(Utils.doubleToTerm(field, queryText.toDouble))
      } else {
        super.getFieldQuery(field, queryText, quoted)
      }
    }

    private def isNumber(str : String) : Boolean = {
      Pattern.matches(fpRegex, str)
    }

}
