package com.cloudant.cloujeau;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.ericsson.otp.erlang.OtpErlangPid;

public final class ServiceRegistry {

    private final Map<String, Service> byName = new HashMap<String, Service>();
    private final Map<OtpErlangPid, Service> byPid = new HashMap<OtpErlangPid, Service>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private final RunQueue<Service> pending = new RunQueue<Service>();

    public ServiceRegistry() {
    }

    public void register(final Service service) {
        final String name = service.getName();
        final Lock lock = this.lock.writeLock();
        lock.lock();
        try {
            if (name != null) {
                byName.put(name, service);
            }
            byPid.put(service.self(), service);
        } finally {
            lock.unlock();
        }
    }

    public void unregister(final Service service) {
        final String name = service.getName();
        final Lock lock = this.lock.writeLock();
        lock.lock();
        try {
            if (name != null) {
                byName.remove(name);
            }
            byPid.remove(service.self());
        } finally {
            lock.unlock();
        }
    }

    public void addPending(final String name) {
        final Lock lock = this.lock.readLock();
        lock.lock();
        try {
            final Service service = byName.get(name);
            if (service != null) {
                pending.put(service);
            }
        } finally {
            lock.unlock();
        }
    }

    public void addPending(final OtpErlangPid pid) {
        final Lock lock = this.lock.readLock();
        lock.lock();
        try {
            final Service service = byPid.get(pid);
            if (service != null) {
                pending.put(service);
            }
        } finally {
            lock.unlock();
        }
    }

    public Service takePending() throws InterruptedException {
        return pending.take();
    }

    public String toString() {
        return String.format(
                "ServiceRegistry(numRegisteredServices=%d,numUnregisteredServices=%d,numPending=%d)",
                byName.size(),
                byPid.size(),
                pending.size());
    }

}
