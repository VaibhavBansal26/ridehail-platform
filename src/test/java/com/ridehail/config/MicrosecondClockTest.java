package com.ridehail.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class MicrosecondClockTest {

    @Test
    void millisWorksUnlikeTheJdkTickClockWithASubMillisecondTick() {
        // the failure this class exists to avoid: Clock.tick(.., 1 microsecond).millis() divides by zero
        var jdkTick = Clock.tick(Clock.systemUTC(), java.time.Duration.ofNanos(1_000));
        org.assertj.core.api.Assertions.assertThatThrownBy(jdkTick::millis).isInstanceOf(ArithmeticException.class);

        var clock = MicrosecondClock.systemUtc();
        assertThat(clock.millis()).isCloseTo(System.currentTimeMillis(), org.assertj.core.api.Assertions.within(1000L));
    }

    @Test
    void instantsHaveNoSubMicrosecondDigitsSoTheyEqualWhatTheDatabaseStores() {
        var clock = MicrosecondClock.systemUtc();
        for (int i = 0; i < 1000; i++) assertThat(clock.instant().getNano() % 1000).isZero();
    }

    @Test
    void itFollowsTheBaseClock() {
        Instant fixed = Instant.parse("2026-01-01T10:00:00.123456789Z");
        var clock = new MicrosecondClock(Clock.fixed(fixed, ZoneOffset.UTC));
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-01-01T10:00:00.123456Z"));
        assertThat(clock.millis()).isEqualTo(fixed.toEpochMilli());
    }

    @Test
    void zoneHandlingKeepsTheTruncation() {
        var zoned = MicrosecondClock.systemUtc().withZone(ZoneId.of("Asia/Kolkata"));
        assertThat(zoned.getZone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
        assertThat(zoned.instant().getNano() % 1000).isZero();
    }
}
