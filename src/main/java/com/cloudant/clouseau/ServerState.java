package com.cloudant.clouseau;

import java.util.concurrent.ScheduledExecutorService;

import org.apache.commons.configuration.Configuration;

import com.ericsson.otp.erlang.OtpNode;

public final class ServerState {

    public ServerState(final Configuration config, final OtpNode node, final ServiceRegistry serviceRegistry,
            final ScheduledExecutorService scheduledExecutor) {
        this.config = config;
        this.node = node;
        this.serviceRegistry = serviceRegistry;
        this.scheduledExecutor = scheduledExecutor;
    }

    public final Configuration config;

    public final OtpNode node;

    public final ServiceRegistry serviceRegistry;

    public final ScheduledExecutorService scheduledExecutor;

}
