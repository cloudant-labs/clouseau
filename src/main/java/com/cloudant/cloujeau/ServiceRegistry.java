package com.cloudant.cloujeau;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingDeque;

import com.ericsson.otp.erlang.OtpErlangPid;

public final class ServiceRegistry {

    private final ConcurrentMap<String, Service> registeredServices = new ConcurrentHashMap<String, Service>();
    private final ConcurrentMap<OtpErlangPid, Service> unregisteredServices = new ConcurrentHashMap<OtpErlangPid, Service>();
    private final RunQueue<Service> pending = new RunQueue<Service>();

    public ServiceRegistry() {
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

    public Service lookup(final String name) {
        return registeredServices.get(name);
    }

    public Service lookup(final OtpErlangPid pid) {
        return unregisteredServices.get(pid);
    }

    public void addPending(final String name) {
        final Service service = lookup(name);
        if (service != null) {
            pending.put(service);
        }
    }

    public void addPending(final OtpErlangPid pid) {
        final Service service = unregisteredServices.get(pid);
        if (service != null) {
            pending.put(service);
        }
    }

    public Service takePending() throws InterruptedException {
        return pending.take();
    }

}
