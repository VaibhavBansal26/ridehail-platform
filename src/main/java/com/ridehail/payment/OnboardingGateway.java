package com.ridehail.payment;

import java.time.Instant;
import java.util.UUID;

/**
 * Port for the parts of a provider integration that are not money movement: saving a customer's card
 * and onboarding a rider to receive payouts. Both are completed by the user in the provider's own UI/SDK
 * (so we never see card or bank details) and confirmed to us by webhook.
 */
public interface OnboardingGateway {

    /**
     * True only for the simulated gateway, where clients may submit tokens directly.
     * With a real provider this MUST be false: otherwise a client could register someone else's card or bank
     * account. Real tokens are written only by the verified webhook.
     */
    boolean acceptsClientSuppliedTokens();

    /** Starts saving a card. The client finishes it with {@code clientSecret} in the provider's mobile SDK. */
    CardSetup beginCardSetup(UUID customerId, String existingProviderCustomerId);

    /** Starts (or resumes) payout onboarding; the rider completes it at {@code url}. */
    PayoutOnboarding beginPayoutOnboarding(UUID riderId, String existingProviderAccountId);

    record CardSetup(String providerCustomerId, String clientSecret, String publishableKey) {}

    record PayoutOnboarding(String providerAccountId, String url, Instant expiresAt) {}
}
