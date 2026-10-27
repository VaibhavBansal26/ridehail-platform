package com.ridehail.common;

/** Decides whether the caller identified by a key may proceed now. Implementations differ in where the counters live. */
public interface RateLimiter {

    /** @param key who is asking (e.g. a rider id); each key has its own allowance */
    Decision tryAcquire(String key);

    /** @param retryAfterMillis when refused: how long until a retry would be accepted (0 when allowed) */
    record Decision(boolean allowed, long retryAfterMillis) {
        public static Decision allow() { return new Decision(true, 0); }

        public static Decision deny(long retryAfterMillis) { return new Decision(false, retryAfterMillis); }
    }
}
