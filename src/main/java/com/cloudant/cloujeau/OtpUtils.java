package com.cloudant.cloujeau;

import java.io.IOException;

import com.ericsson.otp.erlang.OtpConnection;
import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangPid;
import com.ericsson.otp.erlang.OtpErlangRef;
import com.ericsson.otp.erlang.OtpErlangTuple;

public class OtpUtils {

    public static final OtpErlangAtom GEN_CALL = atom("$gen_call");
    public static final OtpErlangAtom IS_AUTH = atom("is_auth");
    public static final OtpErlangAtom OK = atom("ok");
    public static final OtpErlangAtom YES = atom("yes");
    public static final OtpErlangAtom ERROR = atom("error");

    public static OtpErlangTuple tuple(OtpErlangObject... items) {
        return new OtpErlangTuple(items);
    }

    public static OtpErlangAtom atom(String val) {
        return new OtpErlangAtom(val);
    }

    public static void reply(OtpConnection conn, OtpErlangTuple from, OtpErlangObject reply) throws IOException {
        OtpErlangPid fromPid = (OtpErlangPid) from.elementAt(0);
        OtpErlangRef fromRef = (OtpErlangRef) from.elementAt(1);
        conn.send(fromPid, tuple(fromRef, reply));
    }

}
