package com.cloudant.cloujeau;

import static com.cloudant.cloujeau.OtpUtils.binaryToString;
import static com.cloudant.cloujeau.OtpUtils.existingAtom;
import static com.cloudant.cloujeau.OtpUtils.stringToBinary;
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

    @Override
    public OtpErlangObject handleCall(final OtpErlangTuple from, final OtpErlangObject request) throws IOException {
        if (request instanceof OtpErlangTuple) {
            OtpErlangTuple tuple = (OtpErlangTuple) request;
            if (existingAtom("analyze").equals(tuple.elementAt(0))) {
                final OtpErlangObject analyzerConfig = tuple.elementAt(1);
                final OtpErlangBinary text = (OtpErlangBinary) tuple.elementAt(2);
                Analyzer analyzer = SupportedAnalyzers.createAnalyzer(analyzerConfig);
                if (analyzer != null) {
                    return tuple(existingAtom("ok"), tokenize(binaryToString(text), analyzer));
                } else {
                    return tuple(existingAtom("error"), existingAtom("no_such_analyzer"));
                }
            }
        }
        return null;
    }

    private static OtpErlangList tokenize(String text, Analyzer analyzer) throws IOException {
        ArrayList<OtpErlangBinary> result = new ArrayList<OtpErlangBinary>();
        TokenStream tokenStream = analyzer.tokenStream("default", text);
        try {
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                CharTermAttribute term = tokenStream.getAttribute(CharTermAttribute.class);
                result.add(stringToBinary(term.toString()));
            }
            tokenStream.end();
        } finally {
            tokenStream.close();
        }
        return new OtpErlangList(result.toArray(EMPTY));
    }

}
