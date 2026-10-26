package com.ridehail.dispatch;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ridehail.dispatch")
public record DispatchProperties(long offerTtlSeconds, double searchRadiusKm, int maxOffersPerTrip, long expiryScanMs) {}
