package com.ridehail.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * The system clock, truncated to whole microseconds: the precision databases store, so a timestamp held in memory
 * always equals the one read back.
 *
 * This is deliberately not {@code Clock.tick(clock, Duration.ofNanos(1000))}: the JDK's tick clock computes
 * {@code millis()} by dividing by the tick length in milliseconds, which is zero for sub-millisecond ticks, so
 * {@code millis()} throws ArithmeticException ("/ by zero").
 */
final class MicrosecondClock extends Clock {
    private final Clock base;

    MicrosecondClock(Clock base) {
        this.base = base;
    }

    static MicrosecondClock systemUtc() {
        return new MicrosecondClock(Clock.systemUTC());
    }

    @Override
    public Instant instant() {
        return base.instant().truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public ZoneId getZone() {
        return base.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MicrosecondClock(base.withZone(zone));
    }

    @Override
    public String toString() {
        return "MicrosecondClock[" + base + "]";
    }
}
