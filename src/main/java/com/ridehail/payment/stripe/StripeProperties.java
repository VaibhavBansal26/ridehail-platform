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
        String onboardingRefreshUrl,
        /** Where Stripe's hosted card page sends the customer after saving a card, and when they back out. */
        String cardReturnUrl,
        String cardCancelUrl) {

    /** Every signing secret we accept (account-scope endpoint, Connect-scope endpoint, Stripe CLI...). */
    public java.util.List<String> webhookSecrets() {
        if (webhookSecret == null) return java.util.List.of();
        return java.util.Arrays.stream(webhookSecret.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public enum Mode { TEST, LIVE }

    private static final java.util.regex.Pattern SECRET_KEY = java.util.regex.Pattern.compile("^(?:sk|rk)_(test|live)_.+");
    private static final java.util.regex.Pattern PUBLISHABLE_KEY = java.util.regex.Pattern.compile("^pk_(test|live)_.+");

    /** Whether the API key is a test or a live one; empty when it is not a Stripe secret key at all. */
    public java.util.Optional<Mode> mode() {
        return modeOf(SECRET_KEY, apiKey);
    }

    private static java.util.Optional<Mode> modeOf(java.util.regex.Pattern pattern, String key) {
        if (key == null) return java.util.Optional.empty();
        var m = pattern.matcher(key.trim());
        return m.matches() ? java.util.Optional.of(Mode.valueOf(m.group(1).toUpperCase())) : java.util.Optional.empty();
    }

    /**
     * Configuration mistakes that would otherwise only show up as failed payments in production. Messages name the
     * setting and what it should look like, never its value (these are secrets).
     */
    public java.util.List<String> problems() {
        var problems = new java.util.ArrayList<String>();
        var mode = mode();
        if (mode.isEmpty()) {
            problems.add("ridehail.stripe.api-key must be a Stripe secret or restricted key (sk_test_..., sk_live_..., rk_test_... or rk_live_...);"
                    + " a publishable key (pk_...) or something else was given");
        }
        if (publishableKey != null && !publishableKey.isBlank()) {
            var publishableMode = modeOf(PUBLISHABLE_KEY, publishableKey);
            if (publishableMode.isEmpty()) {
                problems.add("ridehail.stripe.publishable-key must be a Stripe publishable key (pk_test_... or pk_live_...)");
            } else if (mode.isPresent() && publishableMode.get() != mode.get()) {
                problems.add("ridehail.stripe.publishable-key is a " + publishableMode.get().name().toLowerCase()
                        + "-mode key but the api-key is " + mode.get().name().toLowerCase() + "-mode; card setup in the app would fail");
            }
        }
        for (String secret : webhookSecrets()) {
            if (!secret.startsWith("whsec_")) {
                problems.add("every entry of ridehail.stripe.webhook-secret must be a signing secret starting with whsec_"
                        + " (the endpoint's \"Signing secret\", not its id or an API key)");
                break;
            }
        }
        return problems;
    }
}
