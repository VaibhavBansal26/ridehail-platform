package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.ridehail.payment.PaymentStatus;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * The customer and rider apps' side of the JSON contract. Drives a real ride through the API and checks each response
 * the apps parse against clients/fixtures/*.json, which the apps' zod schemas (clients/packages/api) are tested against.
 * Change an API on purpose with {@code mvn test -Dfixtures.write=true}, review the fixture diff, and the clients'
 * tests then say what to update.
 */
class ClientApiContractTest extends ApiTestBase {
    private static final String DIR = "clients/fixtures";

    private void check(String fixture, JsonNode actual) throws Exception {
        ContractFixtures.check(json, DIR, fixture, actual);
    }

    @Test
    void everyResponseTheAppsParseMatchesItsFixture() throws Exception {
        // sign-up and sign-in
        JsonNode registered = body(call(HttpMethod.POST, "/api/auth/register/customer", null,
                "{\"name\":\"Ravi\",\"phone\":\"" + UUID.randomUUID() + "\",\"password\":\"password123\"}"));
        // only the shape matters, and token-looking strings in git only attract secret scanners
        check("auth-result.json", ((com.fasterxml.jackson.databind.node.ObjectNode) registered.deepCopy())
                .put("accessToken", "<access-token>").put("refreshToken", "<refresh-token>"));

        Actor customer = customer("Ravi");
        Actor rider = onlineRider("Asha", 0.005);
        check("customer-me.json", fetch("/api/customers/me", customer));
        check("rider-me.json", fetch("/api/riders/me", rider));

        // booking
        JsonNode quote = quote(customer);
        check("quote.json", quote);
        JsonNode booked = body(call(HttpMethod.POST, "/api/trips", customer, "{\"quoteId\":\"" + quote.get("quoteId").asText() + "\"}"));
        check("trip-requested.json", booked);
        UUID trip = UUID.fromString(booked.get("id").asText());

        // the rider's side
        JsonNode offers = fetch("/api/riders/me/offers", rider);
        assertThat(offers.get(0).get("fare").decimalValue()).isEqualByComparingTo(quote.get("total").decimalValue());
        assertThat(offers.get(0).get("pickupLat").asDouble()).isEqualTo(PICKUP_LAT);
        check("offers.json", offers);
        call(HttpMethod.POST, "/api/riders/me/offers/" + offers.get(0).get("offerId").asText() + "/accept", rider, null);
        check("trip-accepted.json", fetch("/api/trips/" + trip, customer));
        check("trip-active.json", fetch("/api/trips/active", rider));

        // live position
        call(HttpMethod.PUT, "/api/riders/me/location", rider, "{\"lat\":12.9720,\"lon\":77.5950,\"heading\":90}");
        check("rider-location.json", fetch("/api/trips/" + trip + "/rider-location", customer));

        call(HttpMethod.POST, "/api/trips/" + trip + "/arrive", rider, null);
        call(HttpMethod.POST, "/api/trips/" + trip + "/start", rider, null);
        JsonNode completed = body(call(HttpMethod.POST, "/api/trips/" + trip + "/complete", rider, null));
        check("trip-completed.json", completed);

        // money
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                .until(() -> paymentRepo.findByTripId(trip).filter(p -> p.getStatus() == PaymentStatus.CAPTURED).isPresent());
        JsonNode earnings = fetch("/api/riders/me/earnings", rider);
        assertThat(earnings.get("recent")).isNotEmpty();
        check("earnings.json", earnings);

        setPayoutAccount(rider, "acct_contract");
        JsonNode payout = body(call(HttpMethod.POST, "/api/riders/me/payouts", rider, null));
        check("payout.json", payout);
        check("payouts.json", fetch("/api/riders/me/payouts", rider));

        // card setup (the simulated provider answers it the way Stripe would)
        check("card-setup.json", body(call(HttpMethod.POST, "/api/customers/me/payment-method/setup", customer, null)));
    }
}
