package com.ridehail.geo;

import java.util.List;

/** Port for address search and routing: swap the provider without touching the API or the apps. */
public interface GeoProvider {

    /** @param nearLat, nearLon where the person is (null when unknown): results close by are preferred */
    List<Place> search(String query, Double nearLat, Double nearLon) throws GeoUnavailableException;

    RoadRoute route(double fromLat, double fromLon, double toLat, double toLon) throws GeoUnavailableException;

    record Place(String name, String address, double lat, double lon) {}

    record Point(double lat, double lon) {}

    /** Road distance and driving time, and the road itself as a line of points (start to end). */
    record RoadRoute(double distanceKm, double durationMin, List<Point> points) {}

    /** The provider could not answer (down, slow, rate-limited, or sent something unreadable). The caller may retry later or fall back. */
    class GeoUnavailableException extends Exception {
        public GeoUnavailableException(String message, Throwable cause) { super(message, cause); }

        public GeoUnavailableException(String message) { super(message); }
    }
}
