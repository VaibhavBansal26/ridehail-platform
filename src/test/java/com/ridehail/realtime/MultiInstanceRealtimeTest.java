package com.ridehail.realtime;

import static com.ridehail.testsupport.ApiClient.require;
import static com.ridehail.testsupport.StompTestClient.await;
import static com.ridehail.testsupport.StompTestClient.expectNone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.ridehail.testsupport.ApiClient;
import com.ridehail.testsupport.ApiClient.Account;
import com.ridehail.testsupport.AppInstance;
import com.ridehail.testsupport.Containers;
import com.ridehail.testsupport.StompTestClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.messaging.simp.stomp.StompHeaders;

/**
 * Two complete copies of the application ("A" and "B"), sharing one database, one RabbitMQ and one Redis, as behind a load
 * balancer. Users hold their WebSocket on one instance while the action that notifies them happens on the other.
 */
@EnabledIf("com.ridehail.testsupport.Containers#dockerAvailable")
class MultiInstanceRealtimeTest {

    static AppInstance a, b;
    static final java.util.concurrent.atomic.AtomicInteger zones = new java.util.concurrent.atomic.AtomicInteger();
    static ApiClient apiA, apiB;

    @BeforeAll
    static void startTwoInstances() {
        Containers.rabbit();
        Containers.redis();
        a = AppInstance.start("a", sharedProperties("create-drop"));
        b = AppInstance.start("b", sharedProperties("none"));      // the schema already exists: A created it in the shared database
        apiA = new ApiClient(a);
        apiB = new ApiClient(b);
    }

    @AfterAll
    static void stopInstances() {
        if (b != null) b.close();
        if (a != null) a.close();                                  // A last: it owns the (create-drop) schema
    }

    static Map<String, String> sharedProperties(String ddl) {
        var p = new LinkedHashMap<>(AppInstance.quiet());
        p.put("spring.datasource.url", "jdbc:h2:mem:multi-instance;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        p.put("spring.jpa.hibernate.ddl-auto", ddl);
        p.put("ridehail.realtime.broker", "relay");
        p.put("ridehail.realtime.relay.host", Containers.rabbitHost());
        p.put("ridehail.realtime.relay.port", String.valueOf(Containers.rabbitStompPort()));
        p.put("ridehail.realtime.relay.system-login", Containers.RABBIT_USER);
        p.put("ridehail.realtime.relay.system-passcode", Containers.RABBIT_PASSWORD);
        p.put("ridehail.realtime.relay.client-login", Containers.RABBIT_USER);
        p.put("ridehail.realtime.relay.client-passcode", Containers.RABBIT_PASSWORD);
        p.put("ridehail.ratelimit.store", "redis");
        p.put("spring.data.redis.host", Containers.redisHost());
        p.put("spring.data.redis.port", String.valueOf(Containers.redisPort()));
        p.put("ridehail.tracking.location-updates-per-second", "0.1");   // a token per 10 s: no refills during a test
        return p;
    }

    /**
     * All tests share one database, so riders from earlier tests are still "available". Each test works in its own patch of
     * the map, far outside the search radius of every other, so a booking can only be offered to this test's rider.
     */
    record Zone(double lat, double lon) {
        double riderLat() { return lat + 0.005; }

        Map<String, Object> quote() {
            return Map.of("vehicleType", "SEDAN", "pickupLat", lat, "pickupLon", lon, "dropLat", lat - 0.036, "dropLon", lon + 0.03);
        }
    }

    static Zone newZone() {
        return new Zone(10.0 + zones.incrementAndGet() * 0.6, 77.5946);   // 0.6 degrees is about 65 km; offers search 5 km
    }

    // ---------------------------------------------------------------- the instances are connected to the broker

    @Test
    void bothInstancesReportTheyAreConnectedToTheBroker() {
        for (AppInstance instance : List.of(a, b)) {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(instance.bean(MeterRegistry.class).get("ridehail.realtime.broker.available").gauge().value())
                            .as(instance + " broker availability").isEqualTo(1.0));
        }
    }

    // ---------------------------------------------------------------- a ride, with each side on the other instance

    @Test
    void aRideWorksWhenTheCustomerIsConnectedToOneInstanceAndTheRiderToTheOther() throws Exception {
        rideBetween(b, a);      // customer's socket on B, rider's socket on A
    }

    @Test
    void andTheSameInTheOppositeDirection() throws Exception {
        rideBetween(a, b);      // customer's socket on A, rider's socket on B
    }

