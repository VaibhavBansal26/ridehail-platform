package com.ridehail.pricing;

import com.ridehail.common.ApiExceptions.NotFoundException;
import com.ridehail.common.VehicleType;
import com.ridehail.customer.CustomerRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FareService {

    private final RouteEstimator routes;
    private final SurgePricingService surge;
    private final FareCalculator calculator;
    private final FareQuoteRepository quotes;
    private final CustomerRepository customers;
    private final PricingProperties props;
    private final Clock clock;

    FareService(RouteEstimator routes, SurgePricingService surge, FareCalculator calculator,
                FareQuoteRepository quotes, CustomerRepository customers, PricingProperties props, Clock clock) {
        this.routes = routes;
        this.surge = surge;
        this.calculator = calculator;
        this.quotes = quotes;
        this.customers = customers;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public FareQuote quote(UUID customerId, VehicleType type,
                           double pickupLat, double pickupLon, double dropLat, double dropLon) {
        customers.findById(customerId).orElseThrow(() -> new NotFoundException("Customer", customerId));

        var route = routes.estimate(pickupLat, pickupLon, dropLat, dropLon);
        var surgeResult = surge.evaluate(pickupLat, pickupLon, type, customerId);
        var fare = calculator.calculate(type, route.distanceKm(), route.durationMin(), surgeResult.multiplier());

        Instant now = Instant.now(clock);
        var q = new FareQuote();
        q.setCustomerId(customerId);
        q.setVehicleType(type);
        q.setPickupLat(pickupLat);
        q.setPickupLon(pickupLon);
        q.setDropLat(dropLat);
        q.setDropLon(dropLon);
        q.setDistanceKm(route.distanceKm());
        q.setDurationMin(route.durationMin());
        q.setSubtotal(fare.subtotal());
        q.setSurgeMultiplier(fare.surgeMultiplier());
        q.setTotal(fare.total());
        q.setCurrency(props.currency());
        q.setDemand(surgeResult.demand());
        q.setSupply(surgeResult.supply());
        q.setCreatedAt(now);
        q.setExpiresAt(now.plus(Duration.ofSeconds(props.quoteTtlSeconds())));
        return quotes.save(q);
    }
}
