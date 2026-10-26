package com.ridehail.payment.stripe;

/**
 * Format of the opaque "payment method token" we store for a customer when Stripe is the provider:
 * {@code cus_xxx|pm_xxx}. An off-session charge needs both the Stripe customer and the saved card, and the
 * domain only keeps one string per customer. Only the verified webhook ever writes these.
 */
public final class StripeTokens {
    private StripeTokens() {}

    public static String paymentMethod(String stripeCustomerId, String stripePaymentMethodId) {
        return stripeCustomerId + "|" + stripePaymentMethodId;
    }

    /** @return {customerId, paymentMethodId}, or null if the token is not in our format */
    static String[] parsePaymentMethod(String token) {
        if (token == null) return null;
        String[] parts = token.split("\\|", -1);
        if (parts.length != 2 || !parts[0].startsWith("cus_") || !parts[1].startsWith("pm_")) return null;
        return parts;
    }
}
