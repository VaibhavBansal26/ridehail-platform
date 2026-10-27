package com.ridehail.common;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory token-bucket rate limiter, one bucket per key. A bucket holds up to {@code burst} tokens and refills at
 * {@code perSecond}; each call takes one. Allows short bursts but caps the sustained rate. Time comes from the injected
 * {@link Clock}, so behaviour is exactly testable.
 *
 * Limits apply per application instance (counters are not shared). That is fine for protecting one node from a noisy
 * client; a strict global limit needs a shared store such as Redis.
 */
public final class TokenBucketLimiter<K> {

    private static final RateLimiter.Decision ALLOWED = RateLimiter.Decision.allow();

    private static final class Bucket {
        double tokens;
        long lastMillis;

        Bucket(double tokens, long lastMillis) {
            this.tokens = tokens;
            this.lastMillis = lastMillis;
        }
    }

    private static final long IDLE_EVICTION_MILLIS = 10 * 60_000L;
    private static final int CLEANUP_EVERY_CALLS = 1024;

    private final double burst;
    private final double refillPerMilli;
    private final Clock clock;
    private final ConcurrentHashMap<K, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();

    public TokenBucketLimiter(double perSecond, int burst, Clock clock) {
        if (perSecond <= 0 || burst < 1) throw new IllegalArgumentException("perSecond must be > 0 and burst >= 1");
        this.burst = burst;
        this.refillPerMilli = perSecond / 1000.0;
        this.clock = clock;
    }

    public RateLimiter.Decision tryAcquire(K key) {
        long now = clock.instant().toEpochMilli();
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(burst, now));
        RateLimiter.Decision decision;
        synchronized (bucket) {
            long elapsed = Math.max(0, now - bucket.lastMillis);
            bucket.tokens = Math.min(burst, bucket.tokens + elapsed * refillPerMilli);
            bucket.lastMillis = now;
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                decision = ALLOWED;
            } else {
                decision = RateLimiter.Decision.deny((long) Math.ceil((1.0 - bucket.tokens) / refillPerMilli));
            }
        }
        if (calls.incrementAndGet() % CLEANUP_EVERY_CALLS == 0) evictIdle(now);
        return decision;
    }

    /** Buckets untouched for a while are full again anyway, so dropping them loses nothing and bounds memory. */
    private void evictIdle(long now) {
        buckets.entrySet().removeIf(e -> now - e.getValue().lastMillis > IDLE_EVICTION_MILLIS);
    }

    int size() {
        return buckets.size();
    }
}
