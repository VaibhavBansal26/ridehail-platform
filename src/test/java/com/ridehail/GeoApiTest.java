package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ridehail.geo.GeoProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Address search and road routes: who may ask, what is checked, what is remembered, and what happens when the provider is not there. */
class GeoApiTest extends ApiTestBase {

    static class Provider implements GeoProvider {
        final AtomicInteger searches = new AtomicInteger(), routes = new AtomicInteger();
        final List<String> queries = new ArrayList<>();
        volatile boolean down;

        void reset() { searches.set(0); routes.set(0); queries.clear(); down = false; }

        @Override
        public List<Place> search(String query, Double nearLat, Double nearLon) throws GeoUnavailableException {
            searches.incrementAndGet();
            queries.add(query);
            if (down) throw new GeoUnavailableException("provider is down");
            return List.of(new Place("Cubbon Park", "Cubbon Park, Bengaluru, Karnataka, India", 12.9763, 77.5929));
        }

        @Override
        public RoadRoute route(double fromLat, double fromLon, double toLat, double toLon) throws GeoUnavailableException {
            routes.incrementAndGet();
            if (down) throw new GeoUnavailableException("provider is down");
            var pts = new ArrayList<Point>();
            // a winding road that starts and ends exactly where it was asked to
            for (int i = 0; i < 2000; i++) pts.add(new Point(fromLat + (toLat - fromLat) * i / 1999.0, fromLon + (toLon - fromLon) * i / 1999.0 + Math.sin(i / 40.0) * 0.0005 * Math.sin(Math.PI * i / 1999.0)));
            return new RoadRoute(6.6, 18.5, pts);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean @Primary Provider recordingGeoProvider() { return new Provider(); }
    }

    @Autowired Provider provider;

    @BeforeEach
    void fresh() {
        provider.reset();
        clock.advance(Duration.ofDays(2)); // the answers remembered by earlier tests expire, and the shared limits refill
    }

    /** A search the way a browser sends it: the text is encoded for the URL and decoded by the server (MockMvc's own URL handling does neither). */
    private ResultActions search(Actor who, String q) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/places/search").queryParam("q", q).header("Authorization", "Bearer " + who.token()));
    }

    private static final String ROUTE = "/api/routes?fromLat=12.9716&fromLon=77.5946&toLat=12.9784&toLon=77.6408";

    @Test
    void youMustBeSignedInAndBeACustomerOrARider() throws Exception {
        call(HttpMethod.GET, "/api/places/search?q=park", null, null).andExpect(status().isUnauthorized());
        call(HttpMethod.GET, ROUTE, null, null).andExpect(status().isUnauthorized());
        Actor admin = admin();
        call(HttpMethod.GET, "/api/places/search?q=park", admin, null).andExpect(status().isForbidden());
        call(HttpMethod.GET, ROUTE, admin, null).andExpect(status().isForbidden());
    }

    @Test
    void customersAndRidersCanSearchAndAskForARoute() throws Exception {
        for (Actor who : List.of(customer("Ravi"), onlineRider("Asha", 0.005))) {
            JsonNode places = fetch("/api/places/search?q=cubbon+park&lat=12.97&lon=77.59", who);
            assertThat(places).hasSize(1);
            assertThat(places.get(0).get("name").asText()).isEqualTo("Cubbon Park");
            assertThat(places.get(0).get("address").asText()).contains("Bengaluru");
            assertThat(places.get(0).get("lat").asDouble()).isEqualTo(12.9763);

            JsonNode route = fetch(ROUTE, who);
            assertThat(route.get("distanceKm").asDouble()).isEqualTo(6.6);
            assertThat(route.get("durationMin").asDouble()).isEqualTo(18.5);
            assertThat(route.get("points").get(0).get(0).asDouble()).isEqualTo(12.9716); // [lat, lon] pairs, start first
            assertThat(route.get("points").get(route.get("points").size() - 1).get(1).asDouble()).isEqualTo(77.6408);
        }
    }

