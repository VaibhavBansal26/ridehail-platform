package com.ridehail.common;

import java.util.UUID;

/** Published inside transactions, handled after commit (observer pattern; swap for Kafka via an outbox later). */
public record RideEvent(Type type, UUID tripId, UUID customerId, UUID riderId, UUID offerId) {
    public enum Type {
        TRIP_REQUESTED, OFFER_SENT, OFFER_DECLINED, OFFER_EXPIRED,
        TRIP_ACCEPTED, RIDER_ARRIVED, TRIP_STARTED, TRIP_COMPLETED, TRIP_CANCELLED, NO_RIDERS_AVAILABLE,
        PAYMENT_CAPTURED, PAYMENT_FAILED
    }
}
