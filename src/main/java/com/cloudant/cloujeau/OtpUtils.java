package com.cloudant.cloujeau;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangLong;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangPid;
import com.ericsson.otp.erlang.OtpErlangRef;
import com.ericsson.otp.erlang.OtpErlangTuple;
import com.ericsson.otp.erlang.OtpMbox;

public final class OtpUtils {

    private static final Map<String, OtpErlangAtom> atoms = new ConcurrentHashMap<String, OtpErlangAtom>();

    public static OtpErlangTuple tuple(final OtpErlangObject... items) {
        return new OtpErlangTuple(items);
    }

    public static OtpErlangAtom atom(final String val) {
        return atoms.computeIfAbsent(val, (v) -> new OtpErlangAtom(v));
    }

    public static OtpErlangLong _long(final long val) {
        return new OtpErlangLong(val);
    }

    public static String binaryToString(final OtpErlangBinary bin) {
        try {
            return new String(bin.binaryValue(), "UTF-8");
        } catch (final UnsupportedEncodingException e) {
            throw new Error("UTF-8 support missing");
        }
    }

    public static OtpErlangBinary stringToBinary(final String str) {
        try {
            return new OtpErlangBinary(str.getBytes("UTF-8"));
        } catch (final UnsupportedEncodingException e) {
            throw new Error("UTF-8 support missing");
        }
    }

    public static void reply(final OtpMbox mbox, final OtpErlangTuple from, final OtpErlangObject reply)
            throws IOException {
        final OtpErlangPid fromPid = (OtpErlangPid) from.elementAt(0);
        final OtpErlangRef fromRef = (OtpErlangRef) from.elementAt(1);
        mbox.send(fromPid, tuple(fromRef, reply));
    }

}
