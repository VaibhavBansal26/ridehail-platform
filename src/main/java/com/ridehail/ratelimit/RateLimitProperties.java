package com.ridehail.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ridehail.ratelimit")
public record RateLimitProperties(Store store, Duration redisCoolOff) {

    public enum Store {
        /** Counters in each instance's memory. Right for a single instance and for development. */
        MEMORY,
        /** Counters in Redis, so limits hold across all instances. Falls back to MEMORY while Redis is unreachable. */
        REDIS
    }
}
