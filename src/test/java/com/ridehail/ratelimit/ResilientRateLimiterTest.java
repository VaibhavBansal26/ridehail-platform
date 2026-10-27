package com.ridehail.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehail.MutableClock;
import com.ridehail.common.RateLimiter;
import com.ridehail.common.RateLimiter.Decision;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

class ResilientRateLimiterTest {

    final MutableClock clock = new MutableClock();
    final AtomicInteger primaryCalls = new AtomicInteger();
    volatile boolean primaryDown;

    final RateLimiter primary = key -> {
        primaryCalls.incrementAndGet();
        if (primaryDown) throw new RedisConnectionFailureException("Redis is down");
        return Decision.allow();
    };
    final RateLimiter local = new InMemoryRateLimiter(1.0, 2, clock);
    final ResilientRateLimiter limiter = new ResilientRateLimiter("test", primary, local, clock, Duration.ofSeconds(5));

    @Test
    void theSharedLimiterDecidesWhileItWorks() {
        for (int i = 0; i < 50; i++) assertThat(limiter.tryAcquire("rider").allowed()).isTrue();   // local would stop at 2
        assertThat(limiter.usingFallback()).isFalse();
    }

    @Test
    void whenTheSharedStoreFailsTheLocalLimitTakesOverInsteadOfFailingTheRequest() {
        primaryDown = true;

        assertThat(limiter.tryAcquire("rider").allowed()).isTrue();      // no exception reaches the caller
        assertThat(limiter.tryAcquire("rider").allowed()).isTrue();
        assertThat(limiter.tryAcquire("rider").allowed()).isFalse();     // and it still limits, just per instance
        assertThat(limiter.usingFallback()).isTrue();
    }

    @Test
    void anOutageCostsOneFailedCallPerCoolOffNotOnePerRequest() {
        primaryDown = true;
        for (int i = 0; i < 100; i++) limiter.tryAcquire("rider");
        assertThat(primaryCalls.get()).isEqualTo(1);                      // the other 99 never touched the broken store

        clock.advance(Duration.ofSeconds(6));                             // cool-off over: try once more
        limiter.tryAcquire("rider");
        assertThat(primaryCalls.get()).isEqualTo(2);
    }

    @Test
    void itRecoversByItselfWhenTheStoreComesBack() {
        primaryDown = true;
        limiter.tryAcquire("rider");
        assertThat(limiter.usingFallback()).isTrue();

        primaryDown = false;
        clock.advance(Duration.ofSeconds(6));

        assertThat(limiter.tryAcquire("rider").allowed()).isTrue();
        assertThat(limiter.usingFallback()).isFalse();
        for (int i = 0; i < 50; i++) assertThat(limiter.tryAcquire("rider").allowed()).isTrue();   // the shared limiter is back in charge
    }
}
