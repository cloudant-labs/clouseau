package com.cloudant.cloujeau;

import static java.util.Map.entry;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Map;

import com.ericsson.otp.erlang.OtpConnection;
import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangLong;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangPid;
import com.ericsson.otp.erlang.OtpErlangRef;
import com.ericsson.otp.erlang.OtpErlangTuple;

public final class OtpUtils {

    private static final Map<String, OtpErlangAtom> atoms = Map.ofEntries(
            registerAtom("$gen_call"),
            registerAtom("is_auth"),
            registerAtom("yes"),
            registerAtom("analyze"),
            registerAtom("no_such_analyzer"),
            registerAtom("noproc"),
            registerAtom("ok"),
            registerAtom("error"),
            registerAtom("open"),
            registerAtom("get_update_seq"));

    public static OtpErlangTuple tuple(final OtpErlangObject... items) {
        return new OtpErlangTuple(items);
    }

    public static OtpErlangAtom atom(final String val) {
        return atoms.get(val);
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

    public static void reply(final OtpConnection conn, final OtpErlangTuple from, final OtpErlangObject reply)
            throws IOException {
        final OtpErlangPid fromPid = (OtpErlangPid) from.elementAt(0);
        final OtpErlangRef fromRef = (OtpErlangRef) from.elementAt(1);
        conn.send(fromPid, tuple(fromRef, reply));
    }

    private static Map.Entry<String, OtpErlangAtom> registerAtom(final String name) {
        return entry(name, new OtpErlangAtom(name));
    }

}
