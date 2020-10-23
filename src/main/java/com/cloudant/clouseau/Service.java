package com.cloudant.clouseau;

import static com.cloudant.clouseau.OtpUtils.*;

import java.io.IOException;

import org.apache.log4j.Logger;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangDecodeException;
import com.ericsson.otp.erlang.OtpErlangExit;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangPid;
import com.ericsson.otp.erlang.OtpErlangTuple;
import com.ericsson.otp.erlang.OtpMbox;
import com.ericsson.otp.erlang.OtpMsg;

public abstract class Service {

    private static final Logger logger = Logger.getLogger("clouseau.main");

    private static final OtpErlangObject INVALID_MSG = tuple(atom("error"), atom("invalid_msg"));

    protected final ServerState state;
    private final OtpMbox mbox;

    public Service(final ServerState state, final String serviceName) {
        this(state, state.node.createMbox(serviceName));
    }

    public Service(final ServerState state) {
        this(state, state.node.createMbox());
    }

    private Service(final ServerState state, final OtpMbox mbox) {
        if (state == null) {
            throw new NullPointerException("state cannot be null");
        }
        if (mbox == null) {
            throw new NullPointerException("mbox cannot be null");
        }
        this.state = state;
        this.mbox = mbox;
    }

    public final void processMessages() {
        try {
            OtpMsg msg;
            while ((msg = mbox.receiveMsg(0L)) != null) {
                handleMsg(msg);
            }
        } catch (final OtpErlangExit e) {
            if (!trapExit(e)) {
                return;
            }
        } catch (final InterruptedException e) {
            return;
        }
    }

    private void handleMsg(final OtpMsg msg) {
        try {
            switch (msg.type()) {
            case OtpMsg.sendTag:
            case OtpMsg.regSendTag:
                final OtpErlangObject obj = msg.getMsg();

                if (obj instanceof OtpErlangTuple) {
                    final OtpErlangTuple tuple = (OtpErlangTuple) obj;
                    final OtpErlangAtom atom = (OtpErlangAtom) tuple.elementAt(0);

                    if (atom("$gen_call").equals(atom)) {
                        final OtpErlangTuple from = (OtpErlangTuple) tuple.elementAt(1);
                        final OtpErlangObject request = tuple.elementAt(2);

                        try {
                            final OtpErlangObject response = handleCall(from, request);
                            if (response != null) {
                                reply(from, response);
                            } else {
                                reply(from, INVALID_MSG);
                            }
                        } catch (final Exception e) {
                            final String err = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                            reply(from, tuple(atom("error"), asBinary(err)));
                            logger.error(this + " encountered exception during handleCall", e);
                        }
                        return;
                    }

                    if (atom("$gen_cast").equals(atom)) {
                        final OtpErlangObject request = tuple.elementAt(1);
                        try {
                            handleCast(request);
                        } catch (final Exception e) {
                            logger.error(this + " encountered exception during handleCast", e);
                        }
                        return;
                    }
                }
                try {
                    handleInfo(obj);
                } catch (final Exception e) {
                    logger.error(this + " encountered exception during handleInfo", e);
                }
                break;

            default:
                logger.warn("received message of unknown type " + msg.type());
            }
        } catch (final Error | IOException | OtpErlangDecodeException e) {
            logger.fatal(this + " encountered fatal error", e);
            System.exit(1);
        }
    }

    public OtpErlangObject handleCall(final OtpErlangTuple from, final OtpErlangObject request) throws Exception {
        return null;
    }

    public void handleCast(final OtpErlangObject request) throws Exception {
    }

    public void handleInfo(final OtpErlangObject request) throws Exception {
    }

    public void terminate(final OtpErlangObject reason) {
    }

    /**
     * Subclasses can trap exits of linked processes.
     *
     * @param e
     * @return true if exit was trapped, false if not.
     */
    public boolean trapExit(final OtpErlangExit e) {
        logger.error(String.format("%s exiting for reason %s", this, e.reason()));
        mbox.close();
        terminate(e.reason());
        return false;
    }

    public final void reply(final OtpErlangTuple from, final OtpErlangObject reply) throws IOException {
        OtpUtils.reply(mbox, from, reply);
    }

    public final void link(final OtpErlangPid pid) throws OtpErlangExit {
        mbox.link(pid);
    }

    public final void unlink(final OtpErlangPid pid) {
        mbox.unlink(pid);
    }

    public final void send(final String name, final OtpErlangObject msg) {
        mbox.send(name, msg);
    }

    public final void send(final String name, final String node, final OtpErlangObject msg) {
        mbox.send(name, node, msg);
    }

    public final void send(final OtpErlangPid to, final OtpErlangObject msg) {
        mbox.send(to, msg);
    }

    public final void exit(final OtpErlangPid to, final OtpErlangObject msg) {
        mbox.exit(to, msg);
    }

    public final void exit(final OtpErlangObject reason) {
        mbox.exit(reason);
        state.serviceRegistry.unregister(this);
        terminate(reason);
    }

    public final String getName() {
        return mbox.getName();
    }

    public final OtpErlangPid self() {
        return mbox.self();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((mbox == null) ? 0 : mbox.self().hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        Service other = (Service) obj;
        if (mbox == null) {
            if (other.mbox != null)
                return false;
        } else if (!mbox.self().equals(other.mbox.self()))
            return false;
        return true;
    }

    public String toString() {
        final String name = mbox.getName();
        if (name == null) {
            return String.format("Service(%s)", mbox.self());
        } else {
            return String.format("Service(%s)", name);
        }
    }
}
