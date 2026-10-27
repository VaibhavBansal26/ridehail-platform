package com.ridehail.dispute;

public enum IncidentType {
    /** The payment provider could not pay a rider's bank account. The rider must fix their bank details. */
    CONNECT_PAYOUT_FAILED,
    /** The provider could not pay the platform's own bank account. */
    PLATFORM_PAYOUT_FAILED,
    /** A transfer we sent to a rider's connected account was reversed: our ledger says paid, the money came back. */
    TRANSFER_REVERSED,
    /** A refund to a customer failed: our ledger says refunded, the money did not leave. */
    REFUND_FAILED
}
