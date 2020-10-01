package com.cloudant.cloujeau;

import java.util.concurrent.ScheduledExecutorService;

import org.apache.commons.configuration.Configuration;

import com.codahale.metrics.MetricRegistry;
import com.ericsson.otp.erlang.OtpNode;

public final class ServerState {

    public ServerState(final Configuration config, final OtpNode node, final ServiceRegistry serviceRegistry,
            final MetricRegistry metricRegistry, final ScheduledExecutorService scheduledExecutor) {
        this.config = config;
        this.node = node;
        this.serviceRegistry = serviceRegistry;
        this.metricRegistry = metricRegistry;
        this.scheduledExecutor = scheduledExecutor;
    }

    public final Configuration config;

    public final OtpNode node;

    public final ServiceRegistry serviceRegistry;

    public final MetricRegistry metricRegistry;

    public final ScheduledExecutorService scheduledExecutor;

}
