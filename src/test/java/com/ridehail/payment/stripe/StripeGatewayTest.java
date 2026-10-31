package com.ridehail.payment.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.ridehail.payment.GatewayResult.Outcome.AWAITING_FUNDS;
import static com.ridehail.payment.GatewayResult.Outcome.DECLINED;
import static com.ridehail.payment.GatewayResult.Outcome.SUCCEEDED;
import static com.ridehail.payment.GatewayResult.Outcome.TRANSIENT_ERROR;

import com.ridehail.common.ApiExceptions.BadGatewayException;
import com.ridehail.payment.GatewayResult;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The real stripe-java SDK against a local stub: asserts what we send and how Stripe's answers are classified. */
class StripeGatewayTest {

    static final String CHARGE = "POST /v1/payment_intents";
    static final String REFUND = "POST /v1/refunds";
    static final String RETRIEVE_PI = "GET /v1/payment_intents/pi_123";
    static final String TRANSFER = "POST /v1/transfers";
    static final String TOKEN = "cus_1|pm_1";

    static StubStripe stub;
    StripeGateway gateway;

    @BeforeAll static void start() { stub = new StubStripe(); }
    @AfterAll static void stop() { stub.stop(); }

    @BeforeEach
    void setUp() {
        stub.reset();
        gateway = gatewayFor(stub.baseUrl());
    }

    static StripeGateway gatewayFor(String baseUrl) {
        return new StripeGateway(new StripeProperties("sk_test_key", "whsec_x", "pk_test_x", baseUrl, 0, "US",
                "ridehail://done", "ridehail://retry", "ridehail://card/done", "ridehail://card/cancelled"));
    }

    // ---------------------------------------------------------------- charges

    @Test
    void chargeSendsAnOffSessionConfirmedPaymentIntentInCentsWithAnIdempotencyKey() {
        stub.reply(CHARGE, 200, StubStripe.paymentIntent("pi_123", "succeeded"));

        GatewayResult r = gateway.charge("key-1", TOKEN, new BigDecimal("10.99"), "USD");

        assertThat(r.outcome()).isEqualTo(SUCCEEDED);
        assertThat(r.reference()).isEqualTo("pi_123");
        var call = stub.only(CHARGE);
        assertThat(call.form("amount")).isEqualTo("1099");
        assertThat(call.form("currency")).isEqualTo("usd");
        assertThat(call.form("customer")).isEqualTo("cus_1");
        assertThat(call.form("payment_method")).isEqualTo("pm_1");
        assertThat(call.form("confirm")).isEqualTo("true");
        assertThat(call.form("off_session")).isEqualTo("true");
        assertThat(call.form("metadata[" + StripeGateway.KEY_METADATA + "]")).isEqualTo("key-1");
        assertThat(call.header("Idempotency-Key")).isEqualTo("charge-key-1");
        assertThat(call.header("Authorization")).isEqualTo("Bearer sk_test_key");
    }

    @Test
    void aDeclinedCardIsADefinitiveDecline() {
        stub.reply(CHARGE, 402, StubStripe.cardError("card_declined", "insufficient_funds"));

        GatewayResult r = gateway.charge("k", TOKEN, new BigDecimal("5.00"), "USD");

        assertThat(r.outcome()).isEqualTo(DECLINED);
        assertThat(r.message()).contains("insufficient_funds");
    }

    @Test
    void aCardNeedingCustomerAuthenticationCannotBeChargedOffSession() {
        stub.reply(CHARGE, 402, StubStripe.cardError("authentication_required", "authentication_required"));
        assertThat(gateway.charge("k", TOKEN, new BigDecimal("5.00"), "USD").outcome()).isEqualTo(DECLINED);
    }

    @Test
    void stripesRetryableCardErrorsAreRetriedNotDeclined() {
        stub.reply(CHARGE, 402, StubStripe.cardError("processing_error", null));
        assertThat(gateway.charge("k", TOKEN, new BigDecimal("5.00"), "USD").outcome()).isEqualTo(TRANSIENT_ERROR);
    }

