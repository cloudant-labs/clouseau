package com.cloudant.cloujeau;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Keeps track of Services that have pending messages and allows threads to wait
 * for them in a fair order.
 */

public final class RunQueue<T> {

    private final BlockingDeque<T> pending = new LinkedBlockingDeque<T>();

    public void put(final T item) {
        if (!pending.contains(item)) {
            pending.addLast(item);
        }
    }

    public T take() {
        while (true) {
            try {
                return pending.takeFirst();
            } catch (final InterruptedException e) {
                // retry.
            }
        }
    }

}
