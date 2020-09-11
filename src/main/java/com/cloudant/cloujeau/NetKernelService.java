package com.cloudant.cloujeau;

import static com.cloudant.cloujeau.OtpUtils.atom;

import java.io.IOException;

import com.ericsson.otp.erlang.OtpConnection;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangTuple;

public final class NetKernelService extends Service {

    public NetKernelService(final ServerState state) {
        super(state);
    }

    @Override
    public OtpErlangObject handleCall(final OtpConnection conn, final OtpErlangTuple from,
            final OtpErlangObject request) throws IOException {
        if (request instanceof OtpErlangTuple) {
            if (atom("is_auth").equals(((OtpErlangTuple) request).elementAt(0))) {
                // respond to a "ping".
                return atom("yes");
            }
        }
        return null;
    }

}
