package com.cloudant.cloujeau;

import static com.cloudant.cloujeau.OtpUtils.ERROR;
import static com.cloudant.cloujeau.OtpUtils.OK;
import static com.cloudant.cloujeau.OtpUtils.atom;
import static com.cloudant.cloujeau.OtpUtils.tuple;

import java.io.IOException;
import java.util.ArrayList;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangList;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangString;

public class AnalyzerService {

    private static final OtpErlangAtom NO_SUCH_ANALYZER = atom("no_such_analyzer");

    public static OtpErlangObject analyze(final Object analyzerConfig, final String text) throws IOException {
        Analyzer analyzer = SupportedAnalyzers.createAnalyzer(analyzerConfig);
        if (analyzer != null) {
            return tuple(OK, tokenize(text, analyzer));
        } else {
            return tuple(ERROR, NO_SUCH_ANALYZER);
        }
    }

    private static OtpErlangList tokenize(String text, Analyzer analyzer) throws IOException {
        ArrayList<OtpErlangString> result = new ArrayList<OtpErlangString>();
        TokenStream tokenStream = analyzer.tokenStream("default", text);
        try {
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                CharTermAttribute term = tokenStream.getAttribute(CharTermAttribute.class);
                result.add(new OtpErlangString(term.toString()));
            }
            tokenStream.end();
        } finally {
            tokenStream.close();
        }
        return new OtpErlangList((OtpErlangObject[]) result.toArray());
    }

}
