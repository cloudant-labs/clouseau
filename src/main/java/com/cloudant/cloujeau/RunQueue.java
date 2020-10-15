package com.cloudant.cloujeau;

import java.util.LinkedHashSet;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Keeps track of Services that have pending messages and allows threads to wait
 * for them in a fair order.
 */

public final class RunQueue<T> {

    private final LinkedHashSet<T> set = new LinkedHashSet<T>();

    private final Lock lock = new ReentrantLock();

    private final Condition notEmpty = lock.newCondition();

    public void put(final T item) {
        final Lock lock = this.lock;
        lock.lock();
        try {
            if (set.add(item)) {
                notEmpty.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    public T take() throws InterruptedException {
        final Lock lock = this.lock;
        lock.lock();
        try {
            while (set.isEmpty()) {
                notEmpty.await();
            }
            final T result = set.iterator().next();
            set.remove(result);
            return result;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        final Lock lock = this.lock;
        lock.lock();
        try {
            return set.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        return set.toString();
    }
}
