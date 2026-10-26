package com.ridehail.payment;

/** Exponential retry delay with a ceiling: base, 2x base, 4x base ... never more than max. */
final class Backoff {
    private Backoff() {}

    /** @param failedAttempts how many attempts have failed so far (1 for the first retry) */
    static long seconds(int failedAttempts, long baseSeconds, long maxSeconds) {
        int doublings = Math.min(Math.max(failedAttempts - 1, 0), 30); // 2^30 * base cannot overflow a long
        return Math.min(baseSeconds * (1L << doublings), maxSeconds);
    }
}
