package com.cloudant.cloujeau;

import static com.cloudant.cloujeau.OtpUtils.atom;
import static com.cloudant.cloujeau.OtpUtils.binaryToString;
import static com.cloudant.cloujeau.OtpUtils.stringToBinary;
import static com.cloudant.cloujeau.OtpUtils.tuple;

import java.io.IOException;
import java.util.ArrayList;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import com.ericsson.otp.erlang.OtpConnection;
import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangList;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangTuple;

public final class AnalyzerService extends Service {

    private static final OtpErlangBinary[] EMPTY = new OtpErlangBinary[0];

    public AnalyzerService(final ServerState state) {
        super(state);
    }

    @Override
    public OtpErlangObject handleCall(final OtpConnection conn, final OtpErlangTuple from,
            final OtpErlangObject request) throws IOException {
        if (request instanceof OtpErlangTuple) {
            final OtpErlangTuple tuple = (OtpErlangTuple) request;
            if (atom("analyze").equals(tuple.elementAt(0))) {
                final OtpErlangObject analyzerConfig = tuple.elementAt(1);
                final OtpErlangBinary text = (OtpErlangBinary) tuple.elementAt(2);
                final Analyzer analyzer = SupportedAnalyzers.createAnalyzer(analyzerConfig);
                if (analyzer != null) {
                    return tuple(atom("ok"), tokenize(binaryToString(text), analyzer));
                } else {
                    return tuple(atom("error"), atom("no_such_analyzer"));
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
                result.add(stringToBinary(term.toString()));
            }
            tokenStream.end();
        } finally {
            tokenStream.close();
        }
        return new OtpErlangList(result.toArray(EMPTY));
    }

}
