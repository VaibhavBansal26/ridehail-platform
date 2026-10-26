package com.ridehail.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehail.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TokenBucketLimiterTest {

    final MutableClock clock = new MutableClock();

    @Test
    void allowsABurstThenRefusesUntilTokensRefill() {
        var limiter = new TokenBucketLimiter<String>(1.0, 5, clock);   // 1 per second, burst 5

        for (int i = 0; i < 5; i++) assertThat(limiter.tryAcquire("a").allowed()).as("burst %d", i).isTrue();
        var refused = limiter.tryAcquire("a");
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterMillis()).isEqualTo(1000);          // exactly one token away

        clock.advance(Duration.ofMillis(400));
        assertThat(limiter.tryAcquire("a").retryAfterMillis()).isEqualTo(600);   // the wait shrinks as time passes

        clock.advance(Duration.ofMillis(600));
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void theSustainedRateIsCappedNoMatterHowLongTheBucketSatIdle() {
        var limiter = new TokenBucketLimiter<String>(1.0, 3, clock);
        clock.advance(Duration.ofHours(5));                                // idle for hours does not bank hours of tokens

        int allowed = 0;
        for (int i = 0; i < 50; i++) if (limiter.tryAcquire("a").allowed()) allowed++;
        assertThat(allowed).isEqualTo(3);                                  // only the burst, never 5 hours' worth
    }

    @Test
    void sustainedTrafficAtTheLimitIsAllowedIndefinitely() {
        var limiter = new TokenBucketLimiter<String>(1.0, 2, clock);
        for (int second = 0; second < 100; second++) {
            assertThat(limiter.tryAcquire("a").allowed()).as("second %d", second).isTrue();
            clock.advance(Duration.ofSeconds(1));
        }
    }

    @Test
    void keysDoNotShareABucket() {
        var limiter = new TokenBucketLimiter<String>(1.0, 1, clock);
        assertThat(limiter.tryAcquire("noisy").allowed()).isTrue();
        assertThat(limiter.tryAcquire("noisy").allowed()).isFalse();
        assertThat(limiter.tryAcquire("quiet").allowed()).isTrue();         // someone else's flood is not my problem
    }

    @Test
    void fractionalRatesWork() {
        var limiter = new TokenBucketLimiter<String>(0.5, 1, clock);       // one every 2 seconds
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").retryAfterMillis()).isEqualTo(2000);
        clock.advance(Duration.ofSeconds(2));
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
    }

    @Test
    void idleBucketsAreForgottenSoMemoryStaysBounded() {
        var limiter = new TokenBucketLimiter<Integer>(1.0, 1, clock);
        for (int key = 0; key < 1023; key++) limiter.tryAcquire(key);
        assertThat(limiter.size()).isEqualTo(1023);

        clock.advance(Duration.ofMinutes(11));
        limiter.tryAcquire(5000);                                           // the 1024th call triggers the sweep
        assertThat(limiter.size()).isEqualTo(1);                            // everything idle was dropped, the live one kept
        assertThat(limiter.tryAcquire(5000).allowed()).isFalse();           // and its state survived the sweep
    }

    @Test
    void concurrentCallersNeverGetMoreTokensThanTheBucketHolds() throws Exception {
        var limiter = new TokenBucketLimiter<String>(1.0, 10, clock);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var allowed = new java.util.concurrent.atomic.AtomicInteger();
        var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Void>>();
        for (int i = 0; i < 200; i++) tasks.add(() -> { if (limiter.tryAcquire("a").allowed()) allowed.incrementAndGet(); return null; });
        pool.invokeAll(tasks);
        pool.shutdown();
        assertThat(allowed.get()).isEqualTo(10);                            // the clock is frozen: exactly the burst, no races
    }

    @Test
    void rejectsNonsenseConfiguration() {
        assertThatThrownBy(() -> new TokenBucketLimiter<String>(0, 5, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenBucketLimiter<String>(1, 0, clock)).isInstanceOf(IllegalArgumentException.class);
    }
}
