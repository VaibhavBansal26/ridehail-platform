package com.ridehail.payment.stripe;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ridehail.stripe")
public record StripeProperties(
        String apiKey,
        /** One or more signing secrets, comma-separated: Stripe gives each webhook endpoint its own. */
        String webhookSecret,
        String publishableKey,
        String apiBase,
        int maxNetworkRetries,
        String connectCountry,
        String onboardingReturnUrl,
        String onboardingRefreshUrl) {

    /** Every signing secret we accept (account-scope endpoint, Connect-scope endpoint, Stripe CLI...). */
    public java.util.List<String> webhookSecrets() {
        if (webhookSecret == null) return java.util.List.of();
        return java.util.Arrays.stream(webhookSecret.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
