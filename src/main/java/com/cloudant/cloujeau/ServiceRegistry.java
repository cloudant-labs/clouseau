package com.cloudant.cloujeau;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

import com.ericsson.otp.erlang.OtpErlangPid;

public final class ServiceRegistry {

    private final ConcurrentMap<String, Service> registeredServices = new ConcurrentHashMap<String, Service>();
    private final ConcurrentMap<OtpErlangPid, Service> unregisteredServices = new ConcurrentHashMap<OtpErlangPid, Service>();
    private final ExecutorService executor;

    public ServiceRegistry(final ExecutorService executor) {
        this.executor = executor;
    }

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

    public void setMessagePending(final String name) {
        final Service service = registeredServices.get(name);
        if (service != null) {
            executor.execute(service);
        }
    }

    public void setMessagePending(final OtpErlangPid pid) {
        final Service service = unregisteredServices.get(pid);
        if (service != null) {
            executor.execute(service);
        }
    }

}
