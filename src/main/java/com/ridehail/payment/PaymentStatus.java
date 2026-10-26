package com.ridehail.payment;

public enum PaymentStatus {
    /** Waiting for a (re)try of the charge. */
    PENDING,
    CAPTURED,
    /** Declined or retries exhausted: a person must act (customer fixes card, admin retries). */
    REQUIRES_ATTENTION,
    REFUNDED
}
