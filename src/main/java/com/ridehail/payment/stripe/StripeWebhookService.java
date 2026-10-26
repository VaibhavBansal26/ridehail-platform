package com.ridehail.payment.stripe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehail.customer.CustomerRepository;
import com.ridehail.payment.PaymentService;
import com.ridehail.payment.PayoutService;
import com.ridehail.rider.RiderRepository;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles Stripe webhooks. The signature is the only authentication: the payload is rejected unless it was signed
 * with our webhook secret and is recent (Stripe's replay tolerance). Handlers are idempotent, and processed event
 * ids are recorded, because Stripe redelivers until it gets a 2xx. A handler that throws makes the endpoint
 * return 500 so Stripe retries later; nothing is marked processed in that case.
 *
 * What each event does:
 *   setup_intent.succeeded     the customer saved a card            -> store the card as their payment method
 *   account.updated            rider onboarding progressed          -> payouts enabled/disabled on their account
 *   payment_intent.succeeded   a charge completed (maybe late)      -> confirm our payment (idempotent)
 *   balance.available          settled funds arrived at the platform -> retry payouts that were waiting for funds
 *   charge.dispute.created, refund.failed, payout.failed, transfer.reversed
 *                              money problems we do not automate    -> logged at ERROR for a human to act on
 */
@Service
@ConditionalOnProperty(name = "ridehail.payments.gateway", havingValue = "stripe")
public class StripeWebhookService {
    private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);
    private static final Set<String> NEEDS_A_HUMAN = Set.of(
            "charge.dispute.created", "refund.failed", "payout.failed", "transfer.reversed");

    public static class InvalidWebhookException extends RuntimeException {
        InvalidWebhookException(String message, Throwable cause) { super(message, cause); }
    }

    public enum Outcome { HANDLED, DUPLICATE, IGNORED }

    private final StripeProperties props;
    private final ProcessedWebhookEventRepository processed;
    private final CustomerRepository customers;
    private final RiderRepository riders;
    private final PaymentService payments;
    private final PayoutService payouts;
    private final TaskScheduler scheduler;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    StripeWebhookService(StripeProperties props, ProcessedWebhookEventRepository processed, CustomerRepository customers,
                         RiderRepository riders, PaymentService payments, PayoutService payouts,
                         @Qualifier("taskScheduler") TaskScheduler scheduler, TransactionTemplate tx, ObjectMapper json, Clock clock) {
        this.props = props;
        this.processed = processed;
        this.customers = customers;
        this.riders = riders;
        this.payments = payments;
        this.payouts = payouts;
        this.scheduler = scheduler;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    /** @throws InvalidWebhookException if the signature or payload is bad (respond 400, Stripe will not retry) */
    public Outcome handle(String payload, String signatureHeader) {
        String eventId, type;
        JsonNode object;
        try {
            // Stripe signs events for "your account" and for "connected accounts" with different endpoint secrets
            Event verified = null;
            SignatureVerificationException lastFailure = null;
            for (String secret : props.webhookSecrets()) {
                try {
                    verified = Webhook.constructEvent(payload, signatureHeader, secret);
                    break;
                } catch (SignatureVerificationException e) {
                    lastFailure = e;
                }
            }
            if (verified == null) throw new InvalidWebhookException("Invalid signature", lastFailure);
            eventId = verified.getId();
            type = verified.getType();
            object = json.readTree(payload).path("data").path("object");
        } catch (InvalidWebhookException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidWebhookException("Malformed webhook", e);
        }

        if (processed.existsById(eventId)) return Outcome.DUPLICATE;

        boolean known = switch (type) {
            case "setup_intent.succeeded" -> { onCardSaved(object); yield true; }
            case "account.updated" -> { onAccountUpdated(object); yield true; }
            case "payment_intent.succeeded" -> { onPaymentSucceeded(object); yield true; }
            case "balance.available" -> { onFundsAvailable(); yield true; }
            default -> {
                if (NEEDS_A_HUMAN.contains(type)) {
                    log.error("Stripe event {} ({}) needs manual follow-up: {}", type, eventId, object.path("id").asText());
                    yield true;
                }
                yield false;
            }
        };

        try {
            processed.saveAndFlush(new ProcessedWebhookEvent(eventId, type, Instant.now(clock)));
        } catch (DataIntegrityViolationException concurrentDelivery) {
            return Outcome.DUPLICATE;
        }
        return known ? Outcome.HANDLED : Outcome.IGNORED;
    }

    private void onCardSaved(JsonNode intent) {
        String stripeCustomer = intent.path("customer").asText(null);
        String paymentMethod = intent.path("payment_method").asText(null);
        if (stripeCustomer == null || paymentMethod == null) return;
        tx.executeWithoutResult(s -> customers.findByProviderCustomerId(stripeCustomer).ifPresentOrElse(
                c -> c.setPaymentMethodToken(StripeTokens.paymentMethod(stripeCustomer, paymentMethod)),
                () -> log.warn("setup_intent.succeeded for unknown Stripe customer {}", stripeCustomer)));
    }

    private void onAccountUpdated(JsonNode account) {
        String accountId = account.path("id").asText(null);
        boolean payoutsEnabled = account.path("payouts_enabled").asBoolean(false);
        if (accountId == null) return;
        tx.executeWithoutResult(s -> riders.findByProviderAccountId(accountId).ifPresentOrElse(
                // a rider can only be paid once Stripe says the account can receive funds
                r -> r.setPayoutAccountToken(payoutsEnabled ? accountId : null),
                () -> log.warn("account.updated for unknown connected account {}", accountId)));
    }

    /**
     * Retrying payouts calls Stripe once per waiting payout, so do it in the background and answer the webhook now.
     * If this is lost (crash), the scheduled poll picks the payouts up anyway.
     */
    private void onFundsAvailable() {
        scheduler.schedule(() -> {
            try {
                payouts.onFundsAvailable();
            } catch (RuntimeException e) {
                log.warn("Retrying payouts after balance.available failed; the scheduled poll will cover it", e);
            }
        }, Instant.now());
    }

    private void onPaymentSucceeded(JsonNode intent) {
        String key = intent.path("metadata").path(StripeGateway.KEY_METADATA).asText(null);
        if (key == null) return; // not one of ours
        try {
            payments.confirmCaptured(UUID.fromString(key), intent.path("id").asText());
        } catch (IllegalArgumentException notOurKey) {
            log.warn("payment_intent.succeeded with unrecognised {}: {}", StripeGateway.KEY_METADATA, key);
        }
    }
}
