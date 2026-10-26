package com.ridehail.payment;

public enum PayoutStatus {
    /** Funds reserved in the ledger; being sent to the provider (or retrying after a provider error). */
    PENDING,
    /**
     * Funds reserved in the ledger, but the platform's balance at the provider has not settled enough to send them yet.
     * A normal, patient state (card payments clear in days); it is retried on a schedule and when the provider reports
     * new available funds, without using up error retries.
     */
    AWAITING_FUNDS,
    PAID,
    /** Rejected, or the provider never had funds within the waiting limit; the money was returned to the rider's balance. */
    FAILED,
    /** Outcome unknown after errors; funds stay reserved until ops confirms with the provider. */
    REQUIRES_ATTENTION
}
