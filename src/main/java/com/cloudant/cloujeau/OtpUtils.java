package com.cloudant.cloujeau;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangRef;
import com.ericsson.otp.erlang.OtpErlangTuple;

public class OtpUtils {

    public static final OtpErlangAtom GEN_CALL = atom("$gen_call");
    public static final OtpErlangAtom IS_AUTH = atom("is_auth");
    public static final OtpErlangAtom OK = atom("ok");
    public static final OtpErlangAtom YES = atom("yes");
    public static final OtpErlangAtom ERROR = atom("error");

    public static OtpErlangTuple genCallReply(OtpErlangRef ref, OtpErlangObject reply) {
        return tuple(ref, reply);
    }

    public static OtpErlangTuple tuple(OtpErlangObject... items) {
        return new OtpErlangTuple(items);
    }

    public static OtpErlangAtom atom(String val) {
        return new OtpErlangAtom(val);
    }

}
