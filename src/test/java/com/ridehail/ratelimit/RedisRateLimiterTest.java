package com.ridehail.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehail.common.RateLimiter.Decision;
import com.ridehail.testsupport.Containers;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The Redis token bucket against a real Redis. "Two instances" are two limiter objects with separate connections. */
@EnabledIf("com.ridehail.testsupport.Containers#dockerAvailable")
class RedisRateLimiterTest {

    static LettuceConnectionFactory factoryA, factoryB;
    static StringRedisTemplate instanceA, instanceB;

    final AtomicLong fakeNow = new AtomicLong(1_000_000L);
    final String name = "t-" + UUID.randomUUID();

    @BeforeAll
    static void connect() {
        factoryA = new LettuceConnectionFactory(new RedisStandaloneConfiguration(Containers.redisHost(), Containers.redisPort()));
        factoryB = new LettuceConnectionFactory(new RedisStandaloneConfiguration(Containers.redisHost(), Containers.redisPort()));
        factoryA.afterPropertiesSet();
        factoryB.afterPropertiesSet();
        instanceA = new StringRedisTemplate(factoryA);
        instanceB = new StringRedisTemplate(factoryB);
        instanceA.afterPropertiesSet();
        instanceB.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        factoryA.destroy();
        factoryB.destroy();
    }

    RedisRateLimiter onA(double perSecond, int burst) { return new RedisRateLimiter(instanceA, name, perSecond, burst, fakeNow::get); }

    RedisRateLimiter onB(double perSecond, int burst) { return new RedisRateLimiter(instanceB, name, perSecond, burst, fakeNow::get); }

    @Test
    void allowsABurstThenRefusesWithTheExactWaitUntilTheNextToken() {
        var limiter = onA(1.0, 5);

        for (int i = 0; i < 5; i++) assertThat(limiter.tryAcquire("rider").allowed()).as("burst %d", i).isTrue();
        Decision refused = limiter.tryAcquire("rider");

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterMillis()).isEqualTo(1000);
    }

    @Test
    void tokensRefillWithTime() {
        var limiter = onA(1.0, 5);
        for (int i = 0; i < 5; i++) limiter.tryAcquire("rider");

        fakeNow.addAndGet(400);
        assertThat(limiter.tryAcquire("rider").retryAfterMillis()).isEqualTo(600);   // the wait shrinks as time passes
        fakeNow.addAndGet(600);
        assertThat(limiter.tryAcquire("rider").allowed()).isTrue();
        assertThat(limiter.tryAcquire("rider").allowed()).isFalse();
    }

    @Test
    void anIdleBucketNeverBanksMoreThanItsBurst() {
        var limiter = onA(1.0, 3);
        limiter.tryAcquire("rider");
        fakeNow.addAndGet(5L * 60 * 60 * 1000);                                     // five hours later

        int allowed = 0;
        for (int i = 0; i < 20; i++) if (limiter.tryAcquire("rider").allowed()) allowed++;
        assertThat(allowed).isEqualTo(3);
    }

    @Test
    void twoInstancesShareOneAllowance() {
        var a = onA(1.0, 5);
        var b = onB(1.0, 5);                                                         // the same limit, seen from another server

        int allowed = 0;
        for (int i = 0; i < 10; i++) if ((i % 2 == 0 ? a : b).tryAcquire("rider").allowed()) allowed++;

        assertThat(allowed).isEqualTo(5);                                            // not 10: one bucket, not one per server
    }

    @Test
    void concurrentCallersOnDifferentInstancesNeverGetMoreThanTheBurstBetweenThem() throws Exception {
        var a = onA(1.0, 10);
        var b = onB(1.0, 10);
        var allowed = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(16);
        var tasks = new ArrayList<Callable<Void>>();
        for (int i = 0; i < 300; i++) {
            var limiter = i % 2 == 0 ? a : b;
            tasks.add(() -> {
                if (limiter.tryAcquire("rider").allowed()) allowed.incrementAndGet();
                return null;
            });
        }
        pool.invokeAll(tasks);
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(allowed.get()).isEqualTo(10);                                     // the script is atomic: no lost updates, no extras
    }

    @Test
    void keysAndNamedLimitsAreIndependent() {
        var limiter = onA(1.0, 1);
        assertThat(limiter.tryAcquire("noisy").allowed()).isTrue();
        assertThat(limiter.tryAcquire("noisy").allowed()).isFalse();
        assertThat(limiter.tryAcquire("quiet").allowed()).isTrue();

        var otherLimit = new RedisRateLimiter(instanceA, name + "-other", 1.0, 1, fakeNow::get);
        assertThat(otherLimit.tryAcquire("noisy").allowed()).isTrue();               // same key, different limit: separate counters
    }

    @Test
    void idleBucketsExpireSoRedisDoesNotFillUp() {
        var limiter = onA(1.0, 5);
        limiter.tryAcquire("rider");

        Long ttl = instanceA.getExpire("ridehail:ratelimit:" + name + ":rider", TimeUnit.MILLISECONDS);

        assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(60_000L);
    }

    @Test
    void aClockThatJumpsBackwardsCannotCorruptABucket() {
        var limiter = onA(1.0, 2);
        limiter.tryAcquire("rider");
        limiter.tryAcquire("rider");

        fakeNow.addAndGet(-60_000);                                                  // e.g. a time source being corrected
        Decision d = limiter.tryAcquire("rider");

        assertThat(d.allowed()).isFalse();                                           // no free tokens from negative elapsed time
        assertThat(d.retryAfterMillis()).isPositive();
    }

    @Test
    void byDefaultTheTimeComesFromRedisItselfSoServerClocksDoNotMatter() throws Exception {
        var a = new RedisRateLimiter(instanceA, name, 5.0, 2);                       // no fake clock: Redis's own
        var b = new RedisRateLimiter(instanceB, name, 5.0, 2);

        assertThat(a.tryAcquire("rider").allowed()).isTrue();
        assertThat(b.tryAcquire("rider").allowed()).isTrue();
        assertThat(a.tryAcquire("rider").allowed()).isFalse();
        Thread.sleep(450);                                                           // 5/s: a token every 200 ms
        assertThat(b.tryAcquire("rider").allowed()).isTrue();                        // refilled, and seen by the other "server"
    }

    @Test
    void whenRedisIsUnreachableTheWrappedLimiterAnswersFromLocalCountersWithoutWaiting() {
        var dead = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1),
                org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
                        .commandTimeout(java.time.Duration.ofMillis(200))
                        .clientOptions(io.lettuce.core.ClientOptions.builder()
                                .disconnectedBehavior(io.lettuce.core.ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).build())
                        .build());
        dead.afterPropertiesSet();
        var template = new StringRedisTemplate(dead);
        template.afterPropertiesSet();
        var clock = new com.ridehail.MutableClock();
        var limiter = new ResilientRateLimiter(name, new RedisRateLimiter(template, name, 1.0, 3),
                new InMemoryRateLimiter(1.0, 3, clock), clock, java.time.Duration.ofSeconds(5));

        long started = System.nanoTime();
        int allowed = 0;
        for (int i = 0; i < 10; i++) if (limiter.tryAcquire("rider").allowed()) allowed++;
        long tookMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(allowed).isEqualTo(3);                      // still limited, by the local fallback
        assertThat(tookMillis).isLessThan(2_000);              // and nobody waited on a dead connection ten times
        dead.destroy();
    }

    @Test
    void rejectsNonsenseConfiguration() {
        assertThatThrownBy(() -> new RedisRateLimiter(instanceA, name, 0, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisRateLimiter(instanceA, name, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
