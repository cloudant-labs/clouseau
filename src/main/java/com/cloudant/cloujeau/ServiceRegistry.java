package com.cloudant.cloujeau;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.ericsson.otp.erlang.OtpErlangPid;

public final class ServiceRegistry {

    private final ConcurrentMap<String, Service> registeredServices = new ConcurrentHashMap<String, Service>();
    private final ConcurrentMap<OtpErlangPid, Service> unregisteredServices = new ConcurrentHashMap<OtpErlangPid, Service>();

    public void register(final String name, final Service service) {
        registeredServices.put(name, service);
    }

    public void register(final OtpErlangPid pid, final Service service) {
        unregisteredServices.put(pid, service);
    }

    public void unregister(final String name) {
        registeredServices.remove(name);
    }

    public void unregister(final OtpErlangPid pid) {
        unregisteredServices.remove(pid);
    }

    public Service lookup(final String name) {
        return registeredServices.get(name);
    }

    public Service lookup(final OtpErlangPid pid) {
        return unregisteredServices.get(pid);
    }

}
