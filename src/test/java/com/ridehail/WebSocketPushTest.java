package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import com.ridehail.customer.CustomerRepository;
import com.ridehail.dispatch.TripOfferRepository;
import com.ridehail.pricing.FareQuoteRepository;
import com.ridehail.rider.RiderRepository;
import com.ridehail.security.UserAccountRepository;
import com.ridehail.trip.TripRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ridehail.dispatch.expiry-scan-ms=3600000",
                "ridehail.payments.retry-scan-ms=3600000",
                "spring.datasource.url=jdbc:h2:mem:ws-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
class WebSocketPushTest {

    static final double PICKUP_LAT = 12.9716, PICKUP_LON = 77.5946;

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    @Autowired TripOfferRepository offerRepo;
    @Autowired TripRepository tripRepo;
    @Autowired FareQuoteRepository quoteRepo;
    @Autowired RiderRepository riderRepo;
    @Autowired CustomerRepository customerRepo;
    @Autowired UserAccountRepository accountRepo;

    private final List<StompSession> sessions = new ArrayList<>();

    /** Riders left online by an earlier test would win the nearest-rider match, so start clean. */
    @BeforeEach
    void clean() {
        offerRepo.deleteAll();
        tripRepo.deleteAll();
        quoteRepo.deleteAll();
        riderRepo.deleteAll();
        customerRepo.deleteAll();
        accountRepo.deleteAll();
    }

    @AfterEach
    void closeSockets() {
        sessions.forEach(s -> { if (s.isConnected()) s.disconnect(); });
    }

    record User(UUID id, String token) {}

    @Test
    void riderIsPushedTheOfferAndCustomerIsPushedEveryTripUpdate() throws Exception {
        User rider = register("rider", "SEDAN");
        User customer = register("customer", null);

        var riderSocket = connect(rider.token());
        var riderOffers = subscribe(riderSocket, "/user/queue/offers");
        var riderTrips = subscribe(riderSocket, "/user/queue/trips");
        var customerTrips = subscribe(connect(customer.token()), "/user/queue/trips");

        rest("/api/riders/me/online", rider.token(), Map.of("lat", PICKUP_LAT + 0.005, "lon", PICKUP_LON));
        JsonNode quote = rest("/api/fares/quote", customer.token(), Map.of("vehicleType", "SEDAN",
                "pickupLat", PICKUP_LAT, "pickupLon", PICKUP_LON, "dropLat", 12.9352, "dropLon", 77.6245));
        JsonNode trip = rest("/api/trips", customer.token(), Map.of("quoteId", quote.get("quoteId").asText()));

        // the rider is told immediately, with everything needed to decide
        Map<String, Object> offer = await(riderOffers, "OFFER_RECEIVED");
        assertThat(offer.get("tripId")).isEqualTo(trip.get("id").asText());
        assertThat(offer).containsKeys("offerId", "expiresAt", "pickupLat", "dropLat", "fare", "distanceToPickupKm");
        assertThat(offer.get("fare").toString()).isEqualTo(quote.get("total").asText());
        assertThat(await(customerTrips, "TRIP_REQUESTED").get("status")).isEqualTo("REQUESTED");

        // rider accepts over REST -> customer is pushed the assignment with rider details
        rest("/api/riders/me/offers/" + offer.get("offerId") + "/accept", rider.token(), null);
        Map<String, Object> accepted = await(customerTrips, "TRIP_ACCEPTED");
        assertThat(accepted.get("status")).isEqualTo("ACCEPTED");
        assertThat(accepted.get("riderId")).isEqualTo(rider.id().toString());
        assertThat(accepted.get("riderName")).isEqualTo("rider");

        rest("/api/trips/" + trip.get("id").asText() + "/arrive", rider.token(), null);
        assertThat(await(customerTrips, "RIDER_ARRIVED").get("status")).isEqualTo("ARRIVED");
        rest("/api/trips/" + trip.get("id").asText() + "/start", rider.token(), null);
        assertThat(await(customerTrips, "TRIP_STARTED").get("status")).isEqualTo("IN_PROGRESS");
        rest("/api/trips/" + trip.get("id").asText() + "/complete", rider.token(), null);
        assertThat(await(customerTrips, "TRIP_COMPLETED").get("status")).isEqualTo("COMPLETED");

        // the charge happens after completion: customer gets a receipt, rider an earnings notice
        Map<String, Object> receipt = await(customerTrips, "PAYMENT_CAPTURED");
        assertThat(receipt.get("amount").toString()).isEqualTo(quote.get("total").asText());
        Map<String, Object> earning = await(riderTrips, "EARNING_CREDITED");
        assertThat(new java.math.BigDecimal(earning.get("amount").toString()))
                .isLessThan(new java.math.BigDecimal(receipt.get("amount").toString()));
    }

    @Test
    void cancellationWithdrawsThePendingOfferFromTheRider() throws Exception {
        User rider = register("rider", "SEDAN");
        User customer = register("customer", null);
        var riderOffers = subscribe(connect(rider.token()), "/user/queue/offers");

        rest("/api/riders/me/online", rider.token(), Map.of("lat", PICKUP_LAT + 0.005, "lon", PICKUP_LON));
        JsonNode quote = rest("/api/fares/quote", customer.token(), Map.of("vehicleType", "SEDAN",
                "pickupLat", PICKUP_LAT, "pickupLon", PICKUP_LON, "dropLat", 12.9352, "dropLon", 77.6245));
        JsonNode trip = rest("/api/trips", customer.token(), Map.of("quoteId", quote.get("quoteId").asText()));
        Map<String, Object> offer = await(riderOffers, "OFFER_RECEIVED");

        rest("/api/trips/" + trip.get("id").asText() + "/cancel", customer.token(), null);

        Map<String, Object> withdrawn = await(riderOffers, "OFFER_WITHDRAWN");
        assertThat(withdrawn.get("offerId")).isEqualTo(offer.get("offerId"));
    }

