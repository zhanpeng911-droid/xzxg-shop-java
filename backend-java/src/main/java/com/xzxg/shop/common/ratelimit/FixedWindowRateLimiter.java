package com.xzxg.shop.common.ratelimit;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Fixed-window limiter matching the Go implementation: a window resets only when
 * {@code now - windowStart > window}. Process-local; Redis is not a first-round
 * dependency.
 */
public final class FixedWindowRateLimiter {

    private final int limit;
    private final long windowNanos;
    private final int maxEntries;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int limit, long windowSeconds, int maxEntries) {
        this(limit, windowSeconds, maxEntries, System::nanoTime);
    }

    FixedWindowRateLimiter(int limit, long windowSeconds, int maxEntries, LongSupplier nanoClock) {
        if (limit < 1) {
            throw new IllegalArgumentException("rate-limit limit must be >= 1");
        }
        if (windowSeconds < 1) {
            throw new IllegalArgumentException("rate-limit window must be >= 1 second");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("rate-limit maxEntries must be >= 1");
        }
        this.limit = limit;
        this.windowNanos = windowSeconds * 1_000_000_000L;
        this.maxEntries = maxEntries;
        this.nanoClock = nanoClock;
    }

    public boolean tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        Window window = windows.get(key);
        if (window == null) {
            if (windows.size() >= maxEntries) {
                evict(now);
            }
            Window candidate = new Window();
            Window existing = windows.putIfAbsent(key, candidate);
            window = existing != null ? existing : candidate;
        }
        synchronized (window) {
            if (window.startNanos == Long.MIN_VALUE || now - window.startNanos > windowNanos) {
                window.startNanos = now;
                window.count = 1;
                return true;
            }
            if (window.count >= limit) {
                return false;
            }
            window.count++;
            return true;
        }
    }

    int size() {
        return windows.size();
    }

    private void evict(long now) {
        windows.forEach((key, window) -> {
            synchronized (window) {
                if (window.startNanos != Long.MIN_VALUE
                        && now - window.startNanos > 2 * windowNanos) {
                    windows.remove(key, window);
                }
            }
        });
        if (windows.size() >= maxEntries) {
            Iterator<String> keys = windows.keySet().iterator();
            if (keys.hasNext()) {
                keys.next();
                keys.remove();
            }
        }
    }

    private static final class Window {
        private long startNanos = Long.MIN_VALUE;
        private int count;
    }
}
