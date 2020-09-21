package com.cloudant.cloujeau;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangList;
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

    public static OtpErlangAtom asAtom(final String val) {
        return atoms.computeIfAbsent(val, (v) -> new OtpErlangAtom(v));
    }

    public static OtpErlangObject asLong(final long val) {
        return new OtpErlangLong(val);
    }

    public static OtpErlangBinary asBinary(final String str) {
        try {
            return new OtpErlangBinary(str.getBytes("UTF-8"));
        } catch (final UnsupportedEncodingException e) {
            throw new Error("UTF-8 support missing");
        }
    }

    public static long asLong(final OtpErlangObject obj) {
        return ((OtpErlangLong) obj).longValue();
    }

    public static Map<OtpErlangObject, OtpErlangObject> asMap(final OtpErlangObject obj) {
        if (obj instanceof OtpErlangTuple) {
            final OtpErlangTuple t = (OtpErlangTuple) obj;
            if (t.arity() == 1 && t.elementAt(0) instanceof OtpErlangList) {
                final Map<OtpErlangObject, OtpErlangObject> result = new HashMap<OtpErlangObject, OtpErlangObject>();
                for (final OtpErlangObject i : (OtpErlangList) t.elementAt(0)) {
                    if (i instanceof OtpErlangTuple) {
                        result.put(((OtpErlangTuple) i).elementAt(0), ((OtpErlangTuple) i).elementAt(1));
                    }
                }
                return result;
            }
        }
        throw new IllegalArgumentException(obj + " not an encoded JSON object");
    }

    public static String asString(final OtpErlangObject obj) {
        if (obj instanceof OtpErlangBinary) {
            try {
                return new String(((OtpErlangBinary) obj).binaryValue(), "UTF-8");
            } catch (final UnsupportedEncodingException e) {
                throw new Error("UTF-8 support missing");
            }
        }
        if (obj instanceof OtpErlangAtom) {
            return ((OtpErlangAtom) obj).atomValue();
        }
        throw new IllegalArgumentException(obj + " cannot be converted to string");
    }

    public static void reply(final OtpMbox mbox, final OtpErlangTuple from, final OtpErlangObject reply)
            throws IOException {
        final OtpErlangPid fromPid = (OtpErlangPid) from.elementAt(0);
        final OtpErlangRef fromRef = (OtpErlangRef) from.elementAt(1);
        mbox.send(fromPid, tuple(fromRef, reply));
    }

}
