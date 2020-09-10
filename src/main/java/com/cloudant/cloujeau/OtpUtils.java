package com.cloudant.cloujeau;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;

import com.ericsson.otp.erlang.OtpConnection;
import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangPid;
import com.ericsson.otp.erlang.OtpErlangRef;
import com.ericsson.otp.erlang.OtpErlangTuple;

public final class OtpUtils {

    private static final Map<String, OtpErlangAtom> atoms = new HashMap<String, OtpErlangAtom>();

    static {
        registerAtom("$gen_call");
        registerAtom("is_auth");
        registerAtom("yes");
        registerAtom("analyze");
        registerAtom("no_such_analyzer");
        registerAtom("noproc");
        registerAtom("ok");
        registerAtom("error");
    }

    public static OtpErlangTuple tuple(OtpErlangObject... items) {
        return new OtpErlangTuple(items);
    }

    public static OtpErlangAtom atom(String val) {
        return new OtpErlangAtom(val);
    }

    public static OtpErlangAtom existingAtom(String val) {
        return atoms.get(val);
    }

    public static String binaryToString(OtpErlangBinary bin) {
        try {
            return new String(bin.binaryValue(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new Error("UTF-8 support missing");
        }
    }

    public static OtpErlangBinary stringToBinary(final String str) {
        try {
            return new OtpErlangBinary(str.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new Error("UTF-8 support missing");
        }
    }

    public static void reply(OtpConnection conn, OtpErlangTuple from, OtpErlangObject reply) throws IOException {
        OtpErlangPid fromPid = (OtpErlangPid) from.elementAt(0);
        OtpErlangRef fromRef = (OtpErlangRef) from.elementAt(1);
        conn.send(fromPid, tuple(fromRef, reply));
    }

    private static void registerAtom(final String atom) {
        atoms.put(atom, new OtpErlangAtom(atom));
    }

}
