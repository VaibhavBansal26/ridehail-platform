package com.ridehail.ratelimit;

import com.ridehail.common.RateLimiter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.LongSupplier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.StreamUtils;

/**
 * Token bucket kept in Redis, so a limit holds across every instance of the application: a rider whose updates are spread
 * over several servers by a load balancer still gets one allowance, not one per server.
 *
 * Each call is one atomic Lua script (no read-modify-write race between instances). By default the script reads the
 * time from Redis itself, so the instances' own clocks do not matter.
 *
 * Throws on any Redis failure: callers wanting graceful degradation wrap this in a {@link ResilientRateLimiter}.
 */
public final class RedisRateLimiter implements RateLimiter {

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript(load("ratelimit/token_bucket.lua"), List.class);

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final int burst;
    private final double refillPerMilli;
    private final long ttlMillis;
    private final LongSupplier nowMillis;

    /** Uses Redis's clock. */
    public RedisRateLimiter(StringRedisTemplate redis, String name, double perSecond, int burst) {
        this(redis, name, perSecond, burst, null);
    }

    /** @param nowMillis time source; null means Redis's clock (what production uses). Tests inject a fake one. */
    public RedisRateLimiter(StringRedisTemplate redis, String name, double perSecond, int burst, LongSupplier nowMillis) {
        if (perSecond <= 0 || burst < 1) throw new IllegalArgumentException("perSecond must be > 0 and burst >= 1");
        this.redis = redis;
        this.keyPrefix = "ridehail:ratelimit:" + name + ":";
        this.burst = burst;
        this.refillPerMilli = perSecond / 1000.0;
        // twice the time to refill an empty bucket, at least a minute: long enough to be exact, short enough not to pile up keys
        this.ttlMillis = Math.max(60_000L, 2 * (long) Math.ceil(burst / perSecond * 1000.0));
        this.nowMillis = nowMillis;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Decision tryAcquire(String key) {
        List<Long> result = redis.execute(SCRIPT, List.of(keyPrefix + key),
                Integer.toString(burst), Double.toString(refillPerMilli), Long.toString(ttlMillis),
                Long.toString(nowMillis == null ? -1L : nowMillis.getAsLong()));
        if (result == null || result.size() != 2) throw new IllegalStateException("Unexpected rate limiter reply: " + result);
        return result.get(0) == 1L ? Decision.allow() : Decision.deny(result.get(1));
    }

    private static String load(String path) {
        try {
            return StreamUtils.copyToString(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
