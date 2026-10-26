package com.ridehail.payment;

/**
 * Outcome of a call to the payment provider.
 * DECLINED is definitive (bad card, closed account): retrying the same request will not help.
 * TRANSIENT_ERROR means "unknown, try again": timeouts, 5xx, provider outage.
 * AWAITING_FUNDS is a definitive "not yet": the platform's own balance at the provider cannot cover a payout right now
 * (card payments settle after a delay). Nothing happened, and it is not a fault: wait and try again, without using up retries.
 */
public record GatewayResult(Outcome outcome, String reference, String message) {
    public enum Outcome { SUCCEEDED, DECLINED, TRANSIENT_ERROR, AWAITING_FUNDS }

    public static GatewayResult ok(String reference) { return new GatewayResult(Outcome.SUCCEEDED, reference, null); }
    public static GatewayResult declined(String message) { return new GatewayResult(Outcome.DECLINED, null, message); }
    public static GatewayResult transientError(String message) { return new GatewayResult(Outcome.TRANSIENT_ERROR, null, message); }
    public static GatewayResult awaitingFunds(String message) { return new GatewayResult(Outcome.AWAITING_FUNDS, null, message); }

    public boolean succeeded() { return outcome == Outcome.SUCCEEDED; }
}
