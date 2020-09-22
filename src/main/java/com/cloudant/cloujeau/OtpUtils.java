package com.cloudant.cloujeau;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangFloat;
import com.ericsson.otp.erlang.OtpErlangInt;
import com.ericsson.otp.erlang.OtpErlangList;
import com.ericsson.otp.erlang.OtpErlangLong;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangPid;
import com.ericsson.otp.erlang.OtpErlangRangeException;
import com.ericsson.otp.erlang.OtpErlangRef;
import com.ericsson.otp.erlang.OtpErlangTuple;
import com.ericsson.otp.erlang.OtpMbox;

public final class OtpUtils {

    private static final Map<String, OtpErlangAtom> atoms = new ConcurrentHashMap<String, OtpErlangAtom>();

    private static final OtpErlangList EMPTY_LIST = new OtpErlangList();

    public static OtpErlangTuple tuple(final OtpErlangObject... items) {
        return new OtpErlangTuple(items);
    }

    public static OtpErlangAtom asAtom(final String val) {
        return atoms.computeIfAbsent(val, (v) -> new OtpErlangAtom(v));
    }

    public static OtpErlangObject asLong(final long val) {
        return new OtpErlangLong(val);
    }

    public static OtpErlangObject asFloat(final float val) {
        return new OtpErlangFloat(val);
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
                return asMap(t.elementAt(0));
            }
        }

        if (obj instanceof OtpErlangList) {
            final OtpErlangList list = (OtpErlangList) obj;
            final Map<OtpErlangObject, OtpErlangObject> result = new HashMap<OtpErlangObject, OtpErlangObject>();
            for (final OtpErlangObject i : list) {
                if (i instanceof OtpErlangTuple) {
                    result.put(((OtpErlangTuple) i).elementAt(0), ((OtpErlangTuple) i).elementAt(1));
                }
            }
            return result;
        }

        throw new IllegalArgumentException(obj + " not an encoded JSON object");
    }

    public static String asString(final OtpErlangObject obj) {
        if (obj == null) {
            return null;
        }
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

    public static boolean asBoolean(final OtpErlangObject obj) {
        if (obj instanceof OtpErlangAtom) {
            if (asAtom("true").equals(obj)) {
                return true;
            }
            if (asAtom("false").equals(obj)) {
                return false;
            }
        }
        throw new IllegalArgumentException(obj + " cannot be converted to boolean");
    }

    public static OtpErlangInt asInt(final int val) {
        return new OtpErlangInt(val);
    }

    public static int asInt(OtpErlangObject obj) {
        try {
            if (obj instanceof OtpErlangLong) {
                return ((OtpErlangLong) obj).intValue();
            }
            if (obj instanceof OtpErlangInt) {
                return ((OtpErlangInt) obj).intValue();
            }
        } catch (OtpErlangRangeException e) {
            throw new IllegalArgumentException("out of range", e);
        }
        throw new IllegalArgumentException(obj + " cannot be converted to int");
    }

    public static OtpErlangList asList(final OtpErlangObject... objs) {
        return new OtpErlangList(objs);
    }

    public static OtpErlangList emptyList() {
        return EMPTY_LIST;
    }

    public static void reply(final OtpMbox mbox, final OtpErlangTuple from, final OtpErlangObject reply)
            throws IOException {
        final OtpErlangPid fromPid = (OtpErlangPid) from.elementAt(0);
        final OtpErlangRef fromRef = (OtpErlangRef) from.elementAt(1);
        mbox.send(fromPid, tuple(fromRef, reply));
    }

}
