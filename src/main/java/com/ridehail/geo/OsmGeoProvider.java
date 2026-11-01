package com.ridehail.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Address search with a Nominatim-compatible service and routing with an OSRM-compatible one: the public servers for a demo, or your own
 * (or a paid service that speaks the same APIs) for anything real. Their public servers are for light use only, so everything here is
 * cached and limited by {@link GeoService}.
 */
class OsmGeoProvider implements GeoProvider {
    private final GeoProperties props;
    private final HttpClient http;
    private final ObjectMapper json;

    OsmGeoProvider(GeoProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(props.timeout()).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    @Override
    public List<Place> search(String query, Double nearLat, Double nearLon) throws GeoUnavailableException {
        var url = new StringBuilder(trim(props.searchUrl())).append("/search?format=jsonv2&limit=6&accept-language=en&q=").append(enc(query));
        if (nearLat != null && nearLon != null) { // prefer results around the person (a hint, not a limit)
            double d = 0.5;
            url.append("&viewbox=").append(nearLon - d).append(',').append(nearLat + d).append(',').append(nearLon + d).append(',').append(nearLat - d);
        }
        JsonNode body = get(url.toString(), "search");
        if (!body.isArray()) throw new GeoUnavailableException("The search service sent something unexpected");
        var places = new ArrayList<Place>();
        for (JsonNode n : body) {
            double lat, lon;
            try {
                lat = Double.parseDouble(n.path("lat").asText());
                lon = Double.parseDouble(n.path("lon").asText());
            } catch (NumberFormatException e) {
                continue; // a result without usable coordinates is no use to a map
            }
            String address = n.path("display_name").asText("");
            String name = n.path("name").asText("");
            if (name.isBlank()) name = address.contains(",") ? address.substring(0, address.indexOf(',')).trim() : address;
            if (!name.isBlank()) places.add(new Place(name, address, lat, lon));
        }
        return places;
    }

    @Override
    public RoadRoute route(double fromLat, double fromLon, double toLat, double toLon) throws GeoUnavailableException {
        String coords = String.format(Locale.ROOT, "%f,%f;%f,%f", fromLon, fromLat, toLon, toLat);
        JsonNode body = get(trim(props.routingUrl()) + "/route/v1/driving/" + coords + "?overview=full&geometries=geojson&alternatives=false&steps=false", "routing");
        JsonNode route = body.path("routes").path(0);
        if (!"Ok".equals(body.path("code").asText()) || route.isMissingNode()) {
            throw new GeoUnavailableException("The routing service found no road route (" + body.path("code").asText("no answer") + ")");
        }
        var points = new ArrayList<Point>();
        for (JsonNode c : route.path("geometry").path("coordinates")) points.add(new Point(c.path(1).asDouble(), c.path(0).asDouble()));
        if (points.size() < 2) throw new GeoUnavailableException("The routing service sent a route with no line");
        return new RoadRoute(route.path("distance").asDouble() / 1000.0, route.path("duration").asDouble() / 60.0, points);
    }

    private JsonNode get(String url, String what) throws GeoUnavailableException {
        // the key (if any) goes on the end of the URL; it is never part of an error message or a log line
        String withKey = props.apiKey() == null || props.apiKey().isBlank() ? url : url + (url.contains("?") ? "&" : "?") + "key=" + enc(props.apiKey());
        try {
            var request = HttpRequest.newBuilder(URI.create(withKey)).timeout(props.timeout()).header("User-Agent", props.userAgent()).header("Accept", "application/json").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new GeoUnavailableException("The " + what + " service answered " + response.statusCode());
            return json.readTree(response.body());
        } catch (IOException e) {
            throw new GeoUnavailableException("The " + what + " service could not be reached or sent something unreadable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeoUnavailableException("Interrupted while waiting for the " + what + " service", e);
        } catch (IllegalArgumentException e) {
            throw new GeoUnavailableException("The " + what + " service address is not valid", e);
        }
    }

    private static String trim(String base) { return base.endsWith("/") ? base.substring(0, base.length() - 1) : base; }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
