package com.cloudant.cloujeau;

import static com.cloudant.cloujeau.OtpUtils.asAtom;
import static com.cloudant.cloujeau.OtpUtils.tuple;

import java.io.IOException;

import org.apache.log4j.Logger;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangExit;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangPid;
import com.ericsson.otp.erlang.OtpErlangTuple;
import com.ericsson.otp.erlang.OtpMbox;
import com.ericsson.otp.erlang.OtpMsg;

public abstract class Service implements Runnable {

    private static final Logger logger = Logger.getLogger("clouseau.main");

    private static final OtpErlangObject INVALID_MSG = tuple(asAtom("error"), asAtom("invalid_msg"));

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

    // Process all messages in the mailbox.
    public void run() {
        logger.info("Processing mailbox for " + this);
        try {
            OtpMsg msg = mbox.receiveMsg(0);
            while (msg != null) {
                try {
                    switch (msg.type()) {
                    case OtpMsg.sendTag:
                    case OtpMsg.regSendTag: {
                        final OtpErlangObject obj = msg.getMsg();
                        if (obj instanceof OtpErlangTuple) {
                            final OtpErlangTuple tuple = (OtpErlangTuple) obj;
                            final OtpErlangAtom atom = (OtpErlangAtom) tuple.elementAt(0);

                            // gen_call
                            if (asAtom("$gen_call").equals(atom)) {
                                final OtpErlangTuple from = (OtpErlangTuple) tuple.elementAt(1);
                                final OtpErlangObject request = tuple.elementAt(2);

                                final OtpErlangObject response = handleCall(from, request);
                                if (response != null) {
                                    reply(from, response);
                                } else {
                                    reply(from, INVALID_MSG);
                                }
                            }
                            // gen cast
                            else if (asAtom("$gen_cast").equals(atom)) {
                                final OtpErlangObject request = tuple.elementAt(1);
                                handleCast(request);
                            }
                        } else {
                            // handle info
                            handleInfo(obj);
                        }
                    }
                        break;

                    default:
                        logger.warn("received message of unknown type " + msg.type());
                    }
                } catch (Error e) {
                    logger.fatal(this + " encountered error", e);
                    System.exit(1);
                } catch (Exception e) {
                    logger.error(this + " encountered exception", e);
                }

                msg = mbox.receiveMsg(0);
            }
        } catch (OtpErlangExit e) {
            if (!asAtom("normal").equals(e.reason())) {
                logger.error(String.format("%s exiting for reason %s", this, e.reason()));
            }
            terminate(e.reason());
            mbox.close();
        } catch (InterruptedException e) {
            return;
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
        // Intentionally empty.
    }

    protected void reply(final OtpErlangTuple from, final OtpErlangObject reply) throws IOException {
        OtpUtils.reply(mbox, from, reply);
    }

    public final void link(final OtpErlangPid pid) throws OtpErlangExit {
        mbox.link(pid);
    }

    public final void unlink(final OtpErlangPid pid) {
        mbox.unlink(pid);
    }

    public final void exit(final OtpErlangObject reason) throws IOException {
        mbox.exit(reason);
        terminate(reason);
    }

    public OtpErlangPid self() {
        return mbox.self();
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
