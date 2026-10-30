package com.ridehail.payment.stripe;

import com.ridehail.common.ApiExceptions.BadGatewayException;
import com.ridehail.payment.GatewayResult;
import com.ridehail.payment.OnboardingGateway;
import com.ridehail.payment.PaymentGateway;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.AuthenticationException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.PermissionException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import com.stripe.model.Balance;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.SetupIntentCreateParams;
import com.stripe.param.TransferCreateParams;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stripe implementation of the payment ports.
 *
 *  - Charges: off-session PaymentIntents against the customer's saved card (confirm=true).
 *  - Refunds: Refund against the PaymentIntent we stored as the charge reference.
 *  - Payouts: Connect Transfers from the platform balance to the rider's connected (Express) account;
 *    Stripe then pays the rider's bank on that account's schedule.
 *  - Every mutating call carries an Idempotency-Key derived from our own keys, so a retry after a timeout
 *    can never create a second charge/refund/transfer.
 *
 * Outcomes are mapped to the three results the domain understands: SUCCEEDED, DECLINED (do not retry),
 * TRANSIENT_ERROR (retry later). When in doubt it chooses TRANSIENT: retrying with the same key is safe,
 * while wrongly declining a payment that actually went through would lose money.
 */
@Component
@ConditionalOnProperty(name = "ridehail.payments.gateway", havingValue = "stripe")
public class StripeGateway implements PaymentGateway, OnboardingGateway {
    private static final Logger log = LoggerFactory.getLogger(StripeGateway.class);

    /** Card error codes that Stripe documents as "try again", as opposed to a real decline. */
    private static final Set<String> RETRYABLE_CARD_CODES = Set.of("processing_error", "issuer_not_available");

    static final String KEY_METADATA = "ridehail_key";

    private final StripeClient stripe;
    private final StripeProperties props;

