package com.ridehail.payment;

public enum PaymentStatus {
    /** Waiting for a (re)try of the charge. */
    PENDING,
    CAPTURED,
    /** The customer's bank has disputed the charge and the provider has pulled the money back; cannot be refunded. */
    DISPUTED,
    /** A dispute was lost: the money is gone. */
    CHARGED_BACK,
    /** Declined or retries exhausted: a person must act (customer fixes card, admin retries). */
    REQUIRES_ATTENTION,
    REFUNDED
}
