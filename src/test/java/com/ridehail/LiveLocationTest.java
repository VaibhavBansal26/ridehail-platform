package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpMethod;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Live rider position: who is sent it, when, how often, and how often a rider may report it.
 * Pushes are observed on the messaging template; real sockets are covered in WebSocketPushTest.
 */
@SpringBootTest(properties = {
        "ridehail.dispatch.expiry-scan-ms=3600000",
        "ridehail.payments.retry-scan-ms=3600000",
        "spring.datasource.url=jdbc:h2:mem:live-location;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
class LiveLocationTest extends ApiTestBase {

    static final String FEED = "/topic/location";

    @SpyBean SimpMessagingTemplate ws;
    @Autowired com.ridehail.tracking.LocationRateLimiter limiter;

    // ---------------------------------------------------------------- who gets the position

    @Test
    void theCustomerOfTheActiveTripIsSentTheRidersPositionAtEveryStageOfTheTrip() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Actor customer = customer("Ravi");
        UUID trip = acceptedTrip(customer, rider);

        putLocation(rider, 12.9720, 77.5950, 90.0).andExpect(status().isOk());
        Map<?, ?> accepted = pushTo(customer, 1).get(0);
        assertThat(accepted.get("type")).isEqualTo("RIDER_LOCATION");
        assertThat(accepted.get("tripId")).isEqualTo(trip);
        assertThat(accepted.get("status")).isEqualTo("ACCEPTED");
        assertThat(accepted.get("lat")).isEqualTo(12.9720);
        assertThat(accepted.get("lon")).isEqualTo(77.5950);
        assertThat(accepted.get("heading")).isEqualTo(90.0);
        assertThat(accepted.get("at")).isInstanceOf(String.class);          // ISO-8601, no client clock involved

        clock.advance(Duration.ofSeconds(2));
        call(HttpMethod.POST, "/api/trips/" + trip + "/arrive", rider, null).andExpect(status().isOk());
        putLocation(rider, 12.9716, 77.5946, null).andExpect(status().isOk());
        assertThat(pushTo(customer, 2).get(1).get("status")).isEqualTo("ARRIVED");

        clock.advance(Duration.ofSeconds(2));
        call(HttpMethod.POST, "/api/trips/" + trip + "/start", rider, null).andExpect(status().isOk());
        putLocation(rider, 12.9600, 77.6000, 180.0).andExpect(status().isOk());
        assertThat(pushTo(customer, 3).get(2).get("status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void nobodyElseReceivesItNotAnotherCustomerNotTheRiderThemselves() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Actor customer = customer("Ravi");
        Actor stranger = customer("Mallory");
        Actor otherRider = onlineRider("Bystander", 0.5);
        acceptedTrip(customer, rider);

        putLocation(rider, 12.9720, 77.5950, null).andExpect(status().isOk());

        assertThat(pushTo(customer, 1)).hasSize(1);
        verify(ws, never()).convertAndSendToUser(eq(stranger.id().toString()), eq(FEED), any());
        verify(ws, never()).convertAndSendToUser(eq(rider.id().toString()), eq(FEED), any());
        verify(ws, never()).convertAndSendToUser(eq(otherRider.id().toString()), eq(FEED), any());
    }

    @Test
    void aRidersMovementsAreNotSharedWithAnyoneBeforeTheyAreAssignedToATrip() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Actor customer = customer("Ravi");

        putLocation(rider, 12.9720, 77.5950, null).andExpect(status().isOk());          // rider idle: nothing to share
        book(customer, quote(customer).get("quoteId").asText());                        // offered, but not yet accepted
        putLocation(rider, 12.9721, 77.5951, null).andExpect(status().isOk());

        verify(ws, never()).convertAndSendToUser(any(), eq(FEED), any());
    }

    @Test
    void sharingStopsTheMomentTheTripEndsWhetherCompletedOrCancelled() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Actor customer = customer("Ravi");
        Ride ride = completeRide(customer, rider);                                       // trip is now COMPLETED
        assertThat(ride.tripId()).isNotNull();
        awaitCharge(ride.tripId());

        putLocation(rider, 12.9000, 77.6000, null).andExpect(status().isOk());
        verify(ws, never()).convertAndSendToUser(eq(customer.id().toString()), eq(FEED), any());

        Actor rider2 = onlineRider("Second", 0.004);
        Actor customer2 = customer("Second customer");
        UUID trip2 = acceptedTrip(customer2, rider2);
        call(HttpMethod.POST, "/api/trips/" + trip2 + "/cancel", customer2, null).andExpect(status().isOk());
        putLocation(rider2, 12.9001, 77.6001, null).andExpect(status().isOk());
        verify(ws, never()).convertAndSendToUser(eq(customer2.id().toString()), eq(FEED), any());
    }

    // ---------------------------------------------------------------- outbound throttle

    @Test
    void aCustomerIsSentAtMostOnePositionPerIntervalAndAlwaysTheFreshestAfterIt() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Actor customer = customer("Ravi");
        acceptedTrip(customer, rider);

        putLocation(rider, 12.9701, 77.5901, null).andExpect(status().isOk());   // sent
        putLocation(rider, 12.9702, 77.5902, null).andExpect(status().isOk());   // within 2s: dropped, not queued
        putLocation(rider, 12.9703, 77.5903, null).andExpect(status().isOk());   // dropped
        assertThat(pushTo(customer, 1)).hasSize(1);

        clock.advance(Duration.ofMillis(1999));
        putLocation(rider, 12.9704, 77.5904, null).andExpect(status().isOk());   // still just under the interval
        assertThat(pushTo(customer, 1)).hasSize(1);

        clock.advance(Duration.ofMillis(1));
        putLocation(rider, 12.9705, 77.5905, null).andExpect(status().isOk());   // interval elapsed: this one goes
        List<Map<?, ?>> pushes = pushTo(customer, 2);
        assertThat(pushes.get(1).get("lat")).isEqualTo(12.9705);                 // the newest, not a backlog
    }

    // ---------------------------------------------------------------- inbound rate limit

    @Test
    void aRiderWhoReportsTooOftenGetsA429WithRetryAfterAndIsNotStoredOrPushed() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Actor customer = customer("Ravi");
        acceptedTrip(customer, rider);

        for (int i = 1; i <= 5; i++) {                                           // the burst of 5 is accepted
            putLocation(rider, 12.9700 + i / 10000.0, 77.5900, null).andExpect(status().isOk());
        }
        putLocation(rider, 12.0000, 77.0000, null)                               // the 6th is refused
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"));

        var stored = riderRepo.findById(rider.id()).orElseThrow();
        assertThat(stored.getLat()).isCloseTo(12.9705, org.assertj.core.api.Assertions.within(1e-9));   // the refused update never touched the database
        verify(ws, times(1)).convertAndSendToUser(eq(customer.id().toString()), eq(FEED), any());   // nor reached the customer

        clock.advance(Duration.ofSeconds(1));                                    // after the advertised wait it works again
        putLocation(rider, 12.9800, 77.5900, null).andExpect(status().isOk());
        assertThat(riderRepo.findById(rider.id()).orElseThrow().getLat()).isEqualTo(12.9800);
    }

    @Test
    void eachRidersLimitIsTheirOwn() throws Exception {
        Actor noisy = onlineRider("Noisy", 0.005);
        Actor quiet = onlineRider("Quiet", 0.006);

        for (int i = 0; i < 5; i++) putLocation(noisy, 12.97, 77.59, null).andExpect(status().isOk());
        putLocation(noisy, 12.97, 77.59, null).andExpect(status().isTooManyRequests());
        putLocation(quiet, 12.97, 77.59, null).andExpect(status().isOk());       // unaffected by the other rider's flood
    }

    @Test
    void anAppReportingEveryFewSecondsIsNeverLimited() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        for (int i = 0; i < 60; i++) {                                           // a minute of 1 update/second
            putLocation(rider, 12.97 + i / 10000.0, 77.59, null).andExpect(status().isOk());
            clock.advance(Duration.ofSeconds(1));
        }
    }

    @Test
    void invalidPositionsAreRejectedBeforeAnythingElse() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        putLocation(rider, 91.0, 77.0, null).andExpect(status().isBadRequest());
        putLocation(rider, 12.0, 181.0, null).andExpect(status().isBadRequest());
        putLocation(rider, 12.0, 77.0, 361.0).andExpect(status().isBadRequest());
        putLocation(rider, 12.0, 77.0, -1.0).andExpect(status().isBadRequest());
        call(HttpMethod.PUT, "/api/riders/me/location", rider, "{\"lat\":\"north\",\"lon\":77}").andExpect(status().isBadRequest());
        callWithToken(HttpMethod.PUT, "/api/riders/me/location", null, "{\"lat\":12,\"lon\":77}").andExpect(status().isUnauthorized());
        Actor customer = customer("Ravi");
        call(HttpMethod.PUT, "/api/riders/me/location", customer, "{\"lat\":12,\"lon\":77}").andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- REST: last known position

    @Test
    void aCustomerWhoReconnectsCanFetchTheRidersLastKnownPosition() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Actor customer = customer("Ravi");
        UUID trip = acceptedTrip(customer, rider);
        putLocation(rider, 12.9712, 77.5934, null).andExpect(status().isOk());

        JsonNode position = fetch("/api/trips/" + trip + "/rider-location", customer);

        assertThat(position.get("lat").asDouble()).isEqualTo(12.9712);
        assertThat(position.get("lon").asDouble()).isEqualTo(77.5934);
        assertThat(position.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(position.get("at").asText()).isNotBlank();
        call(HttpMethod.GET, "/api/trips/" + trip + "/rider-location", admin(), null).andExpect(status().isOk());
    }

    @Test
    void theRestPositionFollowsTheSamePrivacyRulesAsTheLiveFeed() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Actor customer = customer("Ravi");
        Actor stranger = customer("Mallory");
        Actor otherRider = onlineRider("Bystander", 0.5);
        UUID requested = book(customer, quote(customer).get("quoteId").asText());       // offered, not yet accepted

        call(HttpMethod.GET, "/api/trips/" + requested + "/rider-location", customer, null).andExpect(status().isConflict());

        String offer = fetch("/api/riders/me/offers", rider).get(0).get("offerId").asText();
        call(HttpMethod.POST, "/api/riders/me/offers/" + offer + "/accept", rider, null).andExpect(status().isNoContent());
        String url = "/api/trips/" + requested + "/rider-location";
        call(HttpMethod.GET, url, customer, null).andExpect(status().isOk());
        call(HttpMethod.GET, url, stranger, null).andExpect(status().isForbidden());
        call(HttpMethod.GET, url, otherRider, null).andExpect(status().isForbidden());
        callWithToken(HttpMethod.GET, url, null, null).andExpect(status().isUnauthorized());

        for (String step : new String[] {"arrive", "start", "complete"}) {
            call(HttpMethod.POST, "/api/trips/" + requested + "/" + step, rider, null).andExpect(status().isOk());
        }
        call(HttpMethod.GET, url, customer, null).andExpect(status().isConflict());     // trip over: no more location
        awaitCharge(requested);
    }

    // ---------------------------------------------------------------- helpers

    /** Completing a trip starts a background charge; let it finish so it cannot overlap the next test. */
    private void awaitCharge(UUID tripId) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50)).until(() ->
                paymentRepo.findByTripId(tripId).map(p -> p.getStatus().name()).orElse("").equals("CAPTURED"));
    }

    private UUID acceptedTrip(Actor customer, Actor rider) throws Exception {
        UUID trip = book(customer, quote(customer).get("quoteId").asText());
        String offer = fetch("/api/riders/me/offers", rider).get(0).get("offerId").asText();
        call(HttpMethod.POST, "/api/riders/me/offers/" + offer + "/accept", rider, null).andExpect(status().isNoContent());
        return trip;
    }

    private ResultActions putLocation(Actor rider, double lat, double lon, Double heading) throws Exception {
        String body = "{\"lat\":" + lat + ",\"lon\":" + lon + (heading == null ? "" : ",\"heading\":" + heading) + "}";
        return call(HttpMethod.PUT, "/api/riders/me/location", rider, body);
    }

    /** Asserts exactly {@code expected} location pushes went to this customer so far, and returns their payloads. */
    @SuppressWarnings("unchecked")
    private List<Map<?, ?>> pushTo(Actor customer, int expected) {
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(ws, times(expected)).convertAndSendToUser(eq(customer.id().toString()), eq(FEED), payloads.capture());
        return payloads.getAllValues().stream().<Map<?, ?>>map(p -> (Map<?, ?>) p).toList();
    }
}
