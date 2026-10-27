package com.ridehail.ratelimit;

import com.ridehail.common.RateLimiter;
import com.ridehail.common.TokenBucketLimiter;
import java.time.Clock;

/** Counters in this process only. Exact for one instance; with several instances each enforces its own limit. */
public final class InMemoryRateLimiter implements RateLimiter {
    private final TokenBucketLimiter<String> buckets;

    public InMemoryRateLimiter(double perSecond, int burst, Clock clock) {
        this.buckets = new TokenBucketLimiter<>(perSecond, burst, clock);
    }

    @Override
    public Decision tryAcquire(String key) {
        return buckets.tryAcquire(key);
    }
}
