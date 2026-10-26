package com.ridehail.payment;

public enum PayoutStatus {
    /** Funds reserved in the ledger; waiting for the provider to confirm. */
    PENDING,
    PAID,
    /** Provider rejected it; funds were returned to the rider's balance. */
    FAILED,
    /** Outcome unknown after retries; funds stay reserved until ops confirms with the provider. */
    REQUIRES_ATTENTION
}
