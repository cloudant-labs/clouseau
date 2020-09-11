package com.cloudant.cloujeau;

import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.AnalyzerWrapper;

public class PerFieldAnalyzer extends AnalyzerWrapper {

    private final Analyzer defaultAnalyzer;
    private final Map<String, Analyzer> map;

    public PerFieldAnalyzer(final Analyzer defaultAnalyzer, final Map<String, Analyzer> map) {
        super(PER_FIELD_REUSE_STRATEGY);
        this.defaultAnalyzer = defaultAnalyzer;
        this.map = map;
    }

    @Override
    public Analyzer getWrappedAnalyzer(String fieldName) {
        return map.getOrDefault(fieldName, defaultAnalyzer);
    }

}
