package com.ridehail.payment;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ridehail.payments")
public record PaymentProperties(
        BigDecimal commissionRate,
        BigDecimal minPayout,
        int maxAttempts,
        long retryBackoffSeconds,
        long leaseSeconds,
        long retryScanMs) {}