    @Test
    void outagesRateLimitsAndBadCredentialsAreTransientSoNobodyIsWronglyDeclined() {
        stub.reply(CHARGE, 500, StubStripe.error("api_error", null, "Something went wrong"));
        assertThat(gateway.charge("k", TOKEN, new BigDecimal("5.00"), "USD").outcome()).isEqualTo(TRANSIENT_ERROR);

        stub.reply(CHARGE, 429, StubStripe.error("rate_limit_error", "rate_limit", "Too many requests"));
        assertThat(gateway.charge("k", TOKEN, new BigDecimal("5.00"), "USD").outcome()).isEqualTo(TRANSIENT_ERROR);

        stub.reply(CHARGE, 401, StubStripe.error("authentication_error", null, "Invalid API Key provided"));
        assertThat(gateway.charge("k", TOKEN, new BigDecimal("5.00"), "USD").outcome()).isEqualTo(TRANSIENT_ERROR);

        // nothing listening at all
        var unreachable = gatewayFor("http://127.0.0.1:1");
        assertThat(unreachable.charge("k", TOKEN, new BigDecimal("5.00"), "USD").outcome()).isEqualTo(TRANSIENT_ERROR);
    }

    @Test
    void anImpossibleRequestIsRejectedNotRetriedForever() {
        stub.reply(CHARGE, 400, StubStripe.error("invalid_request_error", "resource_missing", "No such PaymentMethod"));
        assertThat(gateway.charge("k", TOKEN, new BigDecimal("5.00"), "USD").outcome()).isEqualTo(DECLINED);

        // same idempotency key reused with different parameters: a bug on our side, retrying cannot fix it
        stub.reply(CHARGE, 400, StubStripe.error("idempotency_error", null, "Keys can only be used with the same parameters"));
        assertThat(gateway.charge("k", TOKEN, new BigDecimal("5.00"), "USD").outcome()).isEqualTo(DECLINED);
    }

    @Test
    void aRequestStillInFlightForTheSameKeyIsRetriedLater() {
        stub.reply(CHARGE, 409, StubStripe.error("api_error", null, "A request with this key is still being processed"));
        assertThat(gateway.charge("k", TOKEN, new BigDecimal("5.00"), "USD").outcome()).isEqualTo(TRANSIENT_ERROR);

        stub.reply(CHARGE, 403, StubStripe.error("permission_error", null, "The provided key does not have permission"));
        assertThat(gateway.charge("k", TOKEN, new BigDecimal("5.00"), "USD").outcome()).isEqualTo(TRANSIENT_ERROR);
    }

    @Test
    void anIntentThatIsStillProcessingIsNotTreatedAsFailureOrSuccess() {
        stub.reply(CHARGE, 200, StubStripe.paymentIntent("pi_123", "processing"));
        assertThat(gateway.charge("k", TOKEN, new BigDecimal("5.00"), "USD").outcome()).isEqualTo(TRANSIENT_ERROR);

        stub.reply(CHARGE, 200, StubStripe.paymentIntent("pi_123", "requires_action"));
        assertThat(gateway.charge("k", TOKEN, new BigDecimal("5.00"), "USD").outcome()).isEqualTo(DECLINED);

        stub.reply(CHARGE, 200, StubStripe.paymentIntent("pi_123", "requires_payment_method"));
        assertThat(gateway.charge("k", TOKEN, new BigDecimal("5.00"), "USD").outcome()).isEqualTo(DECLINED);
    }

    @Test
    void aMissingOrMalformedPaymentMethodNeverReachesStripe() {
        for (String bad : new String[] {null, "", "tok_ok", "cus_1", "cus_1|", "x|pm_1", "cus_1|pm_1|extra"}) {
            assertThat(gateway.charge("k", bad, new BigDecimal("5.00"), "USD").outcome()).as(String.valueOf(bad)).isEqualTo(DECLINED);
        }
        assertThat(stub.totalCalls()).isZero();
    }

    // ---------------------------------------------------------------- refunds

