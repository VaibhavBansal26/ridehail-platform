package com.ridehail.pricing;

import com.ridehail.common.GeoUtils;
import com.ridehail.common.VehicleType;
import com.ridehail.rider.RiderRepository;
import com.ridehail.rider.RiderStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dynamic pricing: multiplier grows with the demand/supply ratio around the pickup point.
 * demand = distinct customers who asked for a quote nearby recently (incl. this one)
 * supply = available riders of that vehicle type nearby
 */
@Service
public class SurgePricingService {

    private final PricingProperties props;
    private final FareQuoteRepository quotes;
    private final RiderRepository riders;
    private final Clock clock;

    SurgePricingService(PricingProperties props, FareQuoteRepository quotes, RiderRepository riders, Clock clock) {
        this.props = props;
        this.quotes = quotes;
        this.riders = riders;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SurgeResult evaluate(double lat, double lon, VehicleType type, UUID customerId) {
        var s = props.surge();
        var box = GeoUtils.boundingBox(lat, lon, s.radiusKm());
        Instant since = Instant.now(clock).minus(Duration.ofMinutes(s.demandWindowMinutes()));

        long otherDemand = quotes.countDistinctCustomersInBoxSince(
                type, since, customerId, box.minLat(), box.maxLat(), box.minLon(), box.maxLon());
        long demand = otherDemand + 1; // the customer asking right now
        long supply = riders.countInBox(
                RiderStatus.AVAILABLE, type, box.minLat(), box.maxLat(), box.minLon(), box.maxLon());

        return new SurgeResult(demand, supply, multiplierFor(demand, supply));
    }

    BigDecimal multiplierFor(long demand, long supply) {
        var s = props.surge();
        double ratio = (double) demand / Math.max(supply, 1);
        double raw = 1.0 + s.slope() * Math.max(0.0, ratio - s.threshold());
        double capped = Math.min(Math.max(raw, 1.0), s.maxMultiplier());
        return BigDecimal.valueOf(capped).setScale(1, RoundingMode.HALF_UP);
    }

    public record SurgeResult(long demand, long supply, BigDecimal multiplier) {}
}
