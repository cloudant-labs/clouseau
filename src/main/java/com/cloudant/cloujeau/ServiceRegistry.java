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

    public void register(final Service service) {
        final String name = service.getName();
        if (name != null) {
            registeredServices.put(name, service);
        } else {
            unregisteredServices.put(service.self(), service);
        }
    }

    public void unregister(final Service service) {
        final String name = service.getName();
        if (name != null) {
            registeredServices.remove(name);
        } else {
            unregisteredServices.remove(service.self());
        }
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
