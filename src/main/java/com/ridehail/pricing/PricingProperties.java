package com.ridehail.pricing;

import com.ridehail.common.VehicleType;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ridehail.pricing")
public record PricingProperties(
        String currency,
        long quoteTtlSeconds,
        double roadFactor,
        double avgSpeedKmh,
        Map<VehicleType, Rate> rates,
        Surge surge) {

    public record Rate(BigDecimal base, BigDecimal perKm, BigDecimal perMin, BigDecimal minimum) {}

    public record Surge(
            double radiusKm,
            long demandWindowMinutes,
            double threshold,
            double slope,
            double maxMultiplier) {}
}
