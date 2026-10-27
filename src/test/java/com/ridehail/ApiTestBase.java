package com.ridehail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehail.customer.CustomerRepository;
import com.ridehail.dispatch.TripOfferRepository;
import com.ridehail.payment.FakePaymentGateway;
import com.ridehail.payment.PaymentRepository;
import com.ridehail.payment.PayoutRepository;
import com.ridehail.pricing.FareQuoteRepository;
import com.ridehail.rider.RiderRepository;
import com.ridehail.security.Role;
import com.ridehail.security.UserAccountRepository;
import com.ridehail.trip.TripRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Shared HTTP helpers: real registration/login, real JWTs, no security bypass. */
// offer expiry and payment/payout retries are driven manually by the tests
@SpringBootTest(properties = {"ridehail.dispatch.expiry-scan-ms=3600000", "ridehail.payments.retry-scan-ms=3600000"})
@AutoConfigureMockMvc
@Import(ApiTestBase.TestClock.class)
public abstract class ApiTestBase {

    @TestConfiguration
    static class TestClock {
        @Bean @Primary MutableClock mutableClock() { return new MutableClock(); }
    }

    protected static final double PICKUP_LAT = 12.9716, PICKUP_LON = 77.5946;
    protected static final double DROP_LAT = 12.9352, DROP_LON = 77.6245;

