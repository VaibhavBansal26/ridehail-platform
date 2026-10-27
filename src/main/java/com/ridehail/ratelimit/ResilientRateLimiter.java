package com.ridehail.ratelimit;

import com.ridehail.common.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A shared limiter that must never become a single point of failure. Rate limiting protects the system; it is not worth
 * rejecting or delaying real work when the limiter's own store is down.
 *
 * While the primary (Redis) works it decides. When it fails, the local fallback decides instead (still limiting, just per
 * instance), and the primary is not tried again for a short cool-off, so a Redis outage costs one fast failure per
 * cool-off period rather than a timeout on every request. It recovers by itself.
 */
public final class ResilientRateLimiter implements RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(ResilientRateLimiter.class);

    private final String name;
    private final RateLimiter primary;
    private final RateLimiter fallback;
    private final Clock clock;
    private final long coolOffMillis;
    private volatile long skipPrimaryUntil;

    public ResilientRateLimiter(String name, RateLimiter primary, RateLimiter fallback, Clock clock, Duration coolOff) {
        this.name = name;
        this.primary = primary;
        this.fallback = fallback;
        this.clock = clock;
        this.coolOffMillis = coolOff.toMillis();
    }

    @Override
    public Decision tryAcquire(String key) {
        long now = clock.instant().toEpochMilli();
        if (now < skipPrimaryUntil) return fallback.tryAcquire(key);
        try {
            return primary.tryAcquire(key);
        } catch (RuntimeException e) {
            skipPrimaryUntil = now + coolOffMillis;
            log.warn("Shared rate limiter '{}' is unavailable ({}); limiting per instance for the next {} ms",
                    name, e.getClass().getSimpleName(), coolOffMillis);
            return fallback.tryAcquire(key);
        }
    }

    /** True while the shared store is being skipped (for tests and diagnostics). */
    boolean usingFallback() {
        return clock.instant().toEpochMilli() < skipPrimaryUntil;
    }
}
