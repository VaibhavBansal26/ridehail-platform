package com.ridehail.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehail.geo.GeoProvider.GeoUnavailableException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The OpenStreetMap-compatible provider against a stand-in for Nominatim and OSRM that speaks their real response formats. */
class OsmGeoProviderTest {
    private record Request(String path, String query, String userAgent) {}

    private HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String reply = "[]";
    private volatile long delayMs = 0;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.add(new Request(ex.getRequestURI().getPath(), ex.getRequestURI().getRawQuery(), ex.getRequestHeaders().getFirst("User-Agent")));
            if (delayMs > 0) try { Thread.sleep(delayMs); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private OsmGeoProvider provider(String apiKey) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var props = new GeoProperties(GeoProperties.Provider.OSM, base, base + "/", "ridehail-test (test@example.com)", apiKey, Duration.ofMillis(800),
                Duration.ofHours(1), Duration.ofHours(1), 100, 250, 1, 5, 1, 5, 1, 2, 5, 10, false);
        return new OsmGeoProvider(props, new ObjectMapper());
    }

    @Test
    void searchAsksNominatimTheRightWayAndReadsItsAnswer() throws Exception {
        reply = """
                [{"place_id":1,"lat":"12.9763","lon":"77.5929","name":"Cubbon Park","display_name":"Cubbon Park, Sampangi Rama Nagara, Bengaluru, Karnataka, India"},
                 {"place_id":2,"lat":"12.98","lon":"77.6","display_name":"Some Road, Bengaluru, India"},
                 {"place_id":3,"lat":"nonsense","lon":"77.6","display_name":"Broken, India"}]""";
        var places = provider(null).search("cubbon park", 12.97, 77.59);

        assertThat(places).extracting(GeoProvider.Place::name).containsExactly("Cubbon Park", "Some Road"); // no name: the first part of the address; no coordinates: dropped
        assertThat(places.get(0).lat()).isEqualTo(12.9763);
        assertThat(places.get(0).address()).startsWith("Cubbon Park, Sampangi");
        Request r = requests.get(0);
        assertThat(r.path()).isEqualTo("/search");
        assertThat(r.query()).contains("q=cubbon+park").contains("format=jsonv2").contains("limit=6").contains("viewbox=");
        assertThat(r.userAgent()).isEqualTo("ridehail-test (test@example.com)"); // Nominatim's policy needs an identifying agent
        assertThat(r.query()).doesNotContain("key=");
    }

    @Test
    void searchEscapesWhatThePersonTyped() throws Exception {
        provider(null).search("a&b=c d#e", null, null);
        assertThat(requests.get(0).query()).contains("q=a%26b%3Dc+d%23e").doesNotContain("viewbox");
    }

    @Test
    void routeAsksOsrmForTheRoadAndReadsDistanceTimeAndLine() throws Exception {
        reply = """
                {"code":"Ok","routes":[{"distance":6612.4,"duration":1110.0,"geometry":{"type":"LineString","coordinates":[[77.5946,12.9716],[77.6,12.973],[77.6408,12.9784]]}}]}""";
        var route = provider(null).route(12.9716, 77.5946, 12.9784, 77.6408);

        assertThat(route.distanceKm()).isEqualTo(6.6124);
        assertThat(route.durationMin()).isEqualTo(18.5);
        assertThat(route.points()).hasSize(3);
        assertThat(route.points().get(0)).isEqualTo(new GeoProvider.Point(12.9716, 77.5946)); // OSRM gives lon,lat; we give lat,lon
        Request r = requests.get(0);
        assertThat(r.path()).isEqualTo("/route/v1/driving/77.594600,12.971600;77.640800,12.978400");
        assertThat(r.query()).contains("overview=full").contains("geometries=geojson");
    }

    @Test
    void anApiKeyGoesOnTheRequestButNeverIntoAnError() throws Exception {
        provider("s3cret-key").search("park", null, null);
        assertThat(requests.get(0).query()).contains("key=s3cret-key");

        status = 503;
        assertThatThrownBy(() -> provider("s3cret-key").search("park", null, null)).isInstanceOf(GeoUnavailableException.class)
                .hasMessageContaining("503").message().doesNotContain("s3cret-key");
    }

    @Test
    void aServiceThatIsDownOrSlowOrIncoherentIsReportedNotThrownAsACrash() {
        status = 500;
        assertThatThrownBy(() -> provider(null).search("park", null, null)).isInstanceOf(GeoUnavailableException.class);
        status = 200;
        reply = "<html>not json</html>";
        assertThatThrownBy(() -> provider(null).search("park", null, null)).isInstanceOf(GeoUnavailableException.class);
        reply = "{\"code\":\"NoRoute\",\"routes\":[]}";
        assertThatThrownBy(() -> provider(null).route(1, 2, 3, 4)).isInstanceOf(GeoUnavailableException.class).hasMessageContaining("NoRoute");
        reply = "[]";
        delayMs = 2500; // longer than the 800 ms timeout
        assertThatThrownBy(() -> provider(null).search("park", null, null)).isInstanceOf(GeoUnavailableException.class);
        assertThat(new AtomicInteger(requests.size()).get()).isGreaterThanOrEqualTo(4);
    }
}
