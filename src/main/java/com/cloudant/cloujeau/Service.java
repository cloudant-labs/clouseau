package com.cloudant.cloujeau;

import com.ericsson.otp.erlang.OtpConnection;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangTuple;

public abstract class Service {

    protected final ServerState state;

    public Service(final ServerState state) {
        this.state = state;
    }

    public abstract OtpErlangObject handleCall(final OtpConnection conn, final OtpErlangTuple from,
            final OtpErlangObject request) throws Exception;

}
