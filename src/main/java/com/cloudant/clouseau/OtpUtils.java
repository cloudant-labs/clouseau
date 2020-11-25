package com.cloudant.clouseau;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.lucene.util.BytesRef;

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
import com.ericsson.otp.erlang.OtpErlangShort;
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
        if (Float.isNaN(val)) {
            return atom("nan");
        }
        if (val == Float.POSITIVE_INFINITY) {
            return atom("infinity");
        }
        if (val == Float.NEGATIVE_INFINITY) {
            return atom("-infinity");
        }
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

    public static OtpErlangList asList(final List<OtpErlangObject> list) {
        return new OtpErlangList(list.toArray(new OtpErlangObject[list.size()]));
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

    public static Set<String> asSetOfStrings(final OtpErlangList list) {
        if (list == null) {
            return null;
        }
        final Set<String> result = new HashSet<String>();
        for (final OtpErlangObject item : list) {
            result.add(asString(item));
        }
        return result;
    }

    public static OtpErlangLong asLong(final long val) {
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

    public static Object asJava(final OtpErlangObject obj) {
        if (obj == null) {
            return null;
        }
        if (isAtomNil(obj)) {
            return null;
        }
        if (obj instanceof OtpErlangAtom) {
            return ((OtpErlangAtom) obj).atomValue();
        }
        if (obj instanceof OtpErlangBinary) {
            return asString((OtpErlangBinary) obj);
        }
        if (obj instanceof OtpErlangDouble) {
            return ((OtpErlangDouble) obj).doubleValue();
        }
        if (obj instanceof OtpErlangFloat) {
            try {
                return ((OtpErlangFloat) obj).floatValue();
            } catch (final OtpErlangRangeException e) {
                throw new Error("cannot happen", e);
            }
        }
        if (obj instanceof OtpErlangInt) {
            try {
                return ((OtpErlangInt) obj).intValue();
            } catch (final OtpErlangRangeException e) {
                throw new Error("cannot happen", e);
            }
        }
        if (obj instanceof OtpErlangLong) {
            return ((OtpErlangLong) obj).longValue();
        }
        if (obj instanceof OtpErlangShort) {
            try {
                return ((OtpErlangShort) obj).shortValue();
            } catch (final OtpErlangRangeException e) {
                throw new Error("cannot happen", e);
            }
        }
        if (obj instanceof OtpErlangList) {
            final OtpErlangList list = (OtpErlangList) obj;
            final List<Object> result = new ArrayList<Object>(list.arity());
            for (int i = 0; i < list.arity(); i++) {
                result.add(asJava(list.elementAt(i)));
            }
            return result;
        }
        throw new IllegalArgumentException(obj.getClass() + " cannot be converted to Java primitive");
    }

    public static OtpErlangObject asOtp(final Object obj) {
        if (obj == null) {
            return atom("null");
        }
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
        if (obj instanceof BytesRef) {
            final BytesRef bytesRef1 = (BytesRef) obj;
            final BytesRef bytesRef2;
            if (bytesRef1.offset > 0) {
                bytesRef2 = BytesRef.deepCopyOf(bytesRef1);
            } else {
                bytesRef2 = bytesRef1;
            }
            return new OtpErlangBinary(bytesRef2.bytes);
        }
        if (obj instanceof List) {
            final List<?> from = (List<?>) obj;
            final OtpErlangObject[] to = new OtpErlangObject[from.size()];
            for (int i = 0; i < to.length; i++) {
                to[i] = asOtp(from.get(i));
            }
            return new OtpErlangList(to);
        }
        if (obj instanceof Object[]) {
            final Object[] from = (Object[]) obj;
            final OtpErlangObject[] to = new OtpErlangObject[from.length];
            for (int i = 0; i < to.length; i++) {
                to[i] = asOtp(from[i]);
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
        throw new IllegalArgumentException(obj.getClass() + " cannot be converted to otp");
    }

    public static String asString(final OtpErlangObject obj) {
        if (obj == null) {
            return null;
        }
        if (isAtomNil(obj)) {
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

    public static BytesRef asBytesRef(final OtpErlangObject obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof OtpErlangBinary) {
            final OtpErlangBinary bin = (OtpErlangBinary) obj;
            return new BytesRef(bin.binaryValue());
        }
        throw new IllegalArgumentException(obj.getClass() + " cannot be converted to BytesRef");
    }

    public static OtpErlangList emptyList() {
        return EMPTY_LIST;
    }

    public static OtpErlangList props(final OtpErlangObject obj) {
        if (obj instanceof OtpErlangTuple && ((OtpErlangTuple) obj).arity() == 1) {
            final OtpErlangObject props = ((OtpErlangTuple) obj).elementAt(0);
            if (props instanceof OtpErlangList) {
                return (OtpErlangList) props;
            }
        }
        return null;
    }

    private static boolean isAtomNil(final OtpErlangObject obj) {
        return atom("nil").equals(obj);
    }

    @SuppressWarnings("unchecked")
    public static <T extends OtpErlangObject> T nilToNull(final OtpErlangObject obj) {
        if (isAtomNil(obj)) {
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
