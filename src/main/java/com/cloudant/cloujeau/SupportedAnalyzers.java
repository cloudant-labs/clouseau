package com.cloudant.cloujeau;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.core.SimpleAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

public class SupportedAnalyzers {

    public static Analyzer createAnalyzer(final Object analyzerConfig) {
        return createAnalyzerInt("standard");
    }

    private static Analyzer createAnalyzerInt(String name) {
        switch (name) {
        case "keyword":
            return new KeywordAnalyzer();
        case "simple":
            return new SimpleAnalyzer();
        case "standard":
            return new StandardAnalyzer();
        default:
            return null;
        }
    }


}