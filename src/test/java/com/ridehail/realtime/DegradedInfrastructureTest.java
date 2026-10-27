package com.ridehail.realtime;

import static com.ridehail.testsupport.ApiClient.require;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ridehail.testsupport.ApiClient;
import com.ridehail.testsupport.AppInstance;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Realtime and rate limiting are conveniences layered on a system whose core is booking, trips and payments. When the message
 * broker or Redis disappears, those conveniences must degrade, never take the core down. Nothing listens on port 1.
 */
class DegradedInfrastructureTest {

    static Map<String, String> base(String database) {
        var p = new LinkedHashMap<>(AppInstance.quiet());
        p.put("spring.datasource.url", "jdbc:h2:mem:" + database + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return p;
    }

    @Test
    void whenTheBrokerIsUnreachableTheApplicationStillStartsAndSellsRides() {
        var p = base("degraded-broker");
        p.put("ridehail.realtime.broker", "relay");
        p.put("ridehail.realtime.relay.host", "127.0.0.1");
        p.put("ridehail.realtime.relay.port", "1");

        try (var app = AppInstance.start("no-broker", p)) {
            var api = new ApiClient(app);
            assertThat(api.get("/actuator/health", null).status()).isEqualTo(200);                        // still reports healthy
            assertThat(app.bean(MeterRegistry.class).get("ridehail.realtime.broker.available").gauge().value())
                    .as("operators are told the broker is down").isEqualTo(0.0);

            // the whole booking flow, whose steps each try to push: none of them may fail because the push cannot be delivered
            var customer = api.registerCustomer("customer");
            var rider = api.registerRider("rider");
            require(api.post("/api/riders/me/online", rider.token(), Map.of("lat", 12.9766, "lon", 77.5946)), 200);
            var quote = require(api.post("/api/fares/quote", customer.token(), Map.of("vehicleType", "SEDAN",
                    "pickupLat", 12.9716, "pickupLon", 77.5946, "dropLat", 12.9352, "dropLon", 77.6245)), 201);
            var trip = require(api.post("/api/trips", customer.token(), Map.of("quoteId", quote.text("quoteId"))), 201);
            var offers = api.get("/api/riders/me/offers", rider.token());                                 // the REST fallback still has the offer
            assertThat(offers.body()).hasSize(1);
            require(api.post("/api/riders/me/offers/" + offers.body().get(0).get("offerId").asText() + "/accept", rider.token(), null), 204);
            for (String step : new String[] {"arrive", "start", "complete"}) {
                require(api.post("/api/trips/" + trip.text("id") + "/" + step, rider.token(), null), 200);
            }
            require(api.put("/api/riders/me/location", rider.token(), Map.of("lat", 12.97, "lon", 77.59)), 200);
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    api.get("/api/trips/" + trip.text("id"), customer.token()).text("status").equals("COMPLETED"));
        }
    }

    @Test
    void whenRedisIsUnreachableRateLimitingFallsBackToPerInstanceCountersInsteadOfFailingRequests() {
        var p = base("degraded-redis");
        p.put("ridehail.ratelimit.store", "redis");
        p.put("spring.data.redis.host", "127.0.0.1");
        p.put("spring.data.redis.port", "1");
        p.put("ridehail.tracking.location-updates-per-second", "0.1");

        try (var app = AppInstance.start("no-redis", p)) {
            var api = new ApiClient(app);
            assertThat(api.get("/actuator/health", null).status()).as("a Redis outage must not mark the instance unhealthy").isEqualTo(200);

            var rider = api.registerRider("rider");
            require(api.post("/api/riders/me/online", rider.token(), Map.of("lat", 12.9766, "lon", 77.5946)), 200);

            long started = System.nanoTime();
            int accepted = 0, refused = 0;
            for (int i = 0; i < 8; i++) {
                int status = api.put("/api/riders/me/location", rider.token(), Map.of("lat", 12.97 + i / 1000.0, "lon", 77.59)).status();
                if (status == 200) accepted++;
                else if (status == 429) refused++;
                else throw new AssertionError("Unexpected status " + status);
            }
            long millis = (System.nanoTime() - started) / 1_000_000;

            assertThat(accepted).isEqualTo(5);            // the burst, enforced by the local counters
            assertThat(refused).isEqualTo(3);             // so rate limiting still works, just per instance
            assertThat(millis).as("no request waited on the dead Redis").isLessThan(5_000);
        }
    }
}
