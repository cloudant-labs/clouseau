package com.cloudant.cloujeau;

import java.io.IOException;

import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangTuple;

public abstract class Service {

    public abstract OtpErlangObject handleCall(final OtpErlangTuple from, final OtpErlangObject request)
            throws IOException;

}
