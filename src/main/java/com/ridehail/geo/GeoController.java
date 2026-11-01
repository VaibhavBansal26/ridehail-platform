package com.ridehail.geo;

import com.ridehail.common.ApiExceptions.BadRequestException;
import com.ridehail.common.ApiExceptions.TooManyRequestsException;
import com.ridehail.common.RateLimiter;
import com.ridehail.geo.GeoProvider.Point;
import com.ridehail.ratelimit.RateLimiterFactory;
import com.ridehail.security.CurrentUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Address search and road routes for the customer and rider apps. See {@link GeoService} for what sits behind them. */
@RestController
@RequestMapping("/api")
class GeoController {
    private static final int MIN_QUERY = 3, MAX_QUERY = 200;

    record PlaceView(String name, String address, double lat, double lon) {}

    /** The road as [lat, lon] pairs from the start to the end. */
    record RouteView(double distanceKm, double durationMin, List<double[]> points) {}

    private final GeoService geo;
    private final RateLimiter searchesPerPerson;
    private final RateLimiter routesPerPerson;

    GeoController(GeoService geo, GeoProperties props, RateLimiterFactory limiters) {
        this.geo = geo;
        this.searchesPerPerson = limiters.create("geo-search", props.searchesPerSecond(), props.searchBurst());
        this.routesPerPerson = limiters.create("geo-route", props.routesPerSecond(), props.routeBurst());
    }

    @GetMapping("/places/search")
    List<PlaceView> search(@AuthenticationPrincipal Jwt jwt, @RequestParam("q") String q,
                           @RequestParam(value = "lat", required = false) Double lat, @RequestParam(value = "lon", required = false) Double lon) {
        // control characters have no place in an address, and runs of spaces are noise
        String query = q.replaceAll("\\p{Cntrl}", " ").trim().replaceAll("\\s+", " ");
        if (query.length() < MIN_QUERY || query.length() > MAX_QUERY) throw new BadRequestException("Type " + MIN_QUERY + " to " + MAX_QUERY + " characters to search");
        if ((lat == null) != (lon == null)) throw new BadRequestException("Give both lat and lon, or neither");
        if (lat != null) check(lat, lon);
        limit(searchesPerPerson, jwt, "Searching too fast; wait a moment");
        return geo.search(query, lat, lon).stream().map(p -> new PlaceView(p.name(), p.address(), p.lat(), p.lon())).toList();
    }

    @GetMapping("/routes")
    RouteView route(@AuthenticationPrincipal Jwt jwt, @RequestParam("fromLat") double fromLat, @RequestParam("fromLon") double fromLon,
                    @RequestParam("toLat") double toLat, @RequestParam("toLon") double toLon) {
        check(fromLat, fromLon);
        check(toLat, toLon);
        limit(routesPerPerson, jwt, "Asking for routes too fast; wait a moment");
        var route = geo.route(fromLat, fromLon, toLat, toLon);
        return new RouteView(route.distanceKm(), route.durationMin(), route.points().stream().map((Point p) -> new double[] {p.lat(), p.lon()}).toList());
    }

    private static void check(double lat, double lon) {
        if (!(lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180)) throw new BadRequestException("Latitude must be between -90 and 90 and longitude between -180 and 180");
    }

    private static void limit(RateLimiter limiter, Jwt jwt, String message) {
        var decision = limiter.tryAcquire(CurrentUser.profileId(jwt).toString());
        if (!decision.allowed()) throw new TooManyRequestsException(message, Math.max(1, (decision.retryAfterMillis() + 999) / 1000));
    }
}
