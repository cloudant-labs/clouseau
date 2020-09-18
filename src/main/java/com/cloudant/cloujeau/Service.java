package com.cloudant.cloujeau;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import com.ericsson.otp.erlang.OtpConnection;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangPid;
import com.ericsson.otp.erlang.OtpErlangTuple;

public abstract class Service {

    private final Set<OtpErlangPid> links = new HashSet<OtpErlangPid>();

    protected final ServerState state;

    public Service(final ServerState state) {
        this.state = state;
    }

    public abstract OtpErlangObject handleCall(final OtpConnection conn, final OtpErlangTuple from,
            final OtpErlangObject request) throws Exception;

    public void terminate(final OtpErlangObject reason) {
        // Intentionally empty.
    }

    public final void link(final OtpErlangPid pid) {
        synchronized (links) {
            links.add(pid);
        }
    }

    public final void unlink(final OtpErlangPid pid) {
        synchronized (links) {
            links.remove(pid);
        }
    }

    public final void exit(final OtpConnection conn, final OtpErlangObject reason)
            throws IOException {
        synchronized (links) {
            final Iterator<OtpErlangPid> it = links.iterator();
            while (it.hasNext()) {
                final OtpErlangPid pid = it.next();
                conn.exit(pid, reason);
            }
            links.clear();
        }
        terminate(reason);
    }

}
