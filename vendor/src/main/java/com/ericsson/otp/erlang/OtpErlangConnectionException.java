package com.ericsson.otp.erlang;

public class OtpErlangConnectionException extends OtpErlangException {
    private static final long serialVersionUID = 1L;

    public OtpErlangConnectionException(Exception e) {
        super(e.getMessage());
    }
}
