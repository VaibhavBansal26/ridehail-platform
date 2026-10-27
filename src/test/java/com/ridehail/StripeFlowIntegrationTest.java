package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ridehail.ledger.AccountType;
import com.ridehail.ledger.LedgerService;
import com.ridehail.dispute.DisputeRepository;
import com.ridehail.dispute.DisputeStatus;
import com.ridehail.dispute.FinanceIncidentRepository;
import com.ridehail.dispute.IncidentStatus;
import com.ridehail.dispute.IncidentType;
import com.ridehail.payment.Payment;
import com.ridehail.payment.PaymentStatus;
import com.ridehail.payment.PayoutService;
import com.ridehail.payment.PayoutStatus;
import com.ridehail.payment.stripe.StubStripe;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The whole app wired to the Stripe adapter, with a local stub standing in for api.stripe.com and webhooks signed
 * exactly the way Stripe signs them.
 */
@SpringBootTest(properties = {
        "ridehail.dispatch.expiry-scan-ms=3600000",
        "ridehail.payments.retry-scan-ms=3600000",
        "ridehail.payments.gateway=stripe",
        "ridehail.stripe.api-key=sk_test_e2e",
        "ridehail.stripe.webhook-secret=" + StripeFlowIntegrationTest.SECRET + " , " + StripeFlowIntegrationTest.CONNECT_SECRET,
        "ridehail.stripe.publishable-key=pk_test_e2e",
        "ridehail.stripe.max-network-retries=0",
        "spring.datasource.url=jdbc:h2:mem:stripe-e2e;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
class StripeFlowIntegrationTest extends ApiTestBase {

    static final String SECRET = "whsec_e2e_secret";
    static final String CONNECT_SECRET = "whsec_e2e_connect_endpoint";
    static final String CHARGE = "POST /v1/payment_intents";
    static final StubStripe stub = new StubStripe();

    @DynamicPropertySource
    static void stripeBase(DynamicPropertyRegistry registry) {
        registry.add("ridehail.stripe.api-base", stub::baseUrl);
    }

    @AfterAll
    static void stopStub() { stub.stop(); }

    @Autowired LedgerService ledger;
    @Autowired PayoutService payoutService;
    @Autowired DisputeRepository disputeRepo;
    @Autowired FinanceIncidentRepository incidentRepo;

    @BeforeEach
    void resetStripe() {
        stub.reset();
        jdbc.update("delete from processed_webhook_events");
    }

    // ---------------------------------------------------------------- card saving

    @Test
    void aCardIsSavedThroughTheProviderSdkAndConfirmedByWebhookNeverByTheClient() throws Exception {
        Actor customer = customerWithoutCard("Ravi");
        stub.reply("POST /v1/customers", 200, "{\"id\":\"cus_ravi\",\"object\":\"customer\"}");
        stub.reply("POST /v1/setup_intents", 200, "{\"id\":\"seti_1\",\"object\":\"setup_intent\",\"client_secret\":\"seti_1_secret\"}");

        JsonNode setup = body(call(HttpMethod.POST, "/api/customers/me/payment-method/setup", customer, null).andExpect(status().isOk()));
        assertThat(setup.get("clientSecret").asText()).isEqualTo("seti_1_secret");
        assertThat(setup.get("publishableKey").asText()).isEqualTo("pk_test_e2e");

        // a client may not just claim a card (it could be someone else's)
        call(HttpMethod.PUT, "/api/customers/me/payment-method", customer, "{\"token\":\"cus_other|pm_stolen\"}")
                .andExpect(status().isForbidden());
        assertThat(fetch("/api/customers/me", customer).get("hasPaymentMethod").asBoolean()).isFalse();

        // the provider confirms that the customer saved a card
        webhook("setup_intent.succeeded", "{\"id\":\"seti_1\",\"customer\":\"cus_ravi\",\"payment_method\":\"pm_visa\"}")
                .andExpect(status().isOk());

        assertThat(fetch("/api/customers/me", customer).get("hasPaymentMethod").asBoolean()).isTrue();
        assertThat(customerRepo.findById(customer.id()).orElseThrow().getPaymentMethodToken()).isEqualTo("cus_ravi|pm_visa");
        assertThat(customerRepo.findById(customer.id()).orElseThrow().getProviderCustomerId()).isEqualTo("cus_ravi");
    }

    // ---------------------------------------------------------------- webhook security

    @Test
    void webhooksWithoutAValidSignatureAreRejected() throws Exception {
        String payload = event("evt_1", "setup_intent.succeeded", "{\"id\":\"seti_1\",\"customer\":\"cus_x\",\"payment_method\":\"pm_x\"}");
        long now = System.currentTimeMillis() / 1000;

        postRaw(payload, null).andExpect(status().isBadRequest());                                          // no header
        postRaw(payload, "t=" + now + ",v1=" + "0".repeat(64)).andExpect(status().isBadRequest());          // wrong signature
        postRaw(payload, sign(payload, now, "whsec_someone_elses")).andExpect(status().isBadRequest());     // wrong secret
        postRaw(payload, sign(payload, now - 3600, SECRET)).andExpect(status().isBadRequest());             // replayed old capture
        postRaw(payload.replace("cus_x", "cus_y"), sign(payload, now, SECRET)).andExpect(status().isBadRequest()); // tampered body
        postRaw("not json", sign("not json", now, SECRET)).andExpect(status().isBadRequest());

        postRaw(payload, sign(payload, now, SECRET)).andExpect(status().isOk());                            // the genuine one
    }

    @Test
    void eventsFromEitherWebhookEndpointAreAcceptedEachWithItsOwnSecret() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        var r = riderRepo.findById(rider.id()).orElseThrow();
        r.setProviderAccountId("acct_connect");
        riderRepo.save(r);

        // account.updated for a connected account arrives on the Connect-scope endpoint, signed with ITS secret
        String payload = event("evt_connect_1", "account.updated", "{\"id\":\"acct_connect\",\"payouts_enabled\":true}");
        long now = System.currentTimeMillis() / 1000;
        postRaw(payload, sign(payload, now, CONNECT_SECRET)).andExpect(status().isOk());
        assertThat(riderRepo.findById(rider.id()).orElseThrow().getPayoutAccountToken()).isEqualTo("acct_connect");

        // and the account-scope endpoint's secret still works
        webhook("customer.created", "{\"id\":\"cus_1\"}").andExpect(status().isOk());
        // while a secret that belongs to neither endpoint does not
        postRaw(payload, sign(payload, now, "whsec_neither")).andExpect(status().isBadRequest());
    }

    @Test
    void redeliveredEventsAreProcessedOnceAndUnknownOnesAreAcknowledged() throws Exception {
        Actor customer = customerWithoutCard("Ravi");
        setProviderCustomer(customer, "cus_dup");
        String payload = event("evt_dup", "setup_intent.succeeded", "{\"id\":\"s\",\"customer\":\"cus_dup\",\"payment_method\":\"pm_1\"}");
        long now = System.currentTimeMillis() / 1000;

        postRaw(payload, sign(payload, now, SECRET)).andExpect(content().string("HANDLED"));
        customerRepo.findById(customer.id()).ifPresent(c -> { c.setPaymentMethodToken(null); customerRepo.save(c); });
        postRaw(payload, sign(payload, now, SECRET)).andExpect(content().string("DUPLICATE")); // same event id: skipped
        assertThat(customerRepo.findById(customer.id()).orElseThrow().getPaymentMethodToken()).isNull();

        webhook("customer.created", "{\"id\":\"cus_1\"}").andExpect(status().isOk()).andExpect(content().string("IGNORED"));
        webhook("charge.dispute.created", "{\"id\":\"dp_1\"}").andExpect(status().isOk()).andExpect(content().string("HANDLED"));
    }

    @Test
    void theWebhookEndpointNeedsNoLoginButStillNothingElseIsOpen() throws Exception {
        callWithToken(HttpMethod.POST, "/api/customers/me/payment-method/setup", null, null).andExpect(status().isUnauthorized());
        callWithToken(HttpMethod.GET, "/api/admin/ledger/trial-balance", null, null).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- rider payout onboarding

    @Test
    void ridersCanOnlyBePaidOnceTheProviderSaysTheirAccountCanReceiveFunds() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        stub.reply("POST /v1/accounts", 200, "{\"id\":\"acct_asha\",\"object\":\"account\"}");
        stub.reply("POST /v1/account_links", 200,
                "{\"object\":\"account_link\",\"created\":1,\"expires_at\":1893456000,\"url\":\"https://connect.stripe.com/setup/e/asha\"}");

        JsonNode onboarding = body(call(HttpMethod.POST, "/api/riders/me/payout-account/onboarding", rider, null).andExpect(status().isOk()));
        assertThat(onboarding.get("url").asText()).isEqualTo("https://connect.stripe.com/setup/e/asha");

        // a rider cannot point payouts at an arbitrary account
        call(HttpMethod.PUT, "/api/riders/me/payout-account", rider, "{\"token\":\"acct_someone_else\"}").andExpect(status().isForbidden());

        // onboarding started but incomplete
        webhook("account.updated", "{\"id\":\"acct_asha\",\"payouts_enabled\":false}").andExpect(status().isOk());
        assertThat(riderRepo.findById(rider.id()).orElseThrow().getPayoutAccountToken()).isNull();
        call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isConflict());

        webhook("account.updated", "{\"id\":\"acct_asha\",\"payouts_enabled\":true}").andExpect(status().isOk());
        assertThat(riderRepo.findById(rider.id()).orElseThrow().getPayoutAccountToken()).isEqualTo("acct_asha");
        assertThat(fetch("/api/riders/me/earnings", rider).get("hasPayoutAccount").asBoolean()).isTrue();

        // Stripe later restricts the account (e.g. verification lapsed)
        webhook("account.updated", "{\"id\":\"acct_asha\",\"payouts_enabled\":false}").andExpect(status().isOk());
        assertThat(riderRepo.findById(rider.id()).orElseThrow().getPayoutAccountToken()).isNull();
    }

    @Test
    void providerFailuresDuringOnboardingAreReportedAs502() throws Exception {
        Actor customer = customerWithoutCard("Ravi");
        stub.reply("POST /v1/customers", 500, StubStripe.error("api_error", null, "boom"));
        call(HttpMethod.POST, "/api/customers/me/payment-method/setup", customer, null).andExpect(status().isBadGateway());
    }

    // ---------------------------------------------------------------- the whole money flow on Stripe

    @Test
    void aRideIsChargedOnStripeSplitInTheLedgerAndPaidOutAsAConnectTransfer() throws Exception {
        Actor customer = stripeCustomer("Ravi", "cus_ravi", "pm_visa");
        Actor rider = stripeRider("Asha", "acct_asha");
        stub.reply(CHARGE, 200, StubStripe.paymentIntent("pi_ride", "succeeded"));

        Ride ride = completeRide(customer, rider);
        Payment payment = awaitPayment(ride.tripId(), PaymentStatus.CAPTURED);

        var charge = stub.only(CHARGE);
        assertThat(charge.form("amount")).isEqualTo(cents(ride.fare()));          // exactly the quoted fare
        assertThat(charge.form("customer")).isEqualTo("cus_ravi");
        assertThat(charge.form("payment_method")).isEqualTo("pm_visa");
        assertThat(charge.form("off_session")).isEqualTo("true");
        assertThat(charge.header("Idempotency-Key")).isEqualTo("charge-" + payment.getChargeKey());
        assertThat(payment.getGatewayReference()).isEqualTo("pi_ride");

        assertThat(ledger.balance(ledger.platform(AccountType.CASH))).isEqualByComparingTo(ride.fare());
        assertThat(ledger.balance(ledger.riderAccount(rider.id()))).isEqualByComparingTo(payment.getRiderAmount());
        assertThat(ledger.trialBalance().balanced()).isTrue();

        // withdraw: becomes a transfer to the rider's connected account
        stub.reply("POST /v1/transfers", 200, "{\"id\":\"tr_1\",\"object\":\"transfer\"}");
        JsonNode payout = body(call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isCreated()));

        assertThat(payout.get("status").asText()).isEqualTo(PayoutStatus.PAID.name());
        var transfer = stub.only("POST /v1/transfers");
        assertThat(transfer.form("amount")).isEqualTo(cents(payment.getRiderAmount()));
        assertThat(transfer.form("destination")).isEqualTo("acct_asha");
        assertThat(transfer.header("Idempotency-Key")).isEqualTo("transfer-" + payout.get("id").asText());
        assertThat(ledger.balance(ledger.riderAccount(rider.id()))).isEqualByComparingTo("0");
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }

    @Test
    void anAdminRefundGoesToStripeAndReversesTheLedger() throws Exception {
        Actor customer = stripeCustomer("Ravi", "cus_ravi", "pm_visa");
        Actor rider = stripeRider("Asha", "acct_asha");
        stub.reply(CHARGE, 200, StubStripe.paymentIntent("pi_ride", "succeeded"));
        Payment payment = awaitPayment(completeRide(customer, rider).tripId(), PaymentStatus.CAPTURED);

        stub.reply("GET /v1/payment_intents/pi_ride", 200, StubStripe.paymentIntent("pi_ride", "succeeded"));
        stub.reply("POST /v1/refunds", 200, "{\"id\":\"re_1\",\"object\":\"refund\",\"status\":\"succeeded\"}");
        call(HttpMethod.POST, "/api/admin/payments/" + payment.getId() + "/refund", admin(), null).andExpect(status().isOk());

        var refund = stub.only("POST /v1/refunds");
        assertThat(refund.form("payment_intent")).isEqualTo("pi_ride");
        assertThat(refund.form("amount")).isEqualTo(cents(payment.getAmount()));
        assertThat(ledger.balance(ledger.platform(AccountType.CASH))).isEqualByComparingTo("0");
        assertThat(ledger.balance(ledger.riderAccount(rider.id()))).isEqualByComparingTo("0");
        assertThat(paymentRepo.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void aDeclinedCardOnStripeStopsTheRiderBeingCreditedAndBlocksTheCustomer() throws Exception {
        Actor customer = stripeCustomer("Ravi", "cus_ravi", "pm_expired");
        Actor rider = stripeRider("Asha", "acct_asha");
        stub.reply(CHARGE, 402, StubStripe.cardError("card_declined", "expired_card"));

        Ride ride = completeRide(customer, rider);
        Payment payment = awaitPayment(ride.tripId(), PaymentStatus.REQUIRES_ATTENTION);

        assertThat(payment.getFailureReason()).isEqualTo("Card declined: expired_card");
        assertThat(ledger.balance(ledger.riderAccount(rider.id()))).isEqualByComparingTo("0");
        String quoteId = quote(customer).get("quoteId").asText();
        call(HttpMethod.POST, "/api/trips", customer, "{\"quoteId\":\"" + quoteId + "\"}").andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------- payouts waiting for Stripe funds

    @Test
    void aPayoutWaitsForSettledStripeFundsAndTheBalanceAvailableWebhookReleasesIt() throws Exception {
        Actor customer = stripeCustomer("Ravi", "cus_ravi", "pm_visa");
        Actor rider = stripeRider("Asha", "acct_asha");
        stub.reply(CHARGE, 200, StubStripe.paymentIntent("pi_ride", "succeeded"));
        Payment payment = awaitPayment(completeRide(customer, rider).tripId(), PaymentStatus.CAPTURED);

        // the card money is in Stripe's PENDING balance only: nothing is available to transfer yet
        stub.reply("GET /v1/balance", 200, balance("[{\"amount\":0,\"currency\":\"usd\"}]", "[{\"amount\":999,\"currency\":\"usd\"}]"));
        JsonNode payout = body(call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isCreated()));

        assertThat(payout.get("status").asText()).isEqualTo("AWAITING_FUNDS");
        assertThat(stub.calls("POST /v1/transfers")).isEmpty();                       // no refused transfer was even attempted
        JsonNode funding = fetch("/api/admin/payouts/funding", admin());
        assertThat(funding.get("providerAvailable").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(funding.get("awaitingCount").asInt()).isEqualTo(1);

        // Stripe settles the funds and says so: the waiting payout goes out straight away, not at the next 15-minute poll
        stub.reply("GET /v1/balance", 200, balance("[{\"amount\":5000,\"currency\":\"usd\"}]", "[]"));
        stub.reply("POST /v1/transfers", 200, "{\"id\":\"tr_1\",\"object\":\"transfer\"}");
        webhook("balance.available", "{\"object\":\"balance\",\"available\":[{\"amount\":5000,\"currency\":\"usd\"}]}")
                .andExpect(status().isOk()).andExpect(content().string("HANDLED"));

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                .until(() -> payoutRepo.findAll().get(0).getStatus() == PayoutStatus.PAID);
        var transfer = stub.only("POST /v1/transfers");
        assertThat(transfer.form("amount")).isEqualTo(cents(payment.getRiderAmount()));
        assertThat(transfer.form("destination")).isEqualTo("acct_asha");
        assertThat(ledger.balance(ledger.riderAccount(rider.id()))).isEqualByComparingTo("0");
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }

    @Test
    void ifStripeItselfRefusesTheTransferForLackOfFundsThePayoutWaitsAndIsRetriedWithTheSameKey() throws Exception {
        Actor customer = stripeCustomer("Ravi", "cus_ravi", "pm_visa");
        Actor rider = stripeRider("Asha", "acct_asha");
        stub.reply(CHARGE, 200, StubStripe.paymentIntent("pi_ride", "succeeded"));
        awaitPayment(completeRide(customer, rider).tripId(), PaymentStatus.CAPTURED);

        stub.reply("GET /v1/balance", 200, balance("[{\"amount\":999999,\"currency\":\"usd\"}]", "[]"));   // looks fine to us ...
        stub.replyOnce("POST /v1/transfers", 400, StubStripe.error("invalid_request_error", "balance_insufficient", "Insufficient funds"));   // ... but not to Stripe
        stub.reply("POST /v1/transfers", 200, "{\"id\":\"tr_2\",\"object\":\"transfer\"}");

        JsonNode payout = body(call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isCreated()));
        assertThat(payout.get("status").asText()).isEqualTo("AWAITING_FUNDS");
        assertThat(payoutRepo.findAll().get(0).getAttempts()).isZero();

        clock.advance(Duration.ofMinutes(16));
        payoutService.retryDue();

        assertThat(payoutRepo.findAll().get(0).getStatus()).isEqualTo(PayoutStatus.PAID);
        var transfers = stub.calls("POST /v1/transfers");
        assertThat(transfers).hasSize(2);
        assertThat(transfers.get(1).header("Idempotency-Key")).isEqualTo(transfers.get(0).header("Idempotency-Key"));
    }

    @Test
    void ifTheBalanceCannotBeReadTheTransferIsStillAttemptedBecauseTheCheckIsOnlyAdvisory() throws Exception {
        Actor customer = stripeCustomer("Ravi", "cus_ravi", "pm_visa");
        Actor rider = stripeRider("Asha", "acct_asha");
        stub.reply(CHARGE, 200, StubStripe.paymentIntent("pi_ride", "succeeded"));
        awaitPayment(completeRide(customer, rider).tripId(), PaymentStatus.CAPTURED);

        stub.reply("GET /v1/balance", 500, StubStripe.error("api_error", null, "boom"));
        stub.reply("POST /v1/transfers", 200, "{\"id\":\"tr_3\",\"object\":\"transfer\"}");

        JsonNode payout = body(call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isCreated()));
        assertThat(payout.get("status").asText()).isEqualTo("PAID");
    }

    // ---------------------------------------------------------------- disputes and payout problems

    @Test
    void aChargebackWebhookFreezesTheRidersShareAndGivesAdminsTheDeadlineAndALinkToRespond() throws Exception {
        Payment payment = paidRide();
        Actor admin = admin();

        webhook("charge.dispute.created", dispute("dp_1", "needs_response", "pi_ride", payment, 1500))
                .andExpect(status().isOk()).andExpect(content().string("HANDLED"));

        var d = disputeRepo.findByProviderId("dp_1").orElseThrow();
        assertThat(d.getStatus()).isEqualTo(DisputeStatus.OPEN);
        assertThat(d.isFrozen()).isTrue();
        assertThat(d.getFeeBooked()).isEqualByComparingTo("15.00");                  // Stripe's dispute fee, from the event
        assertThat(ledger.riderHeldBalance(payment.getRiderId())).isEqualByComparingTo(payment.getRiderAmount());
        assertThat(paymentRepo.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.DISPUTED);

        JsonNode view = fetch("/api/admin/disputes?status=OPEN", admin).get("items").get(0);
        assertThat(view.get("providerUrl").asText()).isEqualTo("https://dashboard.stripe.com/test/disputes/dp_1");
        assertThat(java.time.Instant.parse(view.get("evidenceDueBy").asText()).getEpochSecond()).isEqualTo(1893456000L);
        assertThat(view.get("reason").asText()).isEqualTo("fraudulent");

        // Stripe tells us we lost
        webhook("charge.dispute.closed", dispute("dp_1", "lost", "pi_ride", payment, 1500)).andExpect(status().isOk());

        assertThat(disputeRepo.findByProviderId("dp_1").orElseThrow().getStatus()).isEqualTo(DisputeStatus.LOST);
        assertThat(paymentRepo.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CHARGED_BACK);
        assertThat(ledger.balance(ledger.platform(AccountType.DISPUTE_LOSSES))).isEqualByComparingTo(payment.getAmount());
        assertThat(ledger.riderHeldBalance(payment.getRiderId())).isEqualByComparingTo("0");
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }

    @Test
    void theSameDisputeEventRedeliveredByStripeIsAppliedOnce() throws Exception {
        Payment payment = paidRide();
        String payload = event("evt_dispute_1", "charge.dispute.created", dispute("dp_1", "needs_response", "pi_ride", payment, 0));
        long now = System.currentTimeMillis() / 1000;

        postRaw(payload, sign(payload, now, SECRET)).andExpect(content().string("HANDLED"));
        postRaw(payload, sign(payload, now, SECRET)).andExpect(content().string("DUPLICATE"));

        assertThat(disputeRepo.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ledger_transactions where ref_key like 'DISPUTE_%'", Integer.class)).isEqualTo(1);
    }

    @Test
    void eventsArrivingOutOfOrderStillEndInTheRightState() throws Exception {
        Payment payment = paidRide();

        webhook("charge.dispute.closed", dispute("dp_1", "won", "pi_ride", payment, 0)).andExpect(status().isOk());           // decision first
        webhook("charge.dispute.created", dispute("dp_1", "needs_response", "pi_ride", payment, 0)).andExpect(status().isOk());   // opening, late

        assertThat(disputeRepo.findByProviderId("dp_1").orElseThrow().getStatus()).isEqualTo(DisputeStatus.WON);
        assertThat(paymentRepo.findById(payment.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(ledger.balance(ledger.platform(AccountType.CASH))).isEqualByComparingTo(payment.getAmount());
        assertThat(ledger.riderHeldBalance(payment.getRiderId())).isEqualByComparingTo("0");
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }

    @Test
    void anEarlyWarningIsRecordedWithoutTouchingMoneyAndAClosedWarningStaysThatWay() throws Exception {
        Payment payment = paidRide();

        webhook("charge.dispute.created", dispute("dp_1", "warning_needs_response", "pi_ride", payment, 0)).andExpect(status().isOk());
        assertThat(disputeRepo.findByProviderId("dp_1").orElseThrow().getStatus()).isEqualTo(DisputeStatus.INQUIRY);
        assertThat(ledger.riderHeldBalance(payment.getRiderId())).isEqualByComparingTo("0");

        webhook("charge.dispute.closed", dispute("dp_1", "warning_closed", "pi_ride", payment, 0)).andExpect(status().isOk());
        assertThat(disputeRepo.findByProviderId("dp_1").orElseThrow().getStatus()).isEqualTo(DisputeStatus.CLOSED);
        assertThat(ledger.balance(ledger.platform(AccountType.CASH))).isEqualByComparingTo(payment.getAmount());
    }

    @Test
    void aDisputeOverAChargeWeDoNotKnowIsRecordedForAPersonAndStripeStillGetsA200() throws Exception {
        paidRide();
        webhook("charge.dispute.created", "{\"id\":\"dp_9\",\"amount\":5000,\"currency\":\"usd\",\"payment_intent\":\"pi_somebody_else\","
                + "\"reason\":\"general\",\"status\":\"needs_response\",\"evidence_details\":{\"due_by\":1893456000}}")
                .andExpect(status().isOk());

        var d = disputeRepo.findByProviderId("dp_9").orElseThrow();
        assertThat(d.isMatched()).isFalse();
        assertThat(d.getStatus()).isEqualTo(DisputeStatus.OPEN);
        assertThat(jdbc.queryForObject("select count(*) from ledger_transactions where ref_key like 'DISPUTE_%'", Integer.class)).isZero();
    }

    @Test
    void whenStripeCannotPayARidersBankTheirWithdrawalsPauseUntilTheyUpdateIt() throws Exception {
        Payment payment = paidRide();
        Actor rider = riderOf(payment);

        webhookFor("acct_asha", "payout.failed", "{\"id\":\"po_1\",\"amount\":799,\"currency\":\"usd\",\"status\":\"failed\","
                + "\"failure_code\":\"account_closed\",\"failure_message\":\"The bank account has been closed.\"}").andExpect(status().isOk());

        var incident = incidentRepo.findByTypeAndProviderRef(IncidentType.CONNECT_PAYOUT_FAILED, "po_1").orElseThrow();
        assertThat(incident.getRiderId()).isEqualTo(rider.id());
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.getDetail()).contains("account_closed");
        assertThat(incident.getAmount()).isEqualByComparingTo("7.99");
        call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isConflict());
        assertThat(fetch("/api/riders/me/earnings", rider).get("payoutBlockedReason").asText()).contains("bank");
        assertThat(fetch("/api/admin/finance/attention", admin()).get("needsAction").asBoolean()).isTrue();

        // the rider adds a working bank account in Stripe's dashboard: Stripe sends this for their account
        stub.reply("GET /v1/balance", 200, balance("[{\"amount\":999999,\"currency\":\"usd\"}]", "[]"));
        stub.reply("POST /v1/transfers", 200, "{\"id\":\"tr_ok\",\"object\":\"transfer\"}");
        webhookFor("acct_asha", "account.external_account.updated", "{\"id\":\"ba_new\",\"object\":\"bank_account\",\"account\":\"acct_asha\"}")
                .andExpect(status().isOk());

        assertThat(incidentRepo.findByTypeAndProviderRef(IncidentType.CONNECT_PAYOUT_FAILED, "po_1").orElseThrow().getStatus())
                .isEqualTo(IncidentStatus.RESOLVED);
        call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isCreated());
    }

    @Test
    void aFailedPayoutToThePlatformsOwnBankIsAnIncidentButPausesNoRider() throws Exception {
        Payment payment = paidRide();
        Actor rider = riderOf(payment);

        webhook("payout.failed", "{\"id\":\"po_platform\",\"amount\":50000,\"currency\":\"usd\",\"failure_code\":\"insufficient_funds\"}")
                .andExpect(status().isOk());

        var incident = incidentRepo.findByTypeAndProviderRef(IncidentType.PLATFORM_PAYOUT_FAILED, "po_platform").orElseThrow();
        assertThat(incident.getRiderId()).isNull();
        assertThat(incident.getAmount()).isEqualByComparingTo("500.00");
        stub.reply("GET /v1/balance", 200, balance("[{\"amount\":999999,\"currency\":\"usd\"}]", "[]"));
        stub.reply("POST /v1/transfers", 200, "{\"id\":\"tr_ok\",\"object\":\"transfer\"}");
        call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isCreated());
    }

    @Test
    void aReversedTransferAndAFailedRefundBecomeIncidentsLinkedToOurRecords() throws Exception {
        Payment payment = paidRide();
        Actor rider = riderOf(payment);
        stub.reply("GET /v1/balance", 200, balance("[{\"amount\":999999,\"currency\":\"usd\"}]", "[]"));
        stub.reply("POST /v1/transfers", 200, "{\"id\":\"tr_paid\",\"object\":\"transfer\"}");
        call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isCreated());

        webhook("transfer.reversed", "{\"id\":\"tr_paid\",\"amount\":799,\"amount_reversed\":799,\"currency\":\"usd\",\"destination\":\"acct_asha\"}")
                .andExpect(status().isOk());
        webhook("refund.failed", "{\"id\":\"re_1\",\"amount\":999,\"currency\":\"usd\",\"payment_intent\":\"pi_ride\",\"failure_reason\":\"lost_or_stolen_card\"}")
                .andExpect(status().isOk());

        var reversed = incidentRepo.findByTypeAndProviderRef(IncidentType.TRANSFER_REVERSED, "tr_paid").orElseThrow();
        assertThat(reversed.getRiderId()).isEqualTo(rider.id());
        assertThat(reversed.getPayoutId()).isNotNull();                             // tied to the payout our books say was paid
        assertThat(reversed.getAmount()).isEqualByComparingTo("7.99");
        var refund = incidentRepo.findByTypeAndProviderRef(IncidentType.REFUND_FAILED, "re_1").orElseThrow();
        assertThat(refund.getPaymentId()).isEqualTo(payment.getId());
        assertThat(refund.getDetail()).contains("lost_or_stolen_card");

        JsonNode attention = fetch("/api/admin/finance/attention", admin());
        assertThat(attention.get("incidents").get("open").asInt()).isEqualTo(2);
        assertThat(attention.get("incidents").get("byType").get("TRANSFER_REVERSED").asInt()).isEqualTo(1);
        assertThat(attention.get("incidents").get("byType").get("REFUND_FAILED").asInt()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- late confirmation by webhook

    @Test
    void aChargeThatWasStillProcessingIsCompletedByTheWebhookExactlyOnce() throws Exception {
        Actor customer = stripeCustomer("Ravi", "cus_ravi", "pm_bank");
        Actor rider = stripeRider("Asha", "acct_asha");
        stub.reply(CHARGE, 200, StubStripe.paymentIntent("pi_late", "processing"));

        Ride ride = completeRide(customer, rider);
        Payment pending = awaitPayment(ride.tripId(), p -> p.getAttempts() == 1);
        assertThat(pending.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(ledger.balance(ledger.riderAccount(rider.id()))).isEqualByComparingTo("0");

        // Stripe later reports the money arrived; metadata carries our key
        String intent = "{\"id\":\"pi_late\",\"metadata\":{\"ridehail_key\":\"" + pending.getChargeKey() + "\"}}";
        webhook("payment_intent.succeeded", intent).andExpect(status().isOk());
        Payment done = awaitPayment(ride.tripId(), PaymentStatus.CAPTURED);
        assertThat(done.getGatewayReference()).isEqualTo("pi_late");
        assertThat(ledger.balance(ledger.riderAccount(rider.id()))).isEqualByComparingTo(done.getRiderAmount());

        webhook("payment_intent.succeeded", intent).andExpect(status().isOk());   // Stripe sends it again as a new event
        assertThat(jdbc.queryForObject("select count(*) from ledger_transactions", Integer.class)).isEqualTo(1);
        assertThat(ledger.balance(ledger.platform(AccountType.CASH))).isEqualByComparingTo(done.getAmount());
    }

    @Test
    void moneyThatArrivesAfterWeGaveUpIsStillAccountedFor() throws Exception {
        Actor customer = stripeCustomer("Ravi", "cus_ravi", "pm_bank");
        Actor rider = stripeRider("Asha", "acct_asha");
        stub.reply(CHARGE, 402, StubStripe.cardError("card_declined", "generic_decline"));
        Ride ride = completeRide(customer, rider);
        Payment failed = awaitPayment(ride.tripId(), PaymentStatus.REQUIRES_ATTENTION);

        String intent = "{\"id\":\"pi_surprise\",\"metadata\":{\"ridehail_key\":\"" + failed.getChargeKey() + "\"}}";
        webhook("payment_intent.succeeded", intent).andExpect(status().isOk());

        Payment rescued = awaitPayment(ride.tripId(), PaymentStatus.CAPTURED);
        assertThat(ledger.balance(ledger.riderAccount(rider.id()))).isEqualByComparingTo(rescued.getRiderAmount());
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }

    @Test
    void paymentIntentsThatAreNotOursAreIgnored() throws Exception {
        webhook("payment_intent.succeeded", "{\"id\":\"pi_other\",\"metadata\":{}}").andExpect(status().isOk());
        webhook("payment_intent.succeeded", "{\"id\":\"pi_other\",\"metadata\":{\"ridehail_key\":\"not-a-uuid\"}}").andExpect(status().isOk());
        webhook("payment_intent.succeeded", "{\"id\":\"pi_other\",\"metadata\":{\"ridehail_key\":\"" + UUID.randomUUID() + "\"}}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from ledger_transactions", Integer.class)).isZero();
    }

    // ---------------------------------------------------------------- helpers

    /** A customer whose card was saved the production way: setup call, then the provider's webhook. */
    private Actor stripeCustomer(String name, String stripeCustomerId, String paymentMethodId) throws Exception {
        Actor c = customerWithoutCard(name);
        stub.replyOnce("POST /v1/customers", 200, "{\"id\":\"" + stripeCustomerId + "\",\"object\":\"customer\"}");
        stub.replyOnce("POST /v1/setup_intents", 200, "{\"id\":\"seti_x\",\"object\":\"setup_intent\",\"client_secret\":\"s\"}");
        call(HttpMethod.POST, "/api/customers/me/payment-method/setup", c, null).andExpect(status().isOk());
        webhook("setup_intent.succeeded", "{\"id\":\"seti_x\",\"customer\":\"" + stripeCustomerId + "\",\"payment_method\":\"" + paymentMethodId + "\"}")
                .andExpect(status().isOk());
        return c;
    }

    /** An online rider whose connected account was onboarded the production way. */
    private Actor stripeRider(String name, String accountId) throws Exception {
        Actor r = onlineRider(name, 0.005);
        stub.replyOnce("POST /v1/accounts", 200, "{\"id\":\"" + accountId + "\",\"object\":\"account\"}");
        stub.replyOnce("POST /v1/account_links", 200, "{\"object\":\"account_link\",\"created\":1,\"expires_at\":1893456000,\"url\":\"https://x\"}");
        call(HttpMethod.POST, "/api/riders/me/payout-account/onboarding", r, null).andExpect(status().isOk());
        webhook("account.updated", "{\"id\":\"" + accountId + "\",\"payouts_enabled\":true}").andExpect(status().isOk());
        return r;
    }

    /** A completed, captured ride on Stripe: customer cus_ravi, rider acct_asha (payouts enabled), charge pi_ride. */
    private Payment paidRide() throws Exception {
        Actor customer = stripeCustomer("Ravi", "cus_ravi", "pm_visa");
        Actor rider = stripeRider("Asha", "acct_asha");
        stub.reply(CHARGE, 200, StubStripe.paymentIntent("pi_ride", "succeeded"));
        return awaitPayment(completeRide(customer, rider).tripId(), PaymentStatus.CAPTURED);
    }

    private Actor riderOf(Payment payment) {
        var r = riderRepo.findById(payment.getRiderId()).orElseThrow();
        return new Actor(r.getId(), tokenFor(r.getPhone()), null);
    }

    private String tokenFor(String phone) {
        try {
            return body(call(HttpMethod.POST, "/api/auth/login", (Actor) null,
                    "{\"phone\":\"" + phone + "\",\"password\":\"password123\"}")).get("accessToken").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A Stripe dispute object, in the shape Stripe sends it. */
    private static String dispute(String id, String status, String paymentIntent, Payment p, long feeMinor) {
        return "{\"id\":\"" + id + "\",\"object\":\"dispute\",\"amount\":" + cents(p.getAmount()) + ",\"currency\":\"usd\","
                + "\"charge\":\"ch_1\",\"payment_intent\":\"" + paymentIntent + "\",\"reason\":\"fraudulent\",\"status\":\"" + status + "\","
                + "\"evidence_details\":{\"due_by\":1893456000,\"has_evidence\":false,\"past_due\":false,\"submission_count\":0},"
                + "\"balance_transactions\":[" + (feeMinor > 0
                ? "{\"id\":\"txn_1\",\"object\":\"balance_transaction\",\"amount\":-" + cents(p.getAmount()) + ",\"fee\":" + feeMinor
                  + ",\"net\":-" + (Long.parseLong(cents(p.getAmount())) + feeMinor) + ",\"currency\":\"usd\",\"type\":\"adjustment\"}" : "") + "]}";
    }

    /** An event for a connected account (Stripe adds the top-level "account"). */
    private ResultActions webhookFor(String account, String type, String objectJson) throws Exception {
        String payload = event("evt_" + UUID.randomUUID(), type, objectJson).replaceFirst("\\{", "{\"account\":\"" + account + "\",");
        return postRaw(payload, sign(payload, System.currentTimeMillis() / 1000, SECRET));
    }

    private void setProviderCustomer(Actor customer, String stripeCustomerId) {
        var c = customerRepo.findById(customer.id()).orElseThrow();
        c.setProviderCustomerId(stripeCustomerId);
        customerRepo.save(c);
    }

    private static String balance(String available, String pending) {
        return "{\"object\":\"balance\",\"livemode\":false,\"available\":" + available + ",\"pending\":" + pending + "}";
    }

    private static String cents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).toBigIntegerExact().toString();
    }

    private ResultActions webhook(String type, String objectJson) throws Exception {
        String payload = event("evt_" + UUID.randomUUID(), type, objectJson);
        return postRaw(payload, sign(payload, System.currentTimeMillis() / 1000, SECRET));
    }

    private ResultActions postRaw(String payload, String signature) throws Exception {
        var req = post("/api/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload);
        if (signature != null) req.header("Stripe-Signature", signature);
        return mvc.perform(req); // note: no Authorization header, as Stripe sends it
    }

    private static String event(String id, String type, String objectJson) {
        return "{\"id\":\"" + id + "\",\"object\":\"event\",\"api_version\":\"2025-01-01\",\"created\":"
                + System.currentTimeMillis() / 1000 + ",\"livemode\":false,\"pending_webhooks\":1,\"type\":\"" + type
                + "\",\"data\":{\"object\":" + objectJson + "}}";
    }

    /** Stripe's scheme: HMAC-SHA256 over "<timestamp>.<raw body>", sent as "t=<timestamp>,v1=<hex>". */
    private static String sign(String payload, long timestamp, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String v1 = HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return "t=" + timestamp + ",v1=" + v1;
    }

    private Payment awaitPayment(UUID tripId, PaymentStatus status) {
        return awaitPayment(tripId, p -> p.getStatus() == status);
    }

    private Payment awaitPayment(UUID tripId, java.util.function.Predicate<Payment> condition) {
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                .until(() -> paymentRepo.findByTripId(tripId).filter(condition).isPresent());
        return paymentRepo.findByTripId(tripId).orElseThrow();
    }
}
