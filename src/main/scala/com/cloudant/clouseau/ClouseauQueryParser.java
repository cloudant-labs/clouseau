// Copyright Cloudant 2012

package com.cloudant.clouseau;

import java.util.regex.Pattern;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.NumericUtils;
import org.apache.lucene.util.Version;

public final class ClouseauQueryParser extends QueryParser {

    // regexp from java.lang.Double
    private static final String Digits = "(\\p{Digit}+)";
    private static final String HexDigits = "(\\p{XDigit}+)";
    private static final String Exp = "[eE][+-]?"+Digits;
    private static final String fpRegex =
        ("[\\x00-\\x20]*" +
         "[+-]?(" +
         "NaN|" +
         "Infinity|" +
         "((("+Digits+"(\\.)?("+Digits+"?)("+Exp+")?)|"+
         "(\\.("+Digits+")("+Exp+")?)|"+
         "((" +
         "(0[xX]" + HexDigits + "(\\.)?)|" +
         "(0[xX]" + HexDigits + "?(\\.)" + HexDigits + ")" +
         ")[pP][+-]?" + Digits + "))" +
         "[fFdD]?))" +
         "[\\x00-\\x20]*");

    public ClouseauQueryParser(final Version version, final String defaultField, final Analyzer analyzer) {
        super(version, defaultField, analyzer);
    }

    @Override
    protected Query getRangeQuery(final String field, final String lower, final String upper, final boolean inclusive)
            throws ParseException {
        if (isNumber(lower) && isNumber(upper)) {
            return NumericRangeQuery.newDoubleRange(field, 8, Double.parseDouble(lower), Double.parseDouble(upper), inclusive, inclusive);
        }
        return super.getRangeQuery(field, lower, upper, inclusive);
    }

    @Override
    protected Query getFieldQuery(final String field, final String queryText, final boolean quoted)
        throws ParseException {
        if (isNumber(queryText)) {
            return new TermQuery(new Term(field, NumericUtils.doubleToPrefixCoded(Double.parseDouble(queryText))));
        }
        return super.getFieldQuery(field, queryText, quoted);
    }

    private boolean isNumber(final String str) {
        return Pattern.matches(fpRegex, str);
    }

}