package com.cloudant.cloujeau;

import java.util.concurrent.ScheduledExecutorService;

import org.apache.commons.configuration.Configuration;

import com.ericsson.otp.erlang.OtpNode;

public final class ServerState {

    public ServerState(final Configuration config, final ScheduledExecutorService executor, final OtpNode node) {
        this.config = config;
        this.executor = executor;
        this.node = node;
    }

    public final Configuration config;

    public final ScheduledExecutorService executor;

    public final OtpNode node;

}
