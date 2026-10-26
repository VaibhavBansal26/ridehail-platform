package com.ridehail.payment;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Port to the payment provider (Stripe, Razorpay, Adyen...). Contract for implementations:
 *  - every method takes an idempotency key and MUST return the original result when called again with
 *    the same key (all major providers support this), so retries can never double-charge or double-pay;
 *  - never log or persist raw card data: callers only ever hold opaque tokens.
 */
public interface PaymentGateway {
    GatewayResult charge(String idempotencyKey, String paymentMethodToken, BigDecimal amount, String currency);

    GatewayResult refund(String idempotencyKey, String chargeReference, BigDecimal amount);

    GatewayResult payout(String idempotencyKey, String destinationToken, BigDecimal amount, String currency);

    /**
     * Money the platform can send out right now (settled, not merely pending), or empty if the provider cannot say.
     * Advisory: it lets payouts wait cheaply instead of being attempted and refused. The provider's own check on
     * {@link #payout} remains the final word.
     */
    default Optional<BigDecimal> availableBalance(String currency) {
        return Optional.empty();
    }
}
