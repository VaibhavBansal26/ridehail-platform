package com.ridehail.dispute;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What a payment provider told us about a dispute, in provider-neutral terms. The latest notice always carries the
 * dispute's full current state, so processing is driven by {@code state} and never by which event type arrived.
 *
 * @param providerId        the provider's dispute id
 * @param paymentReference  the provider's id for the charge that was disputed (matches {@code Payment.gatewayReference})
 * @param amount            the amount in dispute, in major units (may be less than the payment)
 * @param feeTotal          net dispute fees the provider has charged us so far (0 if none or unknown)
 * @param evidenceDueBy     deadline to answer the dispute, if any
 * @param providerUrl       where an admin can respond to it
 */
public record DisputeNotice(
        String providerId,
        String paymentReference,
        BigDecimal amount,
        String currency,
        State state,
        String providerStatus,
        String reason,
        Instant evidenceDueBy,
        BigDecimal feeTotal,
        String providerUrl) {

    public enum State { INQUIRY, OPEN, WON, LOST, CLOSED }
}
