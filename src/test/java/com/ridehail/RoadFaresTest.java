package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ridehail.common.GeoUtils;
import com.ridehail.geo.GeoProvider;
import com.ridehail.pricing.PricingProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

/** With ridehail.geo.fares-use-roads on, a fare is quoted from the road the apps draw, and a provider that is down never stops a quote. */
@TestPropertySource(properties = "ridehail.geo.fares-use-roads=true")
class RoadFaresTest extends ApiTestBase {

    static class Provider implements GeoProvider {
        volatile boolean down;

        @Override public List<Place> search(String query, Double nearLat, Double nearLon) { return List.of(); }

        @Override
        public RoadRoute route(double fromLat, double fromLon, double toLat, double toLon) throws GeoUnavailableException {
            if (down) throw new GeoUnavailableException("down");
            return new RoadRoute(12.5, 31.0, List.of(new Point(fromLat, fromLon), new Point(toLat, toLon)));
        }
    }

    @TestConfiguration
    static class Config {
        @Bean @Primary Provider roadProvider() { return new Provider(); }
    }

    @Autowired Provider provider;
    @Autowired PricingProperties pricing;

    @BeforeEach
    void fresh() {
        provider.down = false;
        clock.advance(Duration.ofDays(2));
    }

    @Test
    void theFareUsesTheRoadDistanceAndTime() throws Exception {
        JsonNode quote = quote(customer("Ravi"));
        assertThat(quote.get("distanceKm").asDouble()).isEqualTo(12.5);
        assertThat(quote.get("durationMin").asDouble()).isEqualTo(31.0);
    }

    @Test
    void whenTheProviderIsDownTheQuoteFallsBackToTheStraightLineEstimate() throws Exception {
        provider.down = true;
        JsonNode quote = quote(customer("Ravi"));
        double km = GeoUtils.haversineKm(PICKUP_LAT, PICKUP_LON, DROP_LAT, DROP_LON) * pricing.roadFactor();
        assertThat(quote.get("distanceKm").asDouble()).isEqualTo(km, org.assertj.core.data.Offset.offset(1e-6));
    }
}
