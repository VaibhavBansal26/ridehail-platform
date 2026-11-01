package com.ridehail.pricing;

import com.ridehail.common.GeoUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Straight-line distance inflated by a road factor; good enough until a maps adapter is wired in. */
@Component
@ConditionalOnProperty(prefix = "ridehail.geo", name = "fares-use-roads", havingValue = "false", matchIfMissing = true)
class HaversineRouteEstimator implements RouteEstimator {
    private final PricingProperties props;

    HaversineRouteEstimator(PricingProperties props) {
        this.props = props;
    }

    @Override
    public Route estimate(double fromLat, double fromLon, double toLat, double toLon) {
        double km = GeoUtils.haversineKm(fromLat, fromLon, toLat, toLon) * props.roadFactor();
        return new Route(km, km / props.avgSpeedKmh() * 60.0);
    }
}
