package com.cloudant.clouseau;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermRangeQuery;
import org.junit.Before;
import org.junit.Test;

public class ClouseauQueryParserTest {

    private QueryParser parser;

    @Before
    public void createParser() {
        parser = new ClouseauQueryParser(LuceneUtils.VERSION, "default", new StandardAnalyzer(LuceneUtils.VERSION));
    }

    @Test
    public void termQuery() throws Exception {
        assertThat(parser.parse("foo"), is(instanceOf(TermQuery.class)));
    }

    @Test
    public void booleanQuery() throws Exception {
        assertThat(parser.parse("foo AND bar"), is(instanceOf(BooleanQuery.class)));
    }

    @Test
    public void rangeQuery() throws Exception {
        assertThat(parser.parse("foo:[bar TO baz]"), is(instanceOf(TermRangeQuery.class)));
    }

    @Test
    public void numericRangeQuery() throws Exception {
        assertThat(parser.parse("foo:[1 TO 2]"), is(instanceOf(NumericRangeQuery.class)));
    }

    @Test
    public void numericFloatRangeQuery() throws Exception {
        assertThat(parser.parse("foo:[1.0 TO 2.0]"), is(instanceOf(NumericRangeQuery.class)));
    }

    @Test
    public void numericTermQuery() throws Exception {
        final Query query = parser.parse("foo:12");
        assertThat(query, is(instanceOf(TermQuery.class)));
        assertThat(((TermQuery) query).getTerm(), equalTo(LuceneUtils.doubleToTerm("foo", 12.0)));
    }

    @Test
    public void negativeNumericTermQuery() throws Exception {
        final Query query = parser.parse("foo:\\-12");
        assertThat(query, is(instanceOf(TermQuery.class)));
        assertThat(((TermQuery) query).getTerm(), equalTo(LuceneUtils.doubleToTerm("foo", -12.0)));
    }

    @Test
    public void quotedStringIsNotANumber() throws Exception {
        final Query query = parser.parse("foo:\"12\"");
        assertThat(query, is(instanceOf(TermQuery.class)));
        assertThat(((TermQuery) query).getTerm().text(), equalTo("12"));
    }

}
