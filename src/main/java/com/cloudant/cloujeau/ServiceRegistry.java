package com.cloudant.cloujeau;

import static com.cloudant.cloujeau.OtpUtils.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.log4j.Logger;

import com.ericsson.otp.erlang.OtpErlangPid;

public final class ServiceRegistry {

    private static final Logger logger = Logger.getLogger("clouseau.main");

    private final Map<String, Service> byName = new HashMap<String, Service>();
    private final Map<OtpErlangPid, Service> byPid = new HashMap<OtpErlangPid, Service>();

    private final Map<OtpErlangPid, Service> pidOnly = new LinkedHashMap<OtpErlangPid, Service>(16, 0.75f, true) {

        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(final Entry<OtpErlangPid, Service> eldest) {
            if (size() > capacity) {
                logger.warn(eldest.getValue() + " ejected from LRU");
                eldest.getValue().send(eldest.getKey(), tuple(atom("close"), atom("lru")));
            }
            return false;
        }

    };
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private final RunQueue<Service> pending = new RunQueue<Service>();

    private final int capacity;

    public ServiceRegistry(final int capacity) {
        this.capacity = capacity;
    }

    public void register(final Service service) {
        final String name = service.getName();
        final Lock lock = this.lock.writeLock();
        lock.lock();
        try {
            if (name != null) {
                byName.put(name, service);
                byPid.put(service.self(), service);
            } else {
                pidOnly.put(service.self(), service);
            }
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
                byPid.remove(service.self());
            } else {
                pidOnly.remove(service.self());
            }
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
        Lock lock = this.lock.readLock();
        lock.lock();
        try {
            final Service service = byPid.get(pid);
            if (service != null) {
                pending.put(service);
                return;
            }
        } finally {
            lock.unlock();
        }

        lock = this.lock.writeLock(); // write lock because pidOnly is updated on access.
        lock.lock();
        try {
            final Service service = pidOnly.get(pid);
            if (service != null) {
                pending.put(service);
                return;
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