    /** A signed-in user: profile id (customer/rider id) plus bearer token. */
    protected record Actor(UUID id, String token, String refreshToken) {}

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected MutableClock clock;
    @Autowired protected TripOfferRepository offerRepo;
    @Autowired protected TripRepository tripRepo;
    @Autowired protected RiderRepository riderRepo;
    @Autowired protected CustomerRepository customerRepo;
    @Autowired protected FareQuoteRepository quoteRepo;
    @Autowired protected UserAccountRepository accountRepo;
    @Autowired protected PaymentRepository paymentRepo;
    @Autowired protected PayoutRepository payoutRepo;
    /** Present only when the simulated gateway is active (not in the Stripe-mode tests). */
    @Autowired(required = false) protected FakePaymentGateway gateway;
    @Autowired protected JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        if (gateway != null) gateway.reset();
        wipeLedger();
        jdbc.update("delete from disputes");
        jdbc.update("delete from finance_incidents");
        payoutRepo.deleteAll();
        // a charge started by the previous test may still be settling in the background; if we collide with it, try again
        retryOnRace(paymentRepo::deleteAll);
        offerRepo.deleteAll();
        tripRepo.deleteAll();
        quoteRepo.deleteAll();
        riderRepo.deleteAll();
        customerRepo.deleteAll();
        accountRepo.findAll().stream().filter(a -> a.getRole() != Role.ADMIN).forEach(accountRepo::delete);
    }

    private static void retryOnRace(Runnable action) {
        for (int attempt = 1; ; attempt++) {
            try {
                action.run();
                return;
            } catch (org.springframework.dao.OptimisticLockingFailureException e) {
                if (attempt >= 10) throw e;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** The ledger is append-only in production; tests wipe it directly, keeping the platform's own accounts. */
    protected void wipeLedger() {
        jdbc.update("delete from ledger_entries");
        jdbc.update("delete from ledger_transactions");
        jdbc.update("delete from ledger_accounts where type in ('RIDER_PAYABLE', 'RIDER_FUNDS_HELD')");
    }

    // ---------- actors ----------

    /** A customer with a (good) card on file, as the booking rules require. */
    protected Actor customer(String name) throws Exception {
        return customerWithCard(name, "tok_ok");
    }

    protected Actor customerWithCard(String name, String cardToken) throws Exception {
        Actor c = customerWithoutCard(name);
        setCard(c, cardToken);
        return c;
    }

    protected Actor customerWithoutCard(String name) throws Exception {
        return actor(call(HttpMethod.POST, "/api/auth/register/customer", null,
                "{\"name\":\"" + name + "\",\"phone\":\"" + UUID.randomUUID() + "\",\"password\":\"password123\"}"));
    }

    protected void setCard(Actor customer, String token) throws Exception {
        call(HttpMethod.PUT, "/api/customers/me/payment-method", customer, "{\"token\":\"" + token + "\"}")
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
    }

    protected void setPayoutAccount(Actor rider, String token) throws Exception {
        call(HttpMethod.PUT, "/api/riders/me/payout-account", rider, "{\"token\":\"" + token + "\"}")
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
    }

    protected record Ride(UUID tripId, java.math.BigDecimal fare) {}

    /** Books, accepts and drives a trip to completion. Does not wait for the payment to be captured. */
    protected Ride completeRide(Actor customer, Actor rider) throws Exception {
        JsonNode quote = quote(customer);
        UUID trip = book(customer, quote.get("quoteId").asText());
        String offerId = fetch("/api/riders/me/offers", rider).get(0).get("offerId").asText();
        call(HttpMethod.POST, "/api/riders/me/offers/" + offerId + "/accept", rider, null);
        call(HttpMethod.POST, "/api/trips/" + trip + "/arrive", rider, null);
        call(HttpMethod.POST, "/api/trips/" + trip + "/start", rider, null);
        call(HttpMethod.POST, "/api/trips/" + trip + "/complete", rider, null);
        return new Ride(trip, quote.get("total").decimalValue());
    }

    protected Actor riderOffline(String name) throws Exception {
        return actor(call(HttpMethod.POST, "/api/auth/register/rider", null,
                "{\"name\":\"" + name + "\",\"phone\":\"" + UUID.randomUUID()
                        + "\",\"password\":\"password123\",\"vehicleType\":\"SEDAN\"}"));
    }

    protected Actor onlineRider(String name, double latOffset) throws Exception {
        Actor r = riderOffline(name);
        call(HttpMethod.POST, "/api/riders/me/online", r,
                "{\"lat\":" + (PICKUP_LAT + latOffset) + ",\"lon\":" + PICKUP_LON + "}").andReturn();
        return r;
    }

    protected Actor admin() throws Exception {
        return actor(call(HttpMethod.POST, "/api/auth/login", null, "{\"phone\":\"admin\",\"password\":\"dev-admin-password\"}"));
    }

    private Actor actor(ResultActions r) throws Exception {
        JsonNode b = body(r);
        return new Actor(UUID.fromString(b.get("profileId").asText()), b.get("accessToken").asText(),
                b.get("refreshToken").asText());
    }

    // ---------- requests ----------

    protected JsonNode quote(Actor customer) throws Exception {
        return body(call(HttpMethod.POST, "/api/fares/quote", customer, quoteJson()));
    }

    protected String quoteJson() {
        return "{\"vehicleType\":\"SEDAN\",\"pickupLat\":" + PICKUP_LAT + ",\"pickupLon\":" + PICKUP_LON
                + ",\"dropLat\":" + DROP_LAT + ",\"dropLon\":" + DROP_LON + "}";
    }

    protected UUID book(Actor customer, String quoteId) throws Exception {
        return UUID.fromString(body(call(HttpMethod.POST, "/api/trips", customer, "{\"quoteId\":\"" + quoteId + "\"}"))
                .get("id").asText());
    }

    protected ResultActions call(HttpMethod method, String url, Actor who, String content) throws Exception {
        return callWithToken(method, url, who == null ? null : who.token(), content);
    }

    protected ResultActions callWithToken(HttpMethod method, String url, String bearer, String content) throws Exception {
        MockHttpServletRequestBuilder req = MockMvcRequestBuilders.request(method, url);
        if (bearer != null) req.header("Authorization", "Bearer " + bearer);
        if (content != null) req.contentType(MediaType.APPLICATION_JSON).content(content);
        return mvc.perform(req);
    }

    protected JsonNode fetch(String url, Actor who) throws Exception {
        return body(call(HttpMethod.GET, url, who, null));
    }

    protected JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }
}
