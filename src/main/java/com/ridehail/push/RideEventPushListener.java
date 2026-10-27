package com.ridehail.push;

import com.ridehail.common.RideEvent;
import com.ridehail.dispatch.OfferStatus;
import com.ridehail.dispatch.TripOfferRepository;
import com.ridehail.payment.PaymentRepository;
import com.ridehail.rider.RiderRepository;
import com.ridehail.trip.Trip;
import com.ridehail.trip.TripRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns domain events into WebSocket pushes, only after the database transaction has committed,
 * so a client is never told about something that was rolled back.
 *   riders    <- /user/queue/offers : OFFER_RECEIVED, OFFER_EXPIRED, OFFER_WITHDRAWN
 *   customers <- /user/queue/trips  : TRIP_REQUESTED, TRIP_ACCEPTED, RIDER_ARRIVED, TRIP_STARTED,
 *                                     TRIP_COMPLETED, TRIP_CANCELLED, NO_RIDERS_AVAILABLE
 *   riders    <- /user/queue/trips  : TRIP_CANCELLED
 * REST stays the source of truth: a client that reconnects should re-fetch state, since pushes are best-effort.
 */
@Component
class RideEventPushListener {
    private static final Logger log = LoggerFactory.getLogger(RideEventPushListener.class);
    private static final String OFFERS = "/queue/offers";
    private static final String TRIPS = "/queue/trips";

    private final SimpMessagingTemplate ws;
    private final TripRepository trips;
    private final TripOfferRepository offers;
    private final RiderRepository riders;
    private final PaymentRepository payments;

    RideEventPushListener(SimpMessagingTemplate ws, TripRepository trips, TripOfferRepository offers,
                          RiderRepository riders, PaymentRepository payments) {
        this.ws = ws;
        this.trips = trips;
        this.offers = offers;
        this.riders = riders;
        this.payments = payments;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(RideEvent e) {
        try {
            switch (e.type()) {
                case OFFER_SENT -> pushOffer(e);
                case OFFER_EXPIRED -> toUser(e.riderId(), OFFERS, message("OFFER_EXPIRED", e.tripId()).with("offerId", e.offerId()));
                case TRIP_ACCEPTED -> pushAccepted(e);
                case TRIP_CANCELLED -> pushCancelled(e);
                case TRIP_REQUESTED, RIDER_ARRIVED, TRIP_STARTED, TRIP_COMPLETED, NO_RIDERS_AVAILABLE -> pushTripUpdate(e);
                case PAYMENT_CAPTURED -> pushPaymentCaptured(e);
                case PAYMENT_FAILED -> pushPaymentFailed(e);
                // money notices carry no amount or customer detail: the app refetches /api/riders/me/earnings
                case FUNDS_ON_HOLD, HOLD_ENDED, BANK_PAYOUT_FAILED -> toUser(e.riderId(), TRIPS, message(e.type().name(), e.tripId()));
                case OFFER_DECLINED -> { /* the declining rider already knows */ }
            }
        } catch (RuntimeException ex) {
            // a failed push must never affect the booking that already committed
            log.warn("Could not push {} for trip {}", e.type(), e.tripId(), ex);
        }
    }

    private void pushOffer(RideEvent e) {
        var offer = offers.findById(e.offerId()).orElseThrow();
        Trip t = trips.findById(e.tripId()).orElseThrow();
        toUser(e.riderId(), OFFERS, message("OFFER_RECEIVED", e.tripId())
                .with("offerId", offer.getId())
                .with("distanceToPickupKm", offer.getRiderDistanceKm())
                .with("expiresAt", offer.getExpiresAt().toString())
                .with("pickupLat", t.getPickupLat()).with("pickupLon", t.getPickupLon())
                .with("dropLat", t.getDropLat()).with("dropLon", t.getDropLon())
                .with("fare", t.getFare()).with("currency", t.getCurrency())
                .with("surgeMultiplier", t.getSurgeMultiplier()));
    }

    private void pushAccepted(RideEvent e) {
        Trip t = trips.findById(e.tripId()).orElseThrow();
        var msg = tripMessage("TRIP_ACCEPTED", t);
        riders.findById(e.riderId()).ifPresent(r -> msg.with("riderName", r.getName())
                .with("vehicleType", r.getVehicleType()).with("riderLat", r.getLat()).with("riderLon", r.getLon()));
        toUser(e.customerId(), TRIPS, msg);
    }

    private void pushCancelled(RideEvent e) {
        Trip t = trips.findById(e.tripId()).orElseThrow();
        toUser(e.customerId(), TRIPS, tripMessage("TRIP_CANCELLED", t));
        if (e.riderId() != null) toUser(e.riderId(), TRIPS, tripMessage("TRIP_CANCELLED", t));
        // riders whose pending offer was pulled back by the cancellation
        offers.findByTripIdAndStatus(e.tripId(), OfferStatus.CANCELLED)
                .forEach(o -> toUser(o.getRiderId(), OFFERS, message("OFFER_WITHDRAWN", e.tripId()).with("offerId", o.getId())));
    }

    /** Receipt for the customer, "earning credited" for the rider. */
    private void pushPaymentCaptured(RideEvent e) {
        var p = payments.findByTripId(e.tripId()).orElseThrow();
        toUser(e.customerId(), TRIPS, message("PAYMENT_CAPTURED", e.tripId())
                .with("amount", p.getAmount()).with("currency", p.getCurrency()));
        toUser(e.riderId(), TRIPS, message("EARNING_CREDITED", e.tripId())
                .with("amount", p.getRiderAmount()).with("currency", p.getCurrency()));
    }

    private void pushPaymentFailed(RideEvent e) {
        var p = payments.findByTripId(e.tripId()).orElseThrow();
        toUser(e.customerId(), TRIPS, message("PAYMENT_FAILED", e.tripId())
                .with("amount", p.getAmount()).with("currency", p.getCurrency()).with("reason", p.getFailureReason()));
    }

    private void pushTripUpdate(RideEvent e) {
        Trip t = trips.findById(e.tripId()).orElseThrow();
        toUser(e.customerId(), TRIPS, tripMessage(e.type().name(), t));
    }

    private Msg tripMessage(String type, Trip t) {
        return message(type, t.getId()).with("status", t.getStatus()).with("riderId", t.getRiderId())
                .with("fare", t.getFare()).with("currency", t.getCurrency());
    }

    private void toUser(UUID profileId, String destination, Msg msg) {
        if (profileId != null) ws.convertAndSendToUser(profileId.toString(), destination, msg.fields);
    }

    private static Msg message(String type, UUID tripId) {
        return new Msg().with("type", type).with("tripId", tripId);
    }

    /** Tiny builder; payloads are plain JSON objects so mobile clients need no shared types. */
    private static final class Msg {
        final Map<String, Object> fields = new LinkedHashMap<>();

        Msg with(String key, Object value) {
            if (value != null) fields.put(key, value instanceof Enum<?> en ? en.name() : value);
            return this;
        }
    }
}
