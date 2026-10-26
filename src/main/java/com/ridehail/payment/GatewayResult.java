package com.ridehail.payment;

/**
 * Outcome of a call to the payment provider.
 * DECLINED is definitive (bad card, closed account): retrying the same request will not help.
 * TRANSIENT_ERROR means "unknown, try again": timeouts, 5xx, provider outage.
 */
public record GatewayResult(Outcome outcome, String reference, String message) {
    public enum Outcome { SUCCEEDED, DECLINED, TRANSIENT_ERROR }

    public static GatewayResult ok(String reference) { return new GatewayResult(Outcome.SUCCEEDED, reference, null); }
    public static GatewayResult declined(String message) { return new GatewayResult(Outcome.DECLINED, null, message); }
    public static GatewayResult transientError(String message) { return new GatewayResult(Outcome.TRANSIENT_ERROR, null, message); }

    public boolean succeeded() { return outcome == Outcome.SUCCEEDED; }
}
