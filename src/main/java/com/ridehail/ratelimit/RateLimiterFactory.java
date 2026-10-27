package com.ridehail.ratelimit;

import com.ridehail.common.RateLimiter;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Builds the limiters the application uses, backed by whichever store is configured. */
@Component
public class RateLimiterFactory {
    private final RateLimitProperties props;
    private final ObjectProvider<StringRedisTemplate> redis;
    private final Clock clock;

    RateLimiterFactory(RateLimitProperties props, ObjectProvider<StringRedisTemplate> redis, Clock clock) {
        this.props = props;
        this.redis = redis;
        this.clock = clock;
    }

    /**
     * @param name       identifies this limit in the shared store, so different limits never share counters
     * @param perSecond  sustained rate per key
     * @param burst      how many calls a key may make at once
     */
    public RateLimiter create(String name, double perSecond, int burst) {
        var local = new InMemoryRateLimiter(perSecond, burst, clock);
        if (props.store() != RateLimitProperties.Store.REDIS) return local;

        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("ridehail.ratelimit.store=redis but no Redis connection is configured (spring.data.redis.*)");
        }
        return new ResilientRateLimiter(name, new RedisRateLimiter(template, name, perSecond, burst), local, clock, props.redisCoolOff());
    }
}
