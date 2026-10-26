package com.ridehail.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BackoffTest {

    @Test
    void doublesFromTheBaseUpToTheCeiling() {
        long[] expected = {30, 60, 120, 240, 480, 960, 1800, 1800, 1800};   // base 30s, ceiling 30 min
        for (int failed = 1; failed <= expected.length; failed++) {
            assertThat(Backoff.seconds(failed, 30, 1800)).as("after %d failures", failed).isEqualTo(expected[failed - 1]);
        }
    }

    @Test
    void eightAttemptsSpanAboutAnHourRatherThanEightMinutes() {
        long total = 0;
        for (int failed = 1; failed < 8; failed++) total += Backoff.seconds(failed, 30, 1800);   // the 7 waits between 8 attempts
        assertThat(total).isEqualTo(30 + 60 + 120 + 240 + 480 + 960 + 1800);
        assertThat(total).isGreaterThan(60 * 60);
    }

    @Test
    void absurdAttemptCountsCannotOverflow() {
        assertThat(Backoff.seconds(10_000, 30, 1800)).isEqualTo(1800);
        assertThat(Backoff.seconds(0, 30, 1800)).isEqualTo(30);
    }
}
