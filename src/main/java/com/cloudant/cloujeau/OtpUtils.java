package com.cloudant.cloujeau;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangBoolean;
import com.ericsson.otp.erlang.OtpErlangDouble;
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

    public static String[] asArrayOfStrings(final OtpErlangList list) {
        if (list == null) {
            return null;
        }
        return asListOfStrings(list).toArray(new String[list.arity()]);
    }

    public static OtpErlangAtom atom(final String val) {
        return atoms.computeIfAbsent(val, v -> new OtpErlangAtom(v));
    }

    public static OtpErlangBinary asBinary(final String str) {
        try {
            return new OtpErlangBinary(str.getBytes("UTF-8"));
        } catch (final UnsupportedEncodingException e) {
            throw new Error("UTF-8 support missing");
        }
    }

    public static boolean asBoolean(final OtpErlangObject obj) {
        if (obj instanceof OtpErlangAtom) {
            if (atom("true").equals(obj)) {
                return true;
            }
            if (atom("false").equals(obj)) {
                return false;
            }
        }
        throw new IllegalArgumentException(obj + " cannot be converted to boolean");
    }

    public static OtpErlangObject asFloat(final float val) {
        return new OtpErlangFloat(val);
    }

    public static float asFloat(final OtpErlangObject obj) {
        try {
            if (obj instanceof OtpErlangFloat) {
                return ((OtpErlangFloat) obj).floatValue();
            }
            if (obj instanceof OtpErlangDouble) {
                return ((OtpErlangDouble) obj).floatValue();
            }
        } catch (final OtpErlangRangeException e) {
            throw new IllegalArgumentException("out of range", e);
        }
        throw new IllegalArgumentException(obj + " cannot be converted to float");
    }

    public static OtpErlangInt asInt(final int val) {
        return new OtpErlangInt(val);
    }

    public static int asInt(final OtpErlangObject obj) {
        try {
            if (obj instanceof OtpErlangLong) {
                return ((OtpErlangLong) obj).intValue();
            }
            if (obj instanceof OtpErlangInt) {
                return ((OtpErlangInt) obj).intValue();
            }
        } catch (final OtpErlangRangeException e) {
            throw new IllegalArgumentException("out of range", e);
        }
        throw new IllegalArgumentException(obj + " cannot be converted to int");
    }

    public static OtpErlangList asList(final OtpErlangObject... objs) {
        return new OtpErlangList(objs);
    }

    public static List<String> asListOfStrings(final OtpErlangList list) {
        if (list == null) {
            return null;
        }
        final List<String> result = new ArrayList<String>();
        for (final OtpErlangObject item : list) {
            result.add(asString(item));
        }
        return result;
    }

    public static OtpErlangObject asLong(final long val) {
        return new OtpErlangLong(val);
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

    public static OtpErlangObject asOtp(final Object obj) {
        if (obj instanceof OtpErlangObject) {
            return (OtpErlangObject) obj;
        }
        if (obj instanceof Double) {
            return new OtpErlangDouble((Double) obj);
        }
        if (obj instanceof Float) {
            return new OtpErlangFloat((Float) obj);
        }
        if (obj instanceof Integer) {
            return new OtpErlangInt((Integer) obj);
        }
        if (obj instanceof Long) {
            return new OtpErlangLong((Long) obj);
        }
        if (obj instanceof Boolean) {
            return new OtpErlangBoolean((Boolean) obj);
        }
        if (obj instanceof String) {
            return asBinary((String) obj);
        }
        if (obj instanceof List) {
            final List<?> from = (List<?>) obj;
            final OtpErlangObject[] to = new OtpErlangObject[from.size()];
            for (int i = 0; i < to.length; i++) {
                to[i] = asOtp(from.get(i));
            }
            return new OtpErlangList(to);
        }
        if (obj instanceof Map) {
            final Map<?, ?> from = (Map<?, ?>) obj;
            final OtpErlangObject[] to = new OtpErlangObject[from.size()];
            int i = 0;
            for (final Map.Entry<?, ?> entry : from.entrySet()) {
                to[i++] = tuple(asOtp(entry.getKey()), asOtp(entry.getValue()));
            }
            return new OtpErlangList(to);
        }
        throw new IllegalArgumentException(obj + " cannot be converted to otp");
    }

    public static String asString(final OtpErlangObject obj) {
        if (obj == null) {
            return null;
        }
        if (isNil(obj)) {
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
        return obj.toString();
    }

    public static OtpErlangList emptyList() {
        return EMPTY_LIST;
    }

    private static boolean isNil(final OtpErlangObject obj) {
        return atom("nil").equals(obj);
    }

    @SuppressWarnings("unchecked")
    public static <T extends OtpErlangObject> T nilToNull(final OtpErlangObject obj) {
        if (isNil(obj)) {
            return null;
        }
        return (T) obj;
    }

    public static void reply(final OtpMbox mbox, final OtpErlangTuple from, final OtpErlangObject reply)
            throws IOException {
        final OtpErlangPid fromPid = (OtpErlangPid) from.elementAt(0);
        final OtpErlangRef fromRef = (OtpErlangRef) from.elementAt(1);
        mbox.send(fromPid, tuple(fromRef, reply));
    }

    public static OtpErlangTuple tuple(final OtpErlangObject... items) {
        return new OtpErlangTuple(items);
    }

}
