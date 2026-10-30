package com.ridehail.payment.stripe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehail.customer.CustomerRepository;
import com.ridehail.dispute.IncidentService;
import com.ridehail.dispute.IncidentType;
import com.ridehail.dispute.DisputeService;
import com.ridehail.payment.Payment;
import com.ridehail.payment.PaymentRepository;
import com.ridehail.payment.PaymentService;
import com.ridehail.payment.Payout;
import com.ridehail.payment.PayoutRepository;
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
 *   charge.dispute.*           a customer disputed a charge         -> record it; on a chargeback freeze the rider's share (DisputeService)
 *   payout.failed              Stripe could not pay a rider's bank  -> incident; pause that rider's payouts until they fix it
 *                              (or the platform's own bank, if the event is not for a connected account)
 *   account.external_account.* a rider changed their bank details   -> resolve their failed-payout incident
 *   transfer.reversed          a transfer to a rider was reversed   -> incident for a person (our books say it was paid)
 *   refund.failed              a customer refund failed             -> incident for a person (our books say it was refunded)
 */
@Service
@ConditionalOnProperty(name = "ridehail.payments.gateway", havingValue = "stripe")
public class StripeWebhookService {
    private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);
    /** Every dispute event carries the dispute's full current state, so one handler serves them all. */
    private static final Set<String> DISPUTE_EVENTS = Set.of("charge.dispute.created", "charge.dispute.updated",
            "charge.dispute.closed", "charge.dispute.funds_withdrawn", "charge.dispute.funds_reinstated");

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
    private final DisputeService disputes;
    private final IncidentService incidents;
    private final PaymentRepository paymentRepo;
    private final PayoutRepository payoutRepo;
    private final TaskScheduler scheduler;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    StripeWebhookService(StripeProperties props, ProcessedWebhookEventRepository processed, CustomerRepository customers,
                         RiderRepository riders, PaymentService payments, PayoutService payouts,
                         DisputeService disputes, IncidentService incidents, PaymentRepository paymentRepo, PayoutRepository payoutRepo,
                         @Qualifier("taskScheduler") TaskScheduler scheduler, TransactionTemplate tx, ObjectMapper json, Clock clock) {
        this.props = props;
        this.processed = processed;
        this.customers = customers;
        this.riders = riders;
        this.payments = payments;
        this.payouts = payouts;
        this.disputes = disputes;
        this.incidents = incidents;
        this.paymentRepo = paymentRepo;
        this.payoutRepo = payoutRepo;
        this.scheduler = scheduler;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    /** @throws InvalidWebhookException if the signature or payload is bad (respond 400, Stripe will not retry) */
    public Outcome handle(String payload, String signatureHeader) {
        String eventId, type;
        JsonNode object, root;
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
            root = json.readTree(payload);
            object = root.path("data").path("object");
        } catch (InvalidWebhookException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidWebhookException("Malformed webhook", e);
        }

        // A correctly signed event from the other mode (a test endpoint's secret left in a live deployment, or the reverse)
        // must never touch real books. Answer 200 so Stripe does not keep retrying, and shout in the log.
        boolean eventIsLive = root.path("livemode").asBoolean(false);
        if (props.mode().filter(m -> (m == StripeProperties.Mode.LIVE) != eventIsLive).isPresent()) {
            log.error("Ignoring {} event {}: it is a {}-mode event but the API key is {}-mode. Check STRIPE_WEBHOOK_SECRET.",
                    type, eventId, eventIsLive ? "live" : "test", eventIsLive ? "test" : "live");
            return Outcome.IGNORED;
        }

        if (processed.existsById(eventId)) return Outcome.DUPLICATE;

        boolean known = switch (type) {
            case "setup_intent.succeeded" -> { onCardSaved(object); yield true; }
            case "account.updated" -> { onAccountUpdated(object); yield true; }
            case "payment_intent.succeeded" -> { onPaymentSucceeded(object); yield true; }
            case "balance.available" -> { onFundsAvailable(); yield true; }
            case "payout.failed" -> { onPayoutFailed(root, object); yield true; }
            case "transfer.reversed" -> { onTransferReversed(object); yield true; }
            case "refund.failed" -> { onRefundFailed(object); yield true; }
            case "account.external_account.created", "account.external_account.updated" -> { onBankDetailsChanged(root, object); yield true; }
            default -> {
                if (!DISPUTE_EVENTS.contains(type)) yield false;
                disputes.apply(StripeDisputeMapper.toNotice(object, root.path("livemode").asBoolean(false)));
                yield true;
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

    /** Stripe could not pay out to a bank account: a rider's (connected account) or the platform's own. */
    private void onPayoutFailed(JsonNode root, JsonNode payout) {
        String account = root.path("account").asText(null);
        String currency = payout.path("currency").asText("usd");
        String detail = "Bank payout " + payout.path("id").asText() + " failed: "
                + payout.path("failure_code").asText("unknown") + " - " + payout.path("failure_message").asText("");
        var amount = StripeMoney.fromMinorUnits(payout.path("amount").asLong(), currency);
        if (account == null) {
            incidents.record(IncidentType.PLATFORM_PAYOUT_FAILED, payout.path("id").asText(), null, null, null, amount, currency.toUpperCase(), detail);
            return;
        }
        var rider = riders.findByProviderAccountId(account);
        incidents.record(IncidentType.CONNECT_PAYOUT_FAILED, payout.path("id").asText(), rider.map(r -> r.getId()).orElse(null),
                null, null, amount, currency.toUpperCase(),
                rider.isPresent() ? detail : detail + " (connected account " + account + " matches no rider)");
    }

    /** A transfer we sent to a rider was reversed: our ledger says they were paid, the money came back. A person must decide. */
    private void onTransferReversed(JsonNode transfer) {
        String ref = transfer.path("id").asText();
        String currency = transfer.path("currency").asText("usd");
        Payout payout = payoutRepo.findByGatewayReference(ref).orElse(null);
        var rider = payout != null ? null : riders.findByProviderAccountId(transfer.path("destination").asText(""));
        incidents.record(IncidentType.TRANSFER_REVERSED, ref,
                payout != null ? payout.getRiderId() : (rider != null && rider.isPresent() ? rider.get().getId() : null),
                null, payout != null ? payout.getId() : null,
                StripeMoney.fromMinorUnits(transfer.path("amount_reversed").asLong(transfer.path("amount").asLong()), currency),
                currency.toUpperCase(), "Transfer " + ref + " was reversed" + (payout == null ? " (no payout of ours matches it)" : ""));
    }

    /** A refund to a customer failed: our ledger says they were refunded, the money did not leave. A person must decide. */
    private void onRefundFailed(JsonNode refund) {
        String ref = refund.path("id").asText();
        String currency = refund.path("currency").asText("usd");
        Payment payment = paymentRepo.findByGatewayReference(refund.path("payment_intent").asText("")).orElse(null);
        incidents.record(IncidentType.REFUND_FAILED, ref, payment != null ? payment.getRiderId() : null,
                payment != null ? payment.getId() : null, null,
                StripeMoney.fromMinorUnits(refund.path("amount").asLong(), currency), currency.toUpperCase(),
                "Refund " + ref + " failed: " + refund.path("failure_reason").asText("unknown")
                        + (payment == null ? " (no payment of ours matches it)" : ""));
    }

    /** The rider changed their bank details, which is what Stripe needs before it will pay that account again. */
    private void onBankDetailsChanged(JsonNode root, JsonNode externalAccount) {
        String account = root.path("account").asText(externalAccount.path("account").asText(null));
        if (account == null) return;
        riders.findByProviderAccountId(account).ifPresent(r -> incidents.resolveBankIssues(r.getId(), "Bank details were updated"));
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
