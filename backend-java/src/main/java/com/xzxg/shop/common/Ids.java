package com.xzxg.shop.common;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Produces ids in the Go shape {@code prefix_<unixNano>}. The counter keeps ids
 * unique under concurrency while staying monotonic.
 */
public final class Ids {

    private static final AtomicLong COUNTER = new AtomicLong(System.currentTimeMillis() * 1_000_000L);

    private Ids() {
    }

    public static String next(String prefix) {
        return prefix + "_" + COUNTER.incrementAndGet();
    }
}
