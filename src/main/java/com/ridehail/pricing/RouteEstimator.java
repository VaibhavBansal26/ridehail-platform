package com.ridehail.pricing;

/** Port for distance/ETA. Swap the default for a Google/Mapbox adapter without touching pricing. */
public interface RouteEstimator {
    Route estimate(double fromLat, double fromLon, double toLat, double toLon);

    record Route(double distanceKm, double durationMin) {}
}