    @Test
    void badQuestionsAreRefusedBeforeAnythingIsAsked() throws Exception {
        Actor ravi = customer("Ravi");
        call(HttpMethod.GET, "/api/places/search?q=ab", ravi, null).andExpect(status().isBadRequest());
        call(HttpMethod.GET, "/api/places/search?q=" + "x".repeat(201), ravi, null).andExpect(status().isBadRequest());
        call(HttpMethod.GET, "/api/places/search?q=park&lat=12.9", ravi, null).andExpect(status().isBadRequest());     // lat without lon
        call(HttpMethod.GET, "/api/places/search?q=park&lat=95&lon=77", ravi, null).andExpect(status().isBadRequest());
        call(HttpMethod.GET, "/api/routes?fromLat=12.9&fromLon=77.5&toLat=91&toLon=77.6", ravi, null).andExpect(status().isBadRequest());
        call(HttpMethod.GET, "/api/routes?fromLat=12.9&fromLon=77.5", ravi, null).andExpect(status().isBadRequest());     // missing half
        assertThat(provider.searches.get() + provider.routes.get()).isZero();
    }

    @Test
    void controlCharactersAndExtraSpacesNeverReachTheProvider() throws Exception {
        search(customer("Ravi"), "cubbon\t\n\r  park\u0000").andExpect(status().isOk());
        assertThat(provider.queries).containsExactly("cubbon park");
    }

    @Test
    void theSameQuestionAskedAgainIsAnsweredFromMemory() throws Exception {
        Actor ravi = customer("Ravi");
        search(ravi, "Cubbon Park").andExpect(status().isOk());
        search(ravi, "  cubbon   PARK ").andExpect(status().isOk());
        assertThat(provider.searches.get()).isEqualTo(1);

        fetch(ROUTE, ravi);
        fetch("/api/routes?fromLat=12.97162&fromLon=77.59458&toLat=12.97841&toLon=77.64079", ravi); // a few metres away: the same road
        assertThat(provider.routes.get()).isEqualTo(1);

        clock.advance(Duration.ofHours(25)); // memory does not last for ever
        fetch("/api/places/search?q=cubbon+park", ravi);
        assertThat(provider.searches.get()).isEqualTo(2);
    }

    @Test
    void aLongRouteIsThinnedToWhatAPhoneCanDraw() throws Exception {
        JsonNode route = fetch(ROUTE, customer("Ravi"));
        assertThat(route.get("points").size()).isLessThanOrEqualTo(250).isGreaterThan(10);
    }

    @Test
    void aProviderThatIsDownIsA502AndIsNotRememberedAsAnAnswer() throws Exception {
        Actor ravi = customer("Ravi");
        provider.down = true;
        call(HttpMethod.GET, "/api/places/search?q=cubbon+park", ravi, null).andExpect(status().isBadGateway());
        call(HttpMethod.GET, ROUTE, ravi, null).andExpect(status().isBadGateway());
        provider.down = false;
        assertThat(fetch("/api/places/search?q=cubbon+park", ravi)).hasSize(1);
        assertThat(fetch(ROUTE, ravi).get("points")).isNotEmpty();
    }

    @Test
    void onePersonCannotHammerTheSearch() throws Exception {
        Actor ravi = customer("Ravi");
        for (int i = 0; i < 5; i++) call(HttpMethod.GET, "/api/places/search?q=cubbon+park", ravi, null).andExpect(status().isOk()); // the burst
        call(HttpMethod.GET, "/api/places/search?q=cubbon+park", ravi, null).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        call(HttpMethod.GET, "/api/places/search?q=cubbon+park", customer("Meera"), null).andExpect(status().isOk()); // someone else is unaffected
    }

    @Test
    void everyoneTogetherCannotOverloadTheProvider() throws Exception {
        // the public search servers allow about one request a second in total: new questions beyond the burst are refused, repeats are free
        Actor ravi = customer("Ravi");
        call(HttpMethod.GET, "/api/places/search?q=first+place", ravi, null).andExpect(status().isOk());
        call(HttpMethod.GET, "/api/places/search?q=second+place", ravi, null).andExpect(status().isOk());
        call(HttpMethod.GET, "/api/places/search?q=third+place", ravi, null).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        call(HttpMethod.GET, "/api/places/search?q=first+place", ravi, null).andExpect(status().isOk());
        assertThat(provider.searches.get()).isEqualTo(2);
    }
}
