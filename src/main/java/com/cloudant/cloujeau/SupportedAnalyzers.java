package com.cloudant.cloujeau;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.core.SimpleAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

import com.ericsson.otp.erlang.OtpErlangObject;

public class SupportedAnalyzers {

    public static Analyzer createAnalyzer(final OtpErlangObject analyzerConfig) {
        return createAnalyzerInt("standard");
    }

    private static Analyzer createAnalyzerInt(final String name) {
        switch (name) {
        case "keyword":
            return new KeywordAnalyzer();
        case "simple":
            return new SimpleAnalyzer(LuceneUtils.VERSION);
        case "standard":
            return new StandardAnalyzer(LuceneUtils.VERSION);
        default:
            return null;
        }
    }

}