    @Test
    void messagesAreDeliveredOnlyToTheirRecipient() throws Exception {
        User rider = register("rider", "SEDAN");
        User customer = register("customer", null);
        User bystanderCustomer = register("bystander", null);
        User bystanderRider = register("bystander-rider", "SEDAN");

        var bystanderTrips = subscribe(connect(bystanderCustomer.token()), "/user/queue/trips");
        var bystanderOffers = subscribe(connect(bystanderRider.token()), "/user/queue/offers");
        var riderOffers = subscribe(connect(rider.token()), "/user/queue/offers");

        rest("/api/riders/me/online", rider.token(), Map.of("lat", PICKUP_LAT + 0.005, "lon", PICKUP_LON));
        JsonNode quote = rest("/api/fares/quote", customer.token(), Map.of("vehicleType", "SEDAN",
                "pickupLat", PICKUP_LAT, "pickupLon", PICKUP_LON, "dropLat", 12.9352, "dropLon", 77.6245));
        rest("/api/trips", customer.token(), Map.of("quoteId", quote.get("quoteId").asText()));
        await(riderOffers, "OFFER_RECEIVED");

        assertThat(bystanderTrips.poll(1, TimeUnit.SECONDS)).isNull();
        assertThat(bystanderOffers.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void connectionWithoutAValidAccessTokenIsRefused() {
        assertThatThrownBy(() -> connect(null)).isNotNull();
        assertThatThrownBy(() -> connect("not-a-jwt")).isNotNull();

        // a refresh token must not open a socket either
        User rider = register("rider", "SEDAN");
        JsonNode refreshToken = login(rider);
        assertThatThrownBy(() -> connect(refreshToken.get("refreshToken").asText())).isNotNull();
    }

    @Test
    void subscriptionsToOtherDestinationsAndRolesAreRefused() throws Exception {
        User customer = register("customer", null);

        // a customer may not listen to rider offers; the server closes the session
        StompSession s = connect(customer.token());
        s.subscribe("/user/queue/offers", handler(new LinkedBlockingQueue<>()));
        awaitDisconnected(s);

        // nobody may subscribe to arbitrary topics
        StompSession s2 = connect(customer.token());
        s2.subscribe("/queue/trips", handler(new LinkedBlockingQueue<>()));
        awaitDisconnected(s2);
    }

    @Test
    void clientsCannotSendMessagesThroughTheSocket() throws Exception {
        User customer = register("customer", null);
        StompSession s = connect(customer.token());
        s.send("/queue/trips", "spoof");
        awaitDisconnected(s);
    }

    // ---------- helpers ----------

    private StompSession connect(String token) throws Exception {
        var client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        var headers = new StompHeaders();
        if (token != null) headers.add("Authorization", "Bearer " + token);
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(),
                headers, new StompSessionHandlerAdapter() {}).get(5, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    private BlockingQueue<Map<String, Object>> subscribe(StompSession session, String destination) throws Exception {
        BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
        session.subscribe(destination, handler(queue)).getSubscriptionId();
        Thread.sleep(200); // let the broker register the subscription before events fire
        return queue;
    }

    private StompFrameHandler handler(BlockingQueue<Map<String, Object>> queue) {
        return new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders headers) { return Map.class; }
            @Override @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) { queue.add((Map<String, Object>) payload); }
        };
    }

    /** Waits for a message of the given type, skipping other types that arrive first. */
    private Map<String, Object> await(BlockingQueue<Map<String, Object>> queue, String type) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> m = queue.poll(500, TimeUnit.MILLISECONDS);
            if (m != null && type.equals(m.get("type"))) return m;
        }
        throw new AssertionError("No " + type + " message received");
    }

    private void awaitDisconnected(StompSession s) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (s.isConnected() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertThat(s.isConnected()).isFalse();
    }

    private User register(String name, String vehicleType) {
        String phone = UUID.randomUUID().toString();
        Map<String, Object> body = vehicleType == null
                ? Map.of("name", name, "phone", phone, "password", "password123")
                : Map.of("name", name, "phone", phone, "password", "password123", "vehicleType", vehicleType);
        JsonNode r = rest.postForObject("/api/auth/register/" + (vehicleType == null ? "customer" : "rider"), body, JsonNode.class);
        phones.put(r.get("profileId").asText(), phone);
        if (vehicleType == null) { // customers need a card on file to book
            var headers = new HttpHeaders();
            headers.setBearerAuth(r.get("accessToken").asText());
            rest.exchange("/api/customers/me/payment-method", HttpMethod.PUT,
                    new HttpEntity<>(Map.of("token", "tok_ok"), headers), Void.class);
        }
        return new User(UUID.fromString(r.get("profileId").asText()), r.get("accessToken").asText());
    }

    private final Map<String, String> phones = new java.util.HashMap<>();

    private JsonNode login(User u) {
        return rest.postForObject("/api/auth/login",
                Map.of("phone", phones.get(u.id().toString()), "password", "password123"), JsonNode.class);
    }

    private JsonNode rest(String url, String token, Object body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        var response = rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("POST " + url + " -> " + response.getStatusCode()).isTrue();
        return response.getBody();
    }
}
