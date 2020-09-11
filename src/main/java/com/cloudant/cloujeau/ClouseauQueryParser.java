package com.cloudant.cloujeau;

import java.util.regex.Pattern;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.Version;

public class ClouseauQueryParser extends QueryParser {

    private static final Pattern fpRegex;

    static {
        // regexp from java.lang.Double
        final String Digits = "(\\p{Digit}+)";
        final String HexDigits = "(\\p{XDigit}+)";
        final String Exp = "[eE][+-]?" + Digits;
        final String fpRegexStr = "[\\x00-\\x20]*" + "[+-]?(" + "NaN|" + "Infinity|" + "(((" + Digits + "(\\.)?("
                + Digits + "?)(" + Exp + ")?)|" + "(\\.(" + Digits + ")(" + Exp + ")?)|" + "((" + "(0[xX]" + HexDigits
                + "(\\.)?)|" + "(0[xX]" + HexDigits + "?(\\.)" + HexDigits + ")" + ")[pP][+-]?" + Digits + "))"
                + "[fFdD]?))" + "[\\x00-\\x20]*";
        fpRegex = Pattern.compile(fpRegexStr);
    }

    public ClouseauQueryParser(final Version version, final String defaultField, final Analyzer analyzer) {
        super(version, defaultField, analyzer);
    }

    @Override
    protected org.apache.lucene.search.Query getRangeQuery(final String field, final String lower, final String upper,
            final boolean startInclusive, final boolean endInclusive) throws ParseException {
        if (isNumber(lower) && isNumber(upper)) {
            return NumericRangeQuery.newDoubleRange(
                    field,
                    8,
                    Double.parseDouble(lower),
                    Double.parseDouble(upper),
                    startInclusive,
                    endInclusive);
        } else {
            setLowercaseExpandedTerms(field);
            return super.getRangeQuery(field, lower, upper, startInclusive, endInclusive);
        }
    }

    @Override
    protected org.apache.lucene.search.Query getFieldQuery(final String field, final String queryText,
            final boolean quoted) throws ParseException {
        if (!quoted && isNumber(queryText)) {
            return new TermQuery(LuceneUtils.doubleToTerm(field, Double.parseDouble(queryText)));
        } else {
            return super.getFieldQuery(field, queryText, quoted);
        }
    }

    @Override
    protected org.apache.lucene.search.Query getFuzzyQuery(final String field, final String termStr,
            final float minSimilarity) throws ParseException {
        setLowercaseExpandedTerms(field);
        return super.getFuzzyQuery(field, termStr, minSimilarity);
    }

    @Override
    protected org.apache.lucene.search.Query getPrefixQuery(final String field, final String termStr)
            throws ParseException {
        setLowercaseExpandedTerms(field);
        return super.getPrefixQuery(field, termStr);
    }

    @Override
    protected org.apache.lucene.search.Query getRegexpQuery(final String field, final String termStr)
            throws ParseException {
        setLowercaseExpandedTerms(field);
        return super.getRegexpQuery(field, termStr);
    }

    @Override
    protected org.apache.lucene.search.Query getWildcardQuery(final String field, final String termStr)
            throws ParseException {
        setLowercaseExpandedTerms(field);
        return super.getWildcardQuery(field, termStr);
    }

    private boolean isNumber(final String str) {
        return fpRegex.matcher(str).matches();
    }

    private void setLowercaseExpandedTerms(final String field) {
        final Analyzer analyzer = getAnalyzer();
        if (analyzer instanceof PerFieldAnalyzer) {
            final PerFieldAnalyzer perFieldAnalyzer = (PerFieldAnalyzer) analyzer;
            setLowercaseExpandedTerms(perFieldAnalyzer.getWrappedAnalyzer(field));
        } else {
            setLowercaseExpandedTerms(analyzer);
        }
    }

    private void setLowercaseExpandedTerms(final Analyzer analyzer) {
        setLowercaseExpandedTerms(!(analyzer instanceof KeywordAnalyzer));
    }

}