    /**
     * @param customerSide where the customer's WebSocket lives
     * @param riderSide    where the rider's WebSocket lives
     * Every REST call is made on the instance the OTHER party is not connected to, so each push has to cross the broker.
     */
    private void rideBetween(AppInstance customerSide, AppInstance riderSide) throws Exception {
        Zone zone = newZone();
        var onCustomerSide = new ApiClient(customerSide);
        var onRiderSide = new ApiClient(riderSide);
        Account customer = onCustomerSide.registerCustomer("customer");
        Account rider = onRiderSide.registerRider("rider");

        try (var riderSocket = StompTestClient.connect(riderSide.ws(), rider.token());
             var customerSocket = StompTestClient.connect(customerSide.ws(), customer.token())) {
            BlockingQueue<Map<String, Object>> riderOffers = riderSocket.subscribe("/user/topic/offers");
            BlockingQueue<Map<String, Object>> customerTrips = customerSocket.subscribe("/user/topic/trips");

            require(onRiderSide.post("/api/riders/me/online", rider.token(),
                    Map.of("lat", zone.riderLat(), "lon", zone.lon())), 200);

            // customer books on THEIR instance; the offer is pushed from there to the rider's instance
            var quote = require(onCustomerSide.post("/api/fares/quote", customer.token(), zone.quote()), 201);
            var trip = require(onCustomerSide.post("/api/trips", customer.token(), Map.of("quoteId", quote.text("quoteId"))), 201);
            Map<String, Object> offer = await(riderOffers, "OFFER_RECEIVED");
            assertThat(offer.get("tripId")).isEqualTo(trip.text("id"));

            // rider accepts on THEIR instance; the news is pushed from there to the customer's instance
            require(onRiderSide.post("/api/riders/me/offers/" + offer.get("offerId") + "/accept", rider.token(), null), 204);
            Map<String, Object> accepted = await(customerTrips, "TRIP_ACCEPTED");
            assertThat(accepted.get("riderId")).isEqualTo(rider.id().toString());
            assertThat(accepted.get("riderName")).isEqualTo("rider");

            for (String step : new String[] {"arrive", "start", "complete"}) {
                require(onRiderSide.post("/api/trips/" + trip.text("id") + "/" + step, rider.token(), null), 200);
            }
            assertThat(await(customerTrips, "TRIP_COMPLETED").get("status")).isEqualTo("COMPLETED");
            await(customerTrips, "PAYMENT_CAPTURED");
        }
    }

    // ---------------------------------------------------------------- live location and the shared rate limits

    @Test
    void liveLocationReachesTheCustomerAcrossInstancesAndTheRateLimitsAreSharedByBoth() throws Exception {
        Zone zone = newZone();
        Account customer = apiB.registerCustomer("customer");
        Account rider = apiA.registerRider("rider");
        Account bystander = apiB.registerCustomer("bystander");

        try (var customerSocket = StompTestClient.connect(b.ws(), customer.token());
             var bystanderSocket = StompTestClient.connect(a.ws(), bystander.token());
             var riderSocket = StompTestClient.connect(a.ws(), rider.token())) {
            var feed = customerSocket.subscribe("/user/topic/location");
            var bystanderFeed = bystanderSocket.subscribe("/user/topic/location");
            var bystanderTrips = bystanderSocket.subscribe("/user/topic/trips");
            var riderOffers = riderSocket.subscribe("/user/topic/offers");

            require(apiA.post("/api/riders/me/online", rider.token(), Map.of("lat", zone.riderLat(), "lon", zone.lon())), 200);
            var quote = require(apiB.post("/api/fares/quote", customer.token(), zone.quote()), 201);
            require(apiB.post("/api/trips", customer.token(), Map.of("quoteId", quote.text("quoteId"))), 201);
            Map<String, Object> offer = await(riderOffers, "OFFER_RECEIVED");
            require(apiA.post("/api/riders/me/offers/" + offer.get("offerId") + "/accept", rider.token(), null), 204);

            // four updates in quick succession, spread over BOTH instances: the customer is sent ONE
            require(apiA.put("/api/riders/me/location", rider.token(), Map.of("lat", 12.9701, "lon", 77.5901, "heading", 90.0)), 200);
            Map<String, Object> first = await(feed, "RIDER_LOCATION");
            assertThat(first.get("lat")).isEqualTo(12.9701);
            assertThat(first.get("heading")).isEqualTo(90.0);
            require(apiB.put("/api/riders/me/location", rider.token(), Map.of("lat", 12.9702, "lon", 77.5902)), 200);
            require(apiA.put("/api/riders/me/location", rider.token(), Map.of("lat", 12.9703, "lon", 77.5903)), 200);
            require(apiB.put("/api/riders/me/location", rider.token(), Map.of("lat", 12.9704, "lon", 77.5904)), 200);
            expectNone(feed, "RIDER_LOCATION", 700);      // one push per interval for the whole trip, not one per instance

            Thread.sleep(2200);                           // the 2 s push interval passes
            require(apiB.put("/api/riders/me/location", rider.token(), Map.of("lat", 12.9705, "lon", 77.5905)), 200);   // 5th: the burst
            assertThat(await(feed, "RIDER_LOCATION").get("lat")).isEqualTo(12.9705);

            // the rider's own allowance (5, then one per 10 s) is also shared: a 6th update is refused on whichever instance gets it
            var refusedOnA = apiA.put("/api/riders/me/location", rider.token(), Map.of("lat", 1.0, "lon", 1.0));
            assertThat(refusedOnA.status()).isEqualTo(429);
            assertThat(Integer.parseInt(refusedOnA.headers().firstValue("Retry-After").orElseThrow())).isPositive();
            assertThat(apiB.put("/api/riders/me/location", rider.token(), Map.of("lat", 1.0, "lon", 1.0)).status()).isEqualTo(429);

            // and nobody else heard any of it
            expectNone(bystanderFeed, "RIDER_LOCATION", 300);
            expectNone(bystanderTrips, "TRIP_ACCEPTED", 300);
        }
    }

