package com.cloudant.clouseau;

import static com.cloudant.clouseau.OtpUtils.*;
import static com.cloudant.clouseau.OtpUtils.tuple;

import com.ericsson.otp.erlang.OtpErlangObject;

/**
 * Throw one of these to send a neatly formatted reply.
 */
public final class OtpReplyException extends RuntimeException {

    private static final long serialVersionUID = -7769757312867722694L;

    private final String errorType;
    private final String reason;

    public OtpReplyException(final String reason, final Throwable cause) {
        super(cause);
        this.errorType = null;
        this.reason = reason;
    }

    public OtpReplyException(final String errorType, final String reason, final Throwable cause) {
        super(cause);
        this.errorType = errorType;
        this.reason = reason;
    }

    public OtpErlangObject getReply() {
        final OtpErlangObject msg;
        if (errorType == null) {
            msg = asBinary(reason);
        } else {
            msg = tuple(atom(errorType), asBinary(reason));
        }
        return tuple(atom("error"), msg);
    }

    @Override
    public String getMessage() {
        return String.format("errorType=%s, reason=%s", errorType, reason);
    }

}
