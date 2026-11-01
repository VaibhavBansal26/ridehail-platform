package com.ridehail.geo;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Address search and road routes. Both go through the server, never straight from the apps: the provider's address, key and usage
 * limits stay in one place, results are cached, and a person's searches and trips are not sent to a third party by their phone.
 */
@ConfigurationProperties(prefix = "ridehail.geo")
public record GeoProperties(
        /** OSM: Nominatim-compatible search and OSRM-compatible routing (the public servers, or your own or a paid service speaking the same APIs). FAKE: no network. */
        Provider provider,
        /** Base URL of a Nominatim-compatible search service. */
        String searchUrl,
        /** Base URL of an OSRM-compatible routing service. */
        String routingUrl,
        /** Sent as the User-Agent. Nominatim's policy requires one that identifies this application and a way to contact its operator. */
        String userAgent,
        /** Optional API key for a paid provider, sent as the "key" query parameter. Never logged. */
        String apiKey,
        Duration timeout,
        /** How long an answer is reused. Place names and roads change slowly; this keeps the provider's traffic (and bill) small. */
        Duration searchCacheTtl,
        Duration routeCacheTtl,
        int cacheSize,
        /** A route is thinned to at most this many points before it is sent to a phone. */
        int maxRoutePoints,
        /** Per person: sustained requests per second, and the burst allowed. */
        double searchesPerSecond, int searchBurst,
        double routesPerSecond, int routeBurst,
        /** Across everyone, towards the provider. The public Nominatim allows one request a second in total. */
        double providerSearchesPerSecond, int providerSearchBurst,
        double providerRoutesPerSecond, int providerRouteBurst,
        /** Use road distance and time (instead of straight line times a factor) when quoting fares. Falls back to the factor if the provider is down. */
        boolean faresUseRoads) {

    public enum Provider { OSM, FAKE }
}
