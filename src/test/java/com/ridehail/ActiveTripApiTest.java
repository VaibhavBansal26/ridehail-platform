package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** An app that was closed or reloaded finds its current trip again with GET /api/trips/active. */
class ActiveTripApiTest extends ApiTestBase {

    @Test
    void noTripMeansNoContent() throws Exception {
        call(HttpMethod.GET, "/api/trips/active", customer("Ravi"), null).andExpect(status().isNoContent());
        call(HttpMethod.GET, "/api/trips/active", onlineRider("Asha", 0.005), null).andExpect(status().isNoContent());
        call(HttpMethod.GET, "/api/trips/active", null, null).andExpect(status().isUnauthorized());
        call(HttpMethod.GET, "/api/trips/active", admin(), null).andExpect(status().isNoContent());
    }

    @Test
    void theTripIsFoundFromRequestToCompletionByItsOwnPartiesOnly() throws Exception {
        Actor ravi = customer("Ravi");
        Actor stranger = customer("Stranger");
        Actor asha = onlineRider("Asha", 0.005);
        Actor other = onlineRider("Other", 0.5);   // too far to be offered anything

        UUID trip = book(ravi, quote(ravi).get("quoteId").asText());

        // requested: the customer has it; the rider has been offered it but has not accepted, so has no trip yet
        JsonNode mine = fetch("/api/trips/active", ravi);
        assertThat(mine.get("id").asText()).isEqualTo(trip.toString());
        assertThat(mine.get("status").asText()).isEqualTo("REQUESTED");
        assertThat(mine.get("pickupLat").asDouble()).isEqualTo(PICKUP_LAT);
        assertThat(mine.get("dropLon").asDouble()).isEqualTo(DROP_LON);
        call(HttpMethod.GET, "/api/trips/active", asha, null).andExpect(status().isNoContent());

        assertThat(mine.get("riderName").isNull()).isTrue();          // nobody is coming yet
        assertThat(mine.get("paymentStatus").isNull()).isTrue();

        // accepted: both parties have it, nobody else does
        String offer = fetch("/api/riders/me/offers", asha).get(0).get("offerId").asText();
        call(HttpMethod.POST, "/api/riders/me/offers/" + offer + "/accept", asha, null);
        assertThat(fetch("/api/trips/active", asha).get("id").asText()).isEqualTo(trip.toString());
        JsonNode accepted = fetch("/api/trips/active", ravi);
        assertThat(accepted.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(accepted.get("riderName").asText()).isEqualTo("Asha");      // who is coming
        assertThat(accepted.get("riderVehicleType").asText()).isEqualTo("SEDAN");
        call(HttpMethod.GET, "/api/trips/active", stranger, null).andExpect(status().isNoContent());
        call(HttpMethod.GET, "/api/trips/active", other, null).andExpect(status().isNoContent());

        // in progress, then finished: it stops being "active"
        call(HttpMethod.POST, "/api/trips/" + trip + "/arrive", asha, null);
        call(HttpMethod.POST, "/api/trips/" + trip + "/start", asha, null);
        assertThat(fetch("/api/trips/active", asha).get("status").asText()).isEqualTo("IN_PROGRESS");
        call(HttpMethod.POST, "/api/trips/" + trip + "/complete", asha, null);
        call(HttpMethod.GET, "/api/trips/active", asha, null).andExpect(status().isNoContent());
        call(HttpMethod.GET, "/api/trips/active", ravi, null).andExpect(status().isNoContent());
    }

    @Test
    void aFinishedTripShowsHowItsPaymentStands() throws Exception {
        Actor ravi = customer("Ravi");
        Actor asha = onlineRider("Asha", 0.005);
        Ride ride = completeRide(ravi, asha);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).pollInterval(java.time.Duration.ofMillis(50))
                .until(() -> "CAPTURED".equals(fetch("/api/trips/" + ride.tripId(), ravi).get("paymentStatus").asText()));
        assertThat(fetch("/api/trips/" + ride.tripId(), asha).get("paymentStatus").asText()).isEqualTo("CAPTURED");
    }

    @Test
    void aCancelledTripIsNotActive() throws Exception {
        Actor ravi = customer("Ravi");
        onlineRider("Asha", 0.005);
        UUID trip = book(ravi, quote(ravi).get("quoteId").asText());
        call(HttpMethod.POST, "/api/trips/" + trip + "/cancel", ravi, null).andExpect(status().isOk());
        call(HttpMethod.GET, "/api/trips/active", ravi, null).andExpect(status().isNoContent());
    }
}
