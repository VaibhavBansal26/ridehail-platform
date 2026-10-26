package com.ridehail;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * A frozen, manually advanced clock. Kept at MICROsecond precision because that is all the database stores: Linux
 * clocks have nanoseconds, and a frozen "now" with extra digits compares as slightly earlier than the rounded value
 * read back from the database (a payment's retry time would never look due).
 */
public class MutableClock extends Clock {
    private volatile Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    public void advance(Duration d) { now = now.plus(d); }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
}
