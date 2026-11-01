package com.ridehail.geo;

import com.ridehail.common.ApiExceptions.BadGatewayException;
import com.ridehail.common.ApiExceptions.TooManyRequestsException;
import com.ridehail.common.RateLimiter;
import com.ridehail.geo.GeoProvider.GeoUnavailableException;
import com.ridehail.geo.GeoProvider.Place;
import com.ridehail.geo.GeoProvider.RoadRoute;
import com.ridehail.ratelimit.RateLimiterFactory;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * What the API offers on top of a {@link GeoProvider}: answers are reused (the provider is slow, rate-limited and may cost money, and
 * the same addresses and roads are asked for again and again), the provider is protected from being asked too often by everyone put
 * together, and a provider that is down becomes a clean 502 the apps can handle by falling back.
 */
@Service
public class GeoService {
    private final GeoProvider provider;
    private final GeoProperties props;
    private final TtlCache<String, List<Place>> searches;
    private final TtlCache<String, RoadRoute> routes;
    private final RateLimiter providerSearches;
    private final RateLimiter providerRoutes;

    GeoService(GeoProvider provider, GeoProperties props, Clock clock, RateLimiterFactory limiters) {
        this.provider = provider;
        this.props = props;
        this.searches = new TtlCache<>(props.cacheSize(), props.searchCacheTtl(), clock);
        this.routes = new TtlCache<>(props.cacheSize(), props.routeCacheTtl(), clock);
        this.providerSearches = limiters.create("geo-provider-search", props.providerSearchesPerSecond(), props.providerSearchBurst());
        this.providerRoutes = limiters.create("geo-provider-route", props.providerRoutesPerSecond(), props.providerRouteBurst());
    }

    public List<Place> search(String query, Double nearLat, Double nearLon) {
        // "Cubbon  Park " and "cubbon park" are the same question; "near" is rounded to about a kilometre so neighbours share answers
        String normal = query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String key = normal + "|" + (nearLat == null || nearLon == null ? "-" : String.format(Locale.ROOT, "%.2f,%.2f", nearLat, nearLon));
        List<Place> cached = searches.get(key);
        if (cached != null) return cached;
        admit(providerSearches, "Address search is busy");
        try {
            List<Place> found = List.copyOf(provider.search(normal, nearLat, nearLon));
            searches.put(key, found);
            return found;
        } catch (GeoUnavailableException e) {
            throw new BadGatewayException("Address search is not available right now", e);
        }
    }

    /** The road route, thinned to a size a phone can draw. */
    public RoadRoute route(double fromLat, double fromLon, double toLat, double toLon) {
        String key = String.format(Locale.ROOT, "%.4f,%.4f>%.4f,%.4f", fromLat, fromLon, toLat, toLon); // about 11 m
        RoadRoute cached = routes.get(key);
        if (cached != null) return cached;
        admit(providerRoutes, "Routing is busy");
        try {
            RoadRoute raw = provider.route(fromLat, fromLon, toLat, toLon);
            var thinned = new RoadRoute(raw.distanceKm(), raw.durationMin(), RoutePoints.thin(raw.points(), props.maxRoutePoints()));
            routes.put(key, thinned);
            return thinned;
        } catch (GeoUnavailableException e) {
            throw new BadGatewayException("Routing is not available right now", e);
        }
    }

    private static void admit(RateLimiter limiter, String message) {
        var decision = limiter.tryAcquire("all");
        if (!decision.allowed()) throw new TooManyRequestsException(message, Math.max(1, (decision.retryAfterMillis() + 999) / 1000));
    }
}
