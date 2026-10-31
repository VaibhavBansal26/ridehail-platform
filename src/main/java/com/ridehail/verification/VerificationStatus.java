package com.ridehail.verification;

public enum VerificationStatus {
    /** Registered, nothing (or not everything) sent yet. */
    NOT_SUBMITTED,
    /** Sent, waiting for an admin. */
    PENDING_REVIEW,
    /** May drive. */
    APPROVED,
    /** An admin sent it back with a reason; the rider fixes it and submits again. */
    REJECTED,
    /** An admin withdrew permission to drive. Only an admin can lift it. */
    SUSPENDED,
    /** A licence, registration or insurance ran out. The rider uploads current ones and submits again. */
    EXPIRED
}