    @Test
    void refundTargetsThePaymentIntentWithTheRightAmountAndAnIdempotencyKey() {
        stub.reply(RETRIEVE_PI, 200, StubStripe.paymentIntent("pi_123", "succeeded"));
        stub.reply(REFUND, 200, "{\"id\":\"re_1\",\"object\":\"refund\",\"status\":\"succeeded\"}");

        GatewayResult r = gateway.refund("refund-abc", "pi_123", new BigDecimal("10.99"));

        assertThat(r.outcome()).isEqualTo(SUCCEEDED);
        assertThat(r.reference()).isEqualTo("re_1");
        var call = stub.only(REFUND);
        assertThat(call.form("payment_intent")).isEqualTo("pi_123");
        assertThat(call.form("amount")).isEqualTo("1099");
        assertThat(call.header("Idempotency-Key")).isEqualTo("refund-refund-abc");
    }

    @Test
    void refundingSomethingAlreadyRefundedCountsAsDone() {
        stub.reply(RETRIEVE_PI, 200, StubStripe.paymentIntent("pi_123", "succeeded"));
        stub.reply(REFUND, 400, StubStripe.error("invalid_request_error", "charge_already_refunded", "Charge already refunded"));
        assertThat(gateway.refund("k", "pi_123", new BigDecimal("10.99")).outcome()).isEqualTo(SUCCEEDED);
    }

    @Test
    void aFailedRefundIsNotReportedAsSuccess() {
        stub.reply(RETRIEVE_PI, 200, StubStripe.paymentIntent("pi_123", "succeeded"));
        stub.reply(REFUND, 200, "{\"id\":\"re_1\",\"object\":\"refund\",\"status\":\"failed\"}");
        assertThat(gateway.refund("k", "pi_123", new BigDecimal("10.99")).outcome()).isEqualTo(DECLINED);
    }

    // ---------------------------------------------------------------- payouts (Connect transfers)

    @Test
    void payoutIsATransferToTheRidersConnectedAccount() {
        stub.reply(TRANSFER, 200, "{\"id\":\"tr_1\",\"object\":\"transfer\"}");

        GatewayResult r = gateway.payout("payout-1", "acct_77", new BigDecimal("7.99"), "USD");

        assertThat(r.outcome()).isEqualTo(SUCCEEDED);
        assertThat(r.reference()).isEqualTo("tr_1");
        var call = stub.only(TRANSFER);
        assertThat(call.form("amount")).isEqualTo("799");
        assertThat(call.form("currency")).isEqualTo("usd");
        assertThat(call.form("destination")).isEqualTo("acct_77");
        assertThat(call.header("Idempotency-Key")).isEqualTo("transfer-payout-1");
    }

    @Test
    void payoutWaitsWhenThePlatformBalanceIsNotYetAvailable() {
        // "not yet" is its own outcome: it is not an error and must not use up error retries
        stub.reply(TRANSFER, 400, StubStripe.error("invalid_request_error", "balance_insufficient", "Insufficient funds"));
        assertThat(gateway.payout("k", "acct_77", new BigDecimal("7.99"), "USD").outcome()).isEqualTo(AWAITING_FUNDS);
    }

    @Test
    void availableBalanceIsTheSettledMoneyInThePlatformCurrencyOnly() {
        stub.reply("GET /v1/balance", 200, balance("[{\"amount\":2550,\"currency\":\"usd\"},{\"amount\":9900,\"currency\":\"eur\"}]",
                "[{\"amount\":50000,\"currency\":\"usd\"}]"));
        assertThat(gateway.availableBalance("USD")).hasValue(new BigDecimal("25.50"));  // not the 500.00 still pending
        assertThat(gateway.availableBalance("eur")).hasValue(new BigDecimal("99.00"));

        stub.reply("GET /v1/balance", 200, balance("[]", "[{\"amount\":999,\"currency\":\"usd\"}]"));
        assertThat(gateway.availableBalance("USD")).hasValue(new BigDecimal("0.00"));    // everything is still pending
    }

    @Test
    void ifTheBalanceCannotBeReadWeSayUnknownAndLetTheTransferDecide() {
        stub.reply("GET /v1/balance", 500, StubStripe.error("api_error", null, "boom"));
        assertThat(gateway.availableBalance("USD")).isEmpty();
        stub.reply("GET /v1/balance", 401, StubStripe.error("authentication_error", null, "Invalid API Key"));
        assertThat(gateway.availableBalance("USD")).isEmpty();
    }

