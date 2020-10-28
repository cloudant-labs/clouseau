package com.cloudant.clouseau;

import static com.cloudant.clouseau.OtpUtils.atom;
import static com.cloudant.clouseau.OtpUtils.tuple;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

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
                eldest.getValue().send(eldest.getKey(), tuple(atom("close"), atom("lru")));
            }
            return false;
        }

    };

    private final Lock lock = new ReentrantLock();
    private final LinkedHashSet<Service> pending = new LinkedHashSet<Service>();
    private final Set<Service> running = new HashSet<Service>();
    private final Condition pendingUpdated = lock.newCondition();

    private final int capacity;

    public ServiceRegistry(final int capacity) {
        this.capacity = capacity;
    }

    public void register(final Service service) {
        final String name = service.getName();
        final Lock lock = this.lock;
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
        final Lock lock = this.lock;
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

    public Service lookup(final String name) {
        final Lock lock = this.lock;
        lock.lock();
        try {
            return byName.get(name);
        } finally {
            lock.unlock();
        }
    }

    public Service lookup(final OtpErlangPid pid) {
        final Lock lock = this.lock;
        lock.lock();
        try {
            final Service service = byPid.get(pid);
            if (service != null) {
                return service;
            }
            return pidOnly.get(pid);
        } finally {
            lock.unlock();
        }
    }

    public void addPending(final String name) {
        final Lock lock = this.lock;
        lock.lock();
        try {
            final Service service = byName.get(name);
            if (service != null) {
                pending.add(service);
                pendingUpdated.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    public void addPending(final OtpErlangPid pid) {
        Lock lock = this.lock;
        lock.lock();
        try {
            Service service = byPid.get(pid);
            if (service != null) {
                pending.add(service);
                pendingUpdated.signal();
                return;
            }
            service = pidOnly.get(pid);
            if (service != null) {
                pending.add(service);
                pendingUpdated.signal();
                return;
            }
        } finally {
            lock.unlock();
        }
    }

    public Service borrowPending() throws InterruptedException {
        final Lock lock = this.lock;

        while (true) {
            lock.lock();
            try {
                while (pending.isEmpty()) {
                    pendingUpdated.await();
                }
                final Iterator<Service> it = pending.iterator();
                while (it.hasNext()) {
                    final Service result = it.next();
                    if (!running.contains(result)) {
                        pending.remove(result);
                        running.add(result);
                        return result;
                    }
                }
                pendingUpdated.await();
            } finally {
                lock.unlock();
            }
        }
    }

    public void returnPending(final Service service) throws InterruptedException {
        final Lock lock = this.lock;
        lock.lock();
        try {
            running.remove(service);
            pendingUpdated.signal();
        } finally {
            lock.unlock();
        }
    }

    public String toString() {
        return String.format(
                "ServiceRegistry(numRegisteredServices=%d,numUnregisteredServices=%d,numPending=%d)",
                byName.size(),
                pidOnly.size(),
                pending.size());
    }

}
