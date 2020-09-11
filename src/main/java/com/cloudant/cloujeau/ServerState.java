package com.cloudant.cloujeau;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.configuration.Configuration;

import com.ericsson.otp.erlang.OtpErlangPid;
import com.ericsson.otp.erlang.OtpSelf;

public final class ServerState {

    private final Map<OtpErlangPid, Service> services = new ConcurrentHashMap<OtpErlangPid, Service>();

    private final Map<String, Service> namedServices = new ConcurrentHashMap<String, Service>();

    public ServerState(final Configuration config, final OtpSelf self) {
        this.config = config;
        this.self = self;
    }

    public final Configuration config;

    public final OtpSelf self;
    
    public void addService(final OtpErlangPid pid, final Service service) {
        services.put(pid, service);
    }

    public void removeService(final OtpErlangPid pid) {
        services.remove(pid);
    }

    public Service getService(final OtpErlangPid pid) {
        return services.get(pid);
    }

    public void addNamedService(final String name, final Service service) {
        namedServices.put(name, service);
    }

    public void removeNamedService(final String name) {
        namedServices.remove(name);
    }

    public Service getNamedService(final String name) {
        return namedServices.get(name);
    }

}
