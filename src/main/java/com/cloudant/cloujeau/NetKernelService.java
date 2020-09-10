package com.cloudant.cloujeau;

import static com.cloudant.cloujeau.OtpUtils.existingAtom;

import java.io.IOException;

import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangTuple;

public final class NetKernelService extends Service {

    @Override
    public OtpErlangObject handleCall(OtpErlangTuple from, OtpErlangObject request) throws IOException {
        if (request instanceof OtpErlangTuple) {
            if (existingAtom("is_auth").equals(((OtpErlangTuple) request).elementAt(0))) {
                // respond to a "ping".
                return existingAtom("yes");
            }
        }
        return null;
    }

}
