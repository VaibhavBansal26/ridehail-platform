package com.ridehail.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ridehail.login-limit")
public record LoginLimitProperties(
        /** Login attempts an account may make at once before it has to wait. */
        int burst,
        /** Sustained attempts per minute once the burst is used up. */
        double perMinute) {}
