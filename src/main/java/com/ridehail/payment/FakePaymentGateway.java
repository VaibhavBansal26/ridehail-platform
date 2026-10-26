package com.ridehail.payment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In-memory stand-in for a real provider, behaving the way real ones do (idempotency keys, declines,
 * outages). It is NOT available in the "prod" profile: production fails to start until a real
 * PaymentGateway adapter is provided.
 * Magic tokens: a payment method starting "tok_decline" is declined; a payout destination starting
 * "dest_decline" is rejected.
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(name = "ridehail.payments.gateway", havingValue = "fake", matchIfMissing = true)
public class FakePaymentGateway implements PaymentGateway, OnboardingGateway {

    private final Map<String, GatewayResult> resultsByKey = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger transientFailuresLeft = new AtomicInteger();

    @Override
    public GatewayResult charge(String key, String token, BigDecimal amount, String currency) {
        return handle("charge", key, () -> token == null || token.startsWith("tok_decline")
                ? GatewayResult.declined("Card declined") : GatewayResult.ok("ch_" + key));
    }

    @Override
    public GatewayResult refund(String key, String chargeReference, BigDecimal amount) {
        return handle("refund", key, () -> GatewayResult.ok("re_" + key));
    }

    @Override
    public GatewayResult payout(String key, String destination, BigDecimal amount, String currency) {
        return handle("payout", key, () -> destination == null || destination.startsWith("dest_decline")
                ? GatewayResult.declined("Destination rejected") : GatewayResult.ok("po_" + key));
    }

    private GatewayResult handle(String op, String key, java.util.function.Supplier<GatewayResult> decide) {
        requests.add(op + ":" + key);
        GatewayResult remembered = resultsByKey.get(op + ":" + key);
        if (remembered != null) return remembered; // idempotent replay
        if (transientFailuresLeft.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            return GatewayResult.transientError("Provider timeout"); // not remembered: the retry is a fresh attempt
        }
        GatewayResult result = decide.get();
        resultsByKey.put(op + ":" + key, result);
        return result;
    }

    // ---- onboarding: dev stand-ins, clients may submit tokens directly ----

    @Override
    public boolean acceptsClientSuppliedTokens() { return true; }

    @Override
    public CardSetup beginCardSetup(UUID customerId, String existing) {
        return new CardSetup(existing != null ? existing : "cus_fake_" + customerId, "seti_fake_secret", "pk_fake");
    }

    @Override
    public PayoutOnboarding beginPayoutOnboarding(UUID riderId, String existing) {
        return new PayoutOnboarding(existing != null ? existing : "acct_fake_" + riderId,
                "https://example.test/fake-onboarding", Instant.now().plusSeconds(300));
    }

    // ---- test controls ----

    public void failNextCallsTransiently(int n) { transientFailuresLeft.set(n); }

    public void reset() {
        resultsByKey.clear();
        requests.clear();
        transientFailuresLeft.set(0);
    }

    /** Every request received, including replays: "charge:<key>", "payout:<key>", ... */
    public List<String> requests() { return List.copyOf(requests); }

    /** Distinct operations that actually went through (replays of the same key count once). */
    public long succeeded(String op) {
        return resultsByKey.entrySet().stream()
                .filter(e -> e.getKey().startsWith(op + ":") && e.getValue().succeeded()).count();
    }
}
