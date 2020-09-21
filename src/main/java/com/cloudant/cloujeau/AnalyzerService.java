package com.cloudant.cloujeau;

import static com.cloudant.cloujeau.OtpUtils.asAtom;
import static com.cloudant.cloujeau.OtpUtils.asBinary;
import static com.cloudant.cloujeau.OtpUtils.asString;
import static com.cloudant.cloujeau.OtpUtils.tuple;

import java.io.IOException;
import java.util.ArrayList;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangList;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangTuple;

public final class AnalyzerService extends Service {

    private static final OtpErlangBinary[] EMPTY = new OtpErlangBinary[0];

    public AnalyzerService(final ServerState state) {
        super(state, "analyzer");
    }

    @Override
    public OtpErlangObject handleCall(final OtpErlangTuple from, final OtpErlangObject request) throws IOException {
        if (request instanceof OtpErlangTuple) {
            final OtpErlangTuple tuple = (OtpErlangTuple) request;
            if (asAtom("analyze").equals(tuple.elementAt(0))) {
                final OtpErlangObject analyzerConfig = tuple.elementAt(1);
                final OtpErlangBinary text = (OtpErlangBinary) tuple.elementAt(2);
                final Analyzer analyzer = SupportedAnalyzers.createAnalyzer(analyzerConfig);
                if (analyzer != null) {
                    return tuple(asAtom("ok"), tokenize(asString(text), analyzer));
                } else {
                    return tuple(asAtom("error"), asAtom("no_such_analyzer"));
                }
            }
        }
        return null;
    }

    private static OtpErlangList tokenize(final String text, final Analyzer analyzer) throws IOException {
        final ArrayList<OtpErlangBinary> result = new ArrayList<OtpErlangBinary>();
        final TokenStream tokenStream = analyzer.tokenStream("default", text);
        try {
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                final CharTermAttribute term = tokenStream.getAttribute(CharTermAttribute.class);
                result.add(asBinary(term.toString()));
            }
            tokenStream.end();
        } finally {
            tokenStream.close();
        }
        return new OtpErlangList(result.toArray(EMPTY));
    }

}
