package com.ridehail.geo;

import com.ridehail.common.ApiExceptions.BadGatewayException;
import com.ridehail.common.ApiExceptions.TooManyRequestsException;
import com.ridehail.common.GeoUtils;
import com.ridehail.pricing.PricingProperties;
import com.ridehail.pricing.RouteEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fares from the road distance and driving time, so the price matches the route the apps draw. Off by default ({@code ridehail.geo.fares-use-roads});
 * when on, a provider that is down or busy never stops a quote: it falls back to the straight line times the road factor.
 */
@Component
@ConditionalOnProperty(prefix = "ridehail.geo", name = "fares-use-roads", havingValue = "true")
class RoadRouteEstimator implements RouteEstimator {
    private static final Logger log = LoggerFactory.getLogger(RoadRouteEstimator.class);
    private final GeoService geo;
    private final PricingProperties pricing;

    RoadRouteEstimator(GeoService geo, PricingProperties pricing) {
        this.geo = geo;
        this.pricing = pricing;
    }

    @Override
    public Route estimate(double fromLat, double fromLon, double toLat, double toLon) {
        try {
            var road = geo.route(fromLat, fromLon, toLat, toLon);
            return new Route(road.distanceKm(), road.durationMin());
        } catch (BadGatewayException | TooManyRequestsException e) {
            log.warn("Road route not available for a fare quote ({}); using the straight-line estimate", e.getMessage());
            double km = GeoUtils.haversineKm(fromLat, fromLon, toLat, toLon) * pricing.roadFactor();
            return new Route(km, km / pricing.avgSpeedKmh() * 60.0);
        }
    }
}