    static String balance(String available, String pending) {
        return "{\"object\":\"balance\",\"livemode\":false,\"available\":" + available + ",\"pending\":" + pending + "}";
    }

    @Test
    void payoutToAnInvalidAccountIsRejected() {
        stub.reply(TRANSFER, 400, StubStripe.error("invalid_request_error", "account_invalid", "Account cannot receive transfers"));
        assertThat(gateway.payout("k", "acct_77", new BigDecimal("7.99"), "USD").outcome()).isEqualTo(DECLINED);

        for (String bad : new String[] {null, "", "dest_ok", "cus_1"}) {
            assertThat(gateway.payout("k", bad, new BigDecimal("7.99"), "USD").outcome()).isEqualTo(DECLINED);
        }
        assertThat(stub.calls(TRANSFER)).hasSize(1); // only the first, valid-looking one was sent
    }

    // ---------------------------------------------------------------- onboarding

    @Test
    void cardSetupCreatesTheStripeCustomerOnceAndReturnsAClientSecretForTheMobileSdk() {
        UUID customerId = UUID.randomUUID();
        stub.reply("POST /v1/customers", 200, "{\"id\":\"cus_new\",\"object\":\"customer\"}");
        stub.reply("POST /v1/setup_intents", 200, "{\"id\":\"seti_1\",\"object\":\"setup_intent\",\"client_secret\":\"seti_1_secret_abc\"}");

        var setup = gateway.beginCardSetup(customerId, null);

        assertThat(setup.providerCustomerId()).isEqualTo("cus_new");
        assertThat(setup.clientSecret()).isEqualTo("seti_1_secret_abc");
        assertThat(setup.publishableKey()).isEqualTo("pk_test_x");
        assertThat(stub.only("POST /v1/customers").header("Idempotency-Key")).isEqualTo("customer-" + customerId);
        var intent = stub.only("POST /v1/setup_intents");
        assertThat(intent.form("customer")).isEqualTo("cus_new");
        assertThat(intent.form("usage")).isEqualTo("off_session");

        // second time: reuse the Stripe customer, no new one is created
        stub.reset();
        stub.reply("POST /v1/setup_intents", 200, "{\"id\":\"seti_2\",\"object\":\"setup_intent\",\"client_secret\":\"seti_2_secret\"}");
        assertThat(gateway.beginCardSetup(customerId, "cus_new").providerCustomerId()).isEqualTo("cus_new");
        assertThat(stub.calls("POST /v1/customers")).isEmpty();
    }

    @Test
    void hostedCardCheckoutIsASetupSessionForTheStripeCustomerWithOurReturnLinks() {
        UUID customerId = UUID.randomUUID();
        stub.reply("POST /v1/customers", 200, "{\"id\":\"cus_new\",\"object\":\"customer\"}");
        stub.reply("POST /v1/checkout/sessions", 200,
                "{\"id\":\"cs_1\",\"object\":\"checkout.session\",\"url\":\"https://checkout.stripe.com/c/pay/cs_1\",\"expires_at\":1893456000}");

        var checkout = gateway.beginCardCheckout(customerId, null);

        assertThat(checkout.providerCustomerId()).isEqualTo("cus_new");
        assertThat(checkout.url()).isEqualTo("https://checkout.stripe.com/c/pay/cs_1");
        assertThat(checkout.expiresAt()).isEqualTo(java.time.Instant.ofEpochSecond(1893456000));
        var session = stub.only("POST /v1/checkout/sessions");
        assertThat(session.form("mode")).isEqualTo("setup");
        assertThat(session.form("customer")).isEqualTo("cus_new");
        assertThat(session.form("success_url")).isEqualTo("ridehail://card/done");
        assertThat(session.form("cancel_url")).isEqualTo("ridehail://card/cancelled");
        assertThat(session.form("setup_intent_data[metadata][ridehail_customer_id]")).isEqualTo(customerId.toString());

        // a known Stripe customer is reused
        stub.reset();
        stub.reply("POST /v1/checkout/sessions", 200,
                "{\"id\":\"cs_2\",\"object\":\"checkout.session\",\"url\":\"https://checkout.stripe.com/c/pay/cs_2\",\"expires_at\":1893456000}");
        assertThat(gateway.beginCardCheckout(customerId, "cus_new").providerCustomerId()).isEqualTo("cus_new");
        assertThat(stub.calls("POST /v1/customers")).isEmpty();
    }

