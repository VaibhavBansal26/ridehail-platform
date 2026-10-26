package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ridehail.dispatch.OfferService;
import com.ridehail.rider.RiderStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

class RideFlowIntegrationTest extends ApiTestBase {

    @Autowired OfferService offerService;

    @Test
    void riderAcceptsCustomerAndTripCompletesAtTheQuotedSurgeFare() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Actor customer = customer("Ravi");

        JsonNode quote = quote(customer);
        UUID trip = book(customer, quote.get("quoteId").asText());
        assertThat(fetch("/api/trips/" + trip, customer).get("status").asText()).isEqualTo("REQUESTED");

        JsonNode offers = fetch("/api/riders/me/offers", rider);
        assertThat(offers).hasSize(1);
        String offerId = offers.get(0).get("offerId").asText();

        call(HttpMethod.POST, "/api/riders/me/offers/" + offerId + "/accept", rider, null).andExpect(status().isNoContent());

        JsonNode accepted = fetch("/api/trips/" + trip, customer);
        assertThat(accepted.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(accepted.get("riderId").asText()).isEqualTo(rider.id().toString());
        assertThat(riderRepo.findById(rider.id()).orElseThrow().getStatus()).isEqualTo(RiderStatus.ON_TRIP);

        call(HttpMethod.POST, "/api/trips/" + trip + "/arrive", rider, null).andExpect(status().isOk());
        call(HttpMethod.POST, "/api/trips/" + trip + "/start", rider, null).andExpect(status().isOk());
        call(HttpMethod.POST, "/api/trips/" + trip + "/complete", rider, null).andExpect(status().isOk());

        JsonNode done = fetch("/api/trips/" + trip, customer);
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("fare").decimalValue()).isEqualByComparingTo(quote.get("total").decimalValue());
        assertThat(riderRepo.findById(rider.id()).orElseThrow().getStatus()).isEqualTo(RiderStatus.AVAILABLE);

        // completion triggers the charge in the background; wait so it cannot leak into the next test
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                paymentRepo.findByTripId(trip).map(p -> p.getStatus().name()).orElse("").equals("CAPTURED"));
    }

    @Test
    void declineOrTimeoutPassesTheTripToTheNextNearestRider() throws Exception {
        Actor near = onlineRider("Near", 0.005);
        Actor mid = onlineRider("Mid", 0.010);
        Actor far = onlineRider("Far", 0.015);
        Actor customer = customer("Ravi");
        UUID trip = book(customer, quote(customer).get("quoteId").asText());

        assertThat(fetch("/api/riders/me/offers", near)).hasSize(1);
        assertThat(fetch("/api/riders/me/offers", mid)).isEmpty();

        String nearOffer = fetch("/api/riders/me/offers", near).get(0).get("offerId").asText();
        call(HttpMethod.POST, "/api/riders/me/offers/" + nearOffer + "/decline", near, null).andExpect(status().isNoContent());
        assertThat(fetch("/api/riders/me/offers", mid)).hasSize(1);

        clock.advance(Duration.ofSeconds(16));
        offerRepo.findAll().forEach(o -> offerService.expire(o.getId()));
        assertThat(fetch("/api/riders/me/offers", mid)).isEmpty();
        assertThat(fetch("/api/riders/me/offers", far)).hasSize(1);

        String farOffer = fetch("/api/riders/me/offers", far).get(0).get("offerId").asText();
        call(HttpMethod.POST, "/api/riders/me/offers/" + farOffer + "/accept", far, null).andExpect(status().isNoContent());
        assertThat(fetch("/api/trips/" + trip, customer).get("riderId").asText()).isEqualTo(far.id().toString());
    }

    @Test
    void expiredOfferCannotBeAcceptedAndOnlyTheOwnerCanRespond() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Actor other = onlineRider("Other", 0.020);
        Actor customer = customer("Ravi");
        book(customer, quote(customer).get("quoteId").asText());
        String offerId = fetch("/api/riders/me/offers", rider).get(0).get("offerId").asText();

        call(HttpMethod.POST, "/api/riders/me/offers/" + offerId + "/accept", other, null).andExpect(status().isForbidden());

        clock.advance(Duration.ofSeconds(16));
        call(HttpMethod.POST, "/api/riders/me/offers/" + offerId + "/accept", rider, null).andExpect(status().isConflict());
    }

    @Test
    void secondAcceptOnTheSameOfferIsRejected() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Actor customer = customer("Ravi");
        book(customer, quote(customer).get("quoteId").asText());
        String offerId = fetch("/api/riders/me/offers", rider).get(0).get("offerId").asText();

        call(HttpMethod.POST, "/api/riders/me/offers/" + offerId + "/accept", rider, null).andExpect(status().isNoContent());
        call(HttpMethod.POST, "/api/riders/me/offers/" + offerId + "/accept", rider, null).andExpect(status().isConflict());
    }

    @Test
    void tripEndsAsNoRidersAvailableWhenNobodyIsNearby() throws Exception {
        Actor customer = customer("Ravi");
        UUID trip = book(customer, quote(customer).get("quoteId").asText());
        assertThat(fetch("/api/trips/" + trip, customer).get("status").asText()).isEqualTo("NO_RIDERS_AVAILABLE");
    }

    @Test
    void faresSurgeWhenDemandOutstripsSupplyAndBookingLocksTheQuotedPrice() throws Exception {
        onlineRider("Only rider", 0.005);

        JsonNode calm = quote(customer("c0"));
        assertThat(calm.get("surgeActive").asBoolean()).isFalse();
        assertThat(calm.get("surgeMultiplier").decimalValue()).isEqualByComparingTo("1.0");

        quote(customer("c1"));
        quote(customer("c2"));
        Actor booker = customer("c3");
        JsonNode surged = quote(booker);
        assertThat(surged.get("surgeActive").asBoolean()).isTrue();
        assertThat(surged.get("surgeMultiplier").decimalValue()).isEqualByComparingTo("2.5");
        assertThat(surged.get("total").decimalValue()).isEqualByComparingTo(
                surged.get("subtotal").decimalValue().multiply(new BigDecimal("2.5")).setScale(2, RoundingMode.HALF_UP));

        onlineRider("R2", 0.006);
        onlineRider("R3", 0.007);
        UUID trip = book(booker, surged.get("quoteId").asText());
        assertThat(fetch("/api/trips/" + trip, booker).get("fare").decimalValue())
                .isEqualByComparingTo(surged.get("total").decimalValue());
    }

    @Test
    void bookingTheSameQuoteTwiceReturnsTheSameTrip() throws Exception {
        onlineRider("Asha", 0.005);
        Actor customer = customer("Ravi");
        String quoteId = quote(customer).get("quoteId").asText();
        assertThat(book(customer, quoteId)).isEqualTo(book(customer, quoteId));
        assertThat(tripRepo.count()).isEqualTo(1);
    }

    @Test
    void customerCancellationFreesTheRiderAndWithdrawsThePendingOffer() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Actor customer = customer("Ravi");
        UUID trip = book(customer, quote(customer).get("quoteId").asText());
        assertThat(fetch("/api/riders/me/offers", rider)).hasSize(1);

        call(HttpMethod.POST, "/api/trips/" + trip + "/cancel", customer, null).andExpect(status().isOk());

        assertThat(fetch("/api/riders/me/offers", rider)).isEmpty();
        assertThat(fetch("/api/trips/" + trip, customer).get("status").asText()).isEqualTo("CANCELLED");
    }
}
