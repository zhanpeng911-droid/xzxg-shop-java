package com.xzxg.shop.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class FixedWindowRateLimiterTest {

    @Test
    void allowsUpToLimitThenResetsAfterWindow() {
        AtomicLong now = new AtomicLong(0);
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2, 60, 100, now::get);

        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();

        // The Go condition is `now - windowStart > window`, so exactly 60s does
        // not reset the window yet.
        now.addAndGet(60_000_000_000L);
        assertThat(limiter.tryAcquire("a")).isFalse();

        now.addAndGet(1L);
        assertThat(limiter.tryAcquire("a")).isTrue();
    }

    @Test
    void windowsAreIndependentPerKey() {
        AtomicLong now = new AtomicLong(0);
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, 60, 100, now::get);

        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
        assertThat(limiter.tryAcquire("b")).isTrue();
    }

    @Test
    void evictsWhenEntryLimitIsReached() {
        AtomicLong now = new AtomicLong(0);
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, 60, 2, now::get);

        limiter.tryAcquire("a");
        limiter.tryAcquire("b");
        limiter.tryAcquire("c");

        assertThat(limiter.size()).isLessThanOrEqualTo(2);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new FixedWindowRateLimiter(0, 60, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixedWindowRateLimiter(1, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixedWindowRateLimiter(1, 60, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