    @Test
    void aFailedCardCheckoutIsABadGatewayNotAServerError() {
        stub.reply("POST /v1/checkout/sessions", 500, "{\"error\":{\"message\":\"boom\"}}");
        assertThatThrownBy(() -> gateway.beginCardCheckout(UUID.randomUUID(), "cus_new")).isInstanceOf(BadGatewayException.class);
    }

    @Test
    void payoutOnboardingCreatesAnExpressAccountThatCanReceiveTransfersAndALink() {
        UUID riderId = UUID.randomUUID();
        stub.reply("POST /v1/accounts", 200, "{\"id\":\"acct_1\",\"object\":\"account\"}");
        stub.reply("POST /v1/account_links", 200,
                "{\"object\":\"account_link\",\"created\":1,\"expires_at\":1893456000,\"url\":\"https://connect.stripe.com/setup/e/x\"}");

        var onboarding = gateway.beginPayoutOnboarding(riderId, null);

        assertThat(onboarding.providerAccountId()).isEqualTo("acct_1");
        assertThat(onboarding.url()).isEqualTo("https://connect.stripe.com/setup/e/x");
        assertThat(onboarding.expiresAt().getEpochSecond()).isEqualTo(1893456000L);
        var account = stub.only("POST /v1/accounts");
        assertThat(account.form("type")).isEqualTo("express");
        assertThat(account.form("capabilities[transfers][requested]")).isEqualTo("true");
        assertThat(account.form("country")).isEqualTo("US");
        assertThat(account.header("Idempotency-Key")).isEqualTo("acct-" + riderId);
        var link = stub.only("POST /v1/account_links");
        assertThat(link.form("account")).isEqualTo("acct_1");
        assertThat(link.form("type")).isEqualTo("account_onboarding");
        assertThat(link.form("return_url")).isEqualTo("ridehail://done");
        assertThat(link.form("refresh_url")).isEqualTo("ridehail://retry");
    }

    @Test
    void onboardingFailuresSurfaceAsABadGatewayNotAServerCrash() {
        stub.reply("POST /v1/customers", 500, StubStripe.error("api_error", null, "boom"));
        assertThatThrownBy(() -> gateway.beginCardSetup(UUID.randomUUID(), null)).isInstanceOf(BadGatewayException.class);
        assertThatThrownBy(() -> gateway.beginPayoutOnboarding(UUID.randomUUID(), null)).isInstanceOf(BadGatewayException.class);
    }

    // ---------------------------------------------------------------- misc

    @Test
    void amountsAreConvertedToTheCurrencysSmallestUnit() {
        assertThat(StripeMoney.toMinorUnits(new BigDecimal("10.99"), "USD")).isEqualTo(1099);
        assertThat(StripeMoney.toMinorUnits(new BigDecimal("0.05"), "usd")).isEqualTo(5);
        assertThat(StripeMoney.toMinorUnits(new BigDecimal("500"), "JPY")).isEqualTo(500);   // yen has no minor unit
        assertThat(StripeMoney.fromMinorUnits(1099, "USD")).isEqualByComparingTo("10.99");
        assertThat(StripeMoney.fromMinorUnits(500, "JPY")).isEqualByComparingTo("500");
        assertThatThrownBy(() -> StripeMoney.toMinorUnits(new BigDecimal("1.005"), "USD"))
                .isInstanceOf(ArithmeticException.class);                                      // never round money silently
    }

    @Test
    void theGatewayRefusesToStartWithoutCredentials() {
        assertThatThrownBy(() -> new StripeGateway(new StripeProperties("", "whsec", "pk", null, 0, null, "a", "b", "c", "d")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new StripeGateway(new StripeProperties("sk", "", "pk", null, 0, null, "a", "b", "c", "d")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tokensRoundTrip() {
        assertThat(StripeTokens.parsePaymentMethod(StripeTokens.paymentMethod("cus_1", "pm_9"))).containsExactly("cus_1", "pm_9");
    }
}
