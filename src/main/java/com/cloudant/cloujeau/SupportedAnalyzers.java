package com.cloudant.cloujeau;

import java.util.Map;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.core.SimpleAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangObject;
import static com.cloudant.cloujeau.OtpUtils.*;

public class SupportedAnalyzers {

    public static Analyzer createAnalyzer(final OtpErlangObject analyzerConfig) {
        final Analyzer analyzer = createAnalyzerInt(analyzerConfig);

        if (analyzer == null) {
            return null;
        }

        if (analyzer instanceof PerFieldAnalyzer) {
            return analyzer;
        }

        return new PerFieldAnalyzer(analyzer,
                Map.of("_id", new KeywordAnalyzer(), "_partition", new KeywordAnalyzer()));
    }

    private static Analyzer createAnalyzerInt(final Object analyzerConfig) {
        if (analyzerConfig instanceof OtpErlangBinary) {
            return createAnalyzerInt(Map.of("name", binaryToString((OtpErlangBinary) analyzerConfig)));
        }

        if (analyzerConfig instanceof Map) {
            final String name = (String) ((Map) analyzerConfig).get("name");

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

        return null;
    }

}