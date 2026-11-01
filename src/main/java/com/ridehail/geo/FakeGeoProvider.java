package com.ridehail.geo;

import com.ridehail.common.GeoUtils;
import java.util.List;
import java.util.Locale;

/** No network: a handful of well-known places for the launch city, and a route that is a straight line. For tests and for working offline. */
class FakeGeoProvider implements GeoProvider {

    static final List<Place> PLACES = List.of(
            new Place("MG Road", "MG Road, Bengaluru, Karnataka, India", 12.9756, 77.6068),
            new Place("Indiranagar", "Indiranagar, Bengaluru, Karnataka, India", 12.9784, 77.6408),
            new Place("Koramangala", "Koramangala, Bengaluru, Karnataka, India", 12.9352, 77.6245),
            new Place("Whitefield", "Whitefield, Bengaluru, Karnataka, India", 12.9698, 77.7499),
            new Place("Jayanagar", "Jayanagar, Bengaluru, Karnataka, India", 12.925, 77.5938),
            new Place("Kempegowda Airport", "Kempegowda International Airport, Bengaluru, Karnataka, India", 13.1986, 77.7066));

    @Override
    public List<Place> search(String query, Double nearLat, Double nearLon) {
        String q = query.toLowerCase(Locale.ROOT);
        return PLACES.stream().filter(p -> p.address().toLowerCase(Locale.ROOT).contains(q)).toList();
    }

    @Override
    public RoadRoute route(double fromLat, double fromLon, double toLat, double toLon) {
        double km = GeoUtils.haversineKm(fromLat, fromLon, toLat, toLon) * 1.3;
        return new RoadRoute(km, km / 25.0 * 60.0, List.of(new Point(fromLat, fromLon), new Point(toLat, toLon)));
    }
}
