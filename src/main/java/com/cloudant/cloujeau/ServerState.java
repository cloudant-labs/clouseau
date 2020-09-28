package com.cloudant.cloujeau;

import java.util.concurrent.ScheduledExecutorService;

import org.apache.commons.configuration.Configuration;

import com.codahale.metrics.MetricRegistry;
import com.ericsson.otp.erlang.OtpNode;

public final class ServerState {

    public ServerState(final Configuration config, final ScheduledExecutorService executor, final OtpNode node,
            final MetricRegistry metricRegistry) {
        this.config = config;
        this.executor = executor;
        this.node = node;
        this.metricRegistry = metricRegistry;
    }

    public final Configuration config;

    public final ScheduledExecutorService executor;

    public final OtpNode node;

    public final MetricRegistry metricRegistry;

}
