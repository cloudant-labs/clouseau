package com.cloudant.cloujeau;

import org.apache.commons.configuration.Configuration;

import com.ericsson.otp.erlang.OtpNode;

public final class ServerState {

    public ServerState(final Configuration config, final OtpNode node) {
        this.config = config;
        this.node = node;
    }

    public final Configuration config;

    public final OtpNode node;

}
