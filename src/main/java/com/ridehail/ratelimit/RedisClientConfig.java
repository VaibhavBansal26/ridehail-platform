package com.ridehail.ratelimit;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RedisClientConfig {

    /**
     * While Redis is unreachable, fail commands immediately instead of queueing them until they time out. The rate limiter
     * then falls back to its local counters without making any request wait. The client still reconnects on its own.
     */
    @Bean
    LettuceClientConfigurationBuilderCustomizer failFastWhileDisconnected() {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
    }
}
