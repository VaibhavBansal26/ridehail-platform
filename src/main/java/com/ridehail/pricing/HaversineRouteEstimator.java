package com.ridehail.pricing;

import com.ridehail.common.GeoUtils;
import org.springframework.stereotype.Component;

/** Straight-line distance inflated by a road factor; good enough until a maps adapter is wired in. */
@Component
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