    StripeGateway(StripeProperties props) {
        if (blank(props.apiKey()) || props.webhookSecrets().isEmpty()) {
            throw new IllegalStateException(
                    "ridehail.stripe.api-key and ridehail.stripe.webhook-secret are required when ridehail.payments.gateway=stripe");
        }
        var problems = props.problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Stripe is misconfigured: " + String.join("; ", problems));
        }
        this.props = props;
        log.info("Stripe gateway enabled in {} mode", props.mode().orElseThrow());
        var builder = StripeClient.builder()
                .setApiKey(props.apiKey())
                .setMaxNetworkRetries(props.maxNetworkRetries())
                .setConnectTimeout(5_000)
                .setReadTimeout(20_000);
        if (!blank(props.apiBase())) builder.setApiBase(props.apiBase());
        this.stripe = builder.build();
    }

    // ---------------------------------------------------------------- money movement

    @Override
    public GatewayResult charge(String key, String paymentMethodToken, BigDecimal amount, String currency) {
        String[] ref = StripeTokens.parsePaymentMethod(paymentMethodToken);
        if (ref == null) return GatewayResult.declined("No usable card on file");
        try {
            var params = PaymentIntentCreateParams.builder()
                    .setAmount(StripeMoney.toMinorUnits(amount, currency))
                    .setCurrency(currency.toLowerCase())
                    .setCustomer(ref[0])
                    .setPaymentMethod(ref[1])
                    .setConfirm(true)
                    .setOffSession(Boolean.TRUE)
                    .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                            .setEnabled(true)
                            .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                            .build())
                    .setDescription("Ride payment")
                    .putMetadata(KEY_METADATA, key) // lets the webhook find our payment again
                    .build();
            return fromIntent(stripe.paymentIntents().create(params, idempotent("charge-" + key)));
        } catch (StripeException e) {
            return map(e);
        }
    }

    private static GatewayResult fromIntent(PaymentIntent pi) {
        return switch (String.valueOf(pi.getStatus())) {
            case "succeeded" -> GatewayResult.ok(pi.getId());
            // money may still arrive (webhook payment_intent.succeeded will confirm it)
            case "processing" -> GatewayResult.transientError("Payment is still processing");
            case "requires_action", "requires_confirmation" -> GatewayResult.declined("Customer authentication required");
            case "requires_payment_method", "canceled" -> GatewayResult.declined("Payment method failed");
            default -> GatewayResult.transientError("Unexpected payment status " + pi.getStatus());
        };
    }

    @Override
    public GatewayResult refund(String key, String chargeReference, BigDecimal amount) {
        try {
            // the amount currency is the PaymentIntent's; recompute minor units from the original currency
            var intent = stripe.paymentIntents().retrieve(chargeReference);
            var params = RefundCreateParams.builder()
                    .setPaymentIntent(chargeReference)
                    .setAmount(StripeMoney.toMinorUnits(amount, intent.getCurrency()))
                    .putMetadata(KEY_METADATA, key)
                    .build();
            var refund = stripe.refunds().create(params, idempotent("refund-" + key));
            return switch (String.valueOf(refund.getStatus())) {
                case "succeeded", "pending" -> GatewayResult.ok(refund.getId());
                case "failed", "canceled" -> GatewayResult.declined("Refund " + refund.getStatus());
                default -> GatewayResult.transientError("Unexpected refund status " + refund.getStatus());
            };
        } catch (StripeException e) {
            if ("charge_already_refunded".equals(e.getCode())) return GatewayResult.ok("already_refunded");
            return map(e);
        }
    }

    @Override
    public GatewayResult payout(String key, String destinationToken, BigDecimal amount, String currency) {
        if (destinationToken == null || !destinationToken.startsWith("acct_")) {
            return GatewayResult.declined("No payout account");
        }
        try {
            var params = TransferCreateParams.builder()
                    .setAmount(StripeMoney.toMinorUnits(amount, currency))
                    .setCurrency(currency.toLowerCase())
                    .setDestination(destinationToken)
                    .putMetadata(KEY_METADATA, key)
                    .build();
            return GatewayResult.ok(stripe.transfers().create(params, idempotent("transfer-" + key)).getId());
        } catch (StripeException e) {
            return map(e);
        }
    }

    /**
     * Settled funds in the platform's Stripe balance. Card payments sit in "pending" for a few days first, and transfers
     * can only spend "available". If Stripe cannot be asked we answer "unknown" and the transfer itself decides.
     */
    @Override
    public Optional<BigDecimal> availableBalance(String currency) {
        try {
            long minor = stripe.balance().retrieve().getAvailable().stream()
                    .filter(a -> currency.equalsIgnoreCase(a.getCurrency()))
                    .mapToLong(Balance.Available::getAmount).sum();
            return Optional.of(StripeMoney.fromMinorUnits(minor, currency));
        } catch (StripeException e) {
            log.warn("Could not read the Stripe balance ({}); the transfer will decide", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- onboarding

    @Override
    public boolean acceptsClientSuppliedTokens() {
        return false;
    }

    @Override
    public CardSetup beginCardSetup(UUID customerId, String existingStripeCustomerId) {
        try {
            String stripeCustomer = existingStripeCustomerId;
            if (stripeCustomer == null) {
                stripeCustomer = stripe.customers().create(CustomerCreateParams.builder()
                        .putMetadata("ridehail_customer_id", customerId.toString())
                        .build(), idempotent("customer-" + customerId)).getId();
            }
            var intent = stripe.setupIntents().create(SetupIntentCreateParams.builder()
                    .setCustomer(stripeCustomer)
                    .setUsage(SetupIntentCreateParams.Usage.OFF_SESSION) // we charge it later, without the customer present
                    .setAutomaticPaymentMethods(SetupIntentCreateParams.AutomaticPaymentMethods.builder()
                            .setEnabled(true)
                            .setAllowRedirects(SetupIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                            .build())
                    .putMetadata("ridehail_customer_id", customerId.toString())
                    .build());
            return new CardSetup(stripeCustomer, intent.getClientSecret(), props.publishableKey());
        } catch (StripeException e) {
            log.warn("Stripe card setup failed for customer {}: {}", customerId, e.getMessage());
            throw new BadGatewayException("Could not start card setup, please try again", e);
        }
    }

    @Override
    public PayoutOnboarding beginPayoutOnboarding(UUID riderId, String existingAccountId) {
        try {
            String account = existingAccountId;
            if (account == null) {
                var create = AccountCreateParams.builder()
                        .setType(AccountCreateParams.Type.EXPRESS)
                        .setCapabilities(AccountCreateParams.Capabilities.builder()
                                .setTransfers(AccountCreateParams.Capabilities.Transfers.builder().setRequested(true).build())
                                .build())
                        .putMetadata("ridehail_rider_id", riderId.toString());
                if (!blank(props.connectCountry())) create.setCountry(props.connectCountry());
                account = stripe.accounts().create(create.build(), idempotent("acct-" + riderId)).getId();
            }
            var link = stripe.accountLinks().create(AccountLinkCreateParams.builder()
                    .setAccount(account)
                    .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                    .setReturnUrl(props.onboardingReturnUrl())
                    .setRefreshUrl(props.onboardingRefreshUrl())
                    .build());
            return new PayoutOnboarding(account, link.getUrl(), Instant.ofEpochSecond(link.getExpiresAt()));
        } catch (StripeException e) {
            log.warn("Stripe payout onboarding failed for rider {}: {}", riderId, e.getMessage());
            throw new BadGatewayException("Could not start payout onboarding, please try again", e);
        }
    }

    // ---------------------------------------------------------------- error mapping

    private GatewayResult map(StripeException e) {
        String code = e.getCode();
        String detail = code != null ? code : e.getClass().getSimpleName();

        // The platform balance has no available funds yet (new charges settle after a delay): wait, this is not an error.
        if ("balance_insufficient".equals(code)) return GatewayResult.awaitingFunds("Platform balance not yet available");

        if (e instanceof CardException card) {
            if (code != null && RETRYABLE_CARD_CODES.contains(code)) return GatewayResult.transientError(detail);
            String decline = card.getDeclineCode() != null ? card.getDeclineCode() : detail;
            return GatewayResult.declined("Card declined: " + decline);
        }
        if (e instanceof RateLimitException || e instanceof ApiConnectionException || e instanceof ApiException) {
            return GatewayResult.transientError(detail);
        }
        if (e instanceof AuthenticationException || e instanceof PermissionException) {
            // our credentials are wrong or too restricted: nothing the customer did, and a fixed key makes retries work
            log.error("Stripe rejected our API key ({}). Check STRIPE_API_KEY and its permissions.", e.getClass().getSimpleName());
            return GatewayResult.transientError("Payment provider authentication failed");
        }
        if (e instanceof IdempotencyException || e instanceof InvalidRequestException) {
            // a malformed or impossible request: retrying the same thing will not help
            return GatewayResult.declined("Rejected by provider: " + detail);
        }
        return GatewayResult.transientError(detail);
    }

    private static RequestOptions idempotent(String key) {
        return RequestOptions.builder().setIdempotencyKey(key).build();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
