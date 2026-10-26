package com.ridehail.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ridehail.security")
public record SecurityProperties(
        String jwtSecret,
        long accessTtlMinutes,
        long refreshTtlDays,
        List<String> allowedOrigins,
        BootstrapAdmin bootstrapAdmin) {

    public record BootstrapAdmin(String phone, String password) {}
}