    // ---------------------------------------------------------------- one user, two devices on two instances

    @Test
    void aUserConnectedOnBothInstancesReceivesOnBothOnceTheInstancesHaveSharedWhoIsWhere() throws Exception {
        Zone zone = newZone();
        Account customer = apiA.registerCustomer("two-devices");
        Account rider = apiA.registerRider("rider");

        try (var phone = StompTestClient.connect(a.ws(), customer.token());
             var tablet = StompTestClient.connect(b.ws(), customer.token());
             var riderSocket = StompTestClient.connect(a.ws(), rider.token())) {
            var onPhone = phone.subscribe("/user/topic/trips");
            var onTablet = tablet.subscribe("/user/topic/trips");
            var riderOffers = riderSocket.subscribe("/user/topic/offers");

            require(apiA.post("/api/riders/me/online", rider.token(), Map.of("lat", zone.riderLat(), "lon", zone.lon())), 200);
            var quote = require(apiA.post("/api/fares/quote", customer.token(), zone.quote()), 201);
            require(apiA.post("/api/trips", customer.token(), Map.of("quoteId", quote.text("quoteId"))), 201);
            Map<String, Object> offer = await(riderOffers, "OFFER_RECEIVED");
            require(apiA.post("/api/riders/me/offers/" + offer.get("offerId") + "/accept", rider.token(), null), 204);

            // Instances tell each other who is connected where every ~10 s. Until A has heard about the tablet it can only
            // reach the phone it knows about locally; afterwards every push reaches both devices.
            assertThat(await(onPhone, "TRIP_ACCEPTED").get("status")).isEqualTo("ACCEPTED");
            Thread.sleep(12_000);
            require(apiA.post("/api/trips/" + offer.get("tripId") + "/arrive", rider.token(), null), 200);

            assertThat(await(onPhone, "RIDER_ARRIVED").get("status")).isEqualTo("ARRIVED");
            assertThat(await(onTablet, "RIDER_ARRIVED").get("status")).isEqualTo("ARRIVED");
        }
    }

    // ---------------------------------------------------------------- the relay does not weaken access control

    @Test
    void accessControlIsEnforcedExactlyAsWithoutTheRelay() throws Exception {
        Account customer = apiA.registerCustomer("customer");
        Account rider = apiA.registerRider("rider");

        assertThatThrownBy(() -> StompTestClient.connect(b.ws(), null)).isNotNull();                       // no token
        assertThatThrownBy(() -> StompTestClient.connect(b.ws(), "not-a-jwt")).isNotNull();

        // a rider asking for the customers' location feed, or for the broker's internal destinations, is refused and dropped
        for (String forbidden : List.of("/user/topic/location", "/topic/simp-user-registry",
                "/topic/unresolved-user-destination", "/topic/anything", "/user/topic/offers-user-someone-else")) {
            var socket = StompTestClient.connect(b.ws(), rider.token());
            socket.send("/topic/ignored", "x");   // clients cannot publish at all (this alone closes the session)
            await().atMost(Duration.ofSeconds(5)).until(() -> !socket.isConnected());
            var again = StompTestClient.connect(b.ws(), rider.token());
            var h = new StompHeaders();
            h.setDestination(forbidden);
            try {
                again.subscribe(h);
            } catch (Exception expected) { /* the broker connection is torn down before a receipt can arrive */ }
            await().atMost(Duration.ofSeconds(5)).until(() -> !again.isConnected());
        }
        try (var ok = StompTestClient.connect(b.ws(), customer.token())) {
            ok.subscribe("/user/topic/location");      // while the legitimate subscription works
        }
    }
}
