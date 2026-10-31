package com.ridehail.notify;

import com.ridehail.common.RideEvent;
import com.ridehail.dispatch.OfferStatus;
import com.ridehail.dispatch.TripOfferRepository;
import com.ridehail.rider.RiderRepository;
import com.ridehail.trip.Trip;
import com.ridehail.trip.TripRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns the moments a person must not miss into lock-screen notifications, after the transaction that caused them has committed.
 * Notifications carry as little as possible: a fare, a first name, a distance. No phone numbers, no addresses, no coordinates.
 * The "type" and "tripId" in the data are what the app uses to open the right screen when it is tapped.
 */
@Component
class RideNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(RideNotificationListener.class);

    private final NotificationService notifications;
    private final TripRepository trips;
    private final TripOfferRepository offers;
    private final RiderRepository riders;
    private final Clock clock;

    RideNotificationListener(NotificationService notifications, TripRepository trips, TripOfferRepository offers, RiderRepository riders, Clock clock) {
        this.notifications = notifications;
        this.trips = trips;
        this.offers = offers;
        this.riders = riders;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(RideEvent e) {
        try {
            switch (e.type()) {
                case OFFER_SENT -> offer(e);
                case TRIP_ACCEPTED -> accepted(e);
                case RIDER_ARRIVED -> notifications.notify(e.customerId(), message("Your rider has arrived", "They are at your pickup point.", "RIDER_ARRIVED", e.tripId(), 0));
                case TRIP_CANCELLED -> {
                    notifications.notify(e.customerId(), message("Ride cancelled", "This ride was cancelled.", "TRIP_CANCELLED", e.tripId(), 0));
                    notifications.notify(e.riderId(), message("Trip cancelled", "The customer cancelled this trip.", "TRIP_CANCELLED", e.tripId(), 0));
                }
                case NO_RIDERS_AVAILABLE -> notifications.notify(e.customerId(), message("No riders available", "Try again in a moment.", "NO_RIDERS_AVAILABLE", e.tripId(), 0));
                case PAYMENT_FAILED -> notifications.notify(e.customerId(), message("Payment failed", "Update your card to keep riding.", "PAYMENT_FAILED", e.tripId(), 0));
                case BANK_PAYOUT_FAILED -> notifications.notify(e.riderId(), message("Bank payout failed", "Update your bank details to withdraw again.", "BANK_PAYOUT_FAILED", e.tripId(), 0));
                default -> { /* everything else is visible in the app, and not worth waking a phone for */ }
            }
        } catch (RuntimeException ex) {
            // a notification problem must never touch the ride that already committed
            log.warn("Could not prepare a notification for {} on trip {}", e.type(), e.tripId(), ex);
        }
    }

    private void offer(RideEvent e) {
        var offer = offers.findById(e.offerId()).orElse(null);
        Trip trip = trips.findById(e.tripId()).orElse(null);
        if (offer == null || trip == null || offer.getStatus() != OfferStatus.PENDING) return;
        // an offer is a 15-second question: if the phone gets it later than that, it is worse than nothing
        long left = Math.max(1, Math.min(30, Duration.between(Instant.now(clock), offer.getExpiresAt()).toSeconds()));
        var m = message("New ride request", trip.getCurrency() + " " + trip.getFare().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                + " · " + distance(offer.getRiderDistanceKm()) + " to pickup", "OFFER_RECEIVED", e.tripId(), (int) left);
        notifications.notify(e.riderId(), withOffer(m, e.offerId()));
    }

    private void accepted(RideEvent e) {
        var rider = riders.findById(e.riderId()).orElse(null);
        String who = rider == null ? "Your rider" : rider.getName();
        String vehicle = rider == null || rider.getVehicleType() == null ? "" : " · " + titleCase(rider.getVehicleType().name());
        notifications.notify(e.customerId(), message("Your rider is on the way", who + vehicle, "TRIP_ACCEPTED", e.tripId(), 0));
    }

    private static PushNotifier.Message message(String title, String body, String type, UUID tripId, int ttl) {
        return new PushNotifier.Message(title, body, tripId == null ? Map.of("type", type) : Map.of("type", type, "tripId", tripId.toString()), ttl);
    }

    private static PushNotifier.Message withOffer(PushNotifier.Message m, UUID offerId) {
        var data = new java.util.HashMap<>(m.data());
        data.put("offerId", offerId.toString());
        return new PushNotifier.Message(m.title(), m.body(), Map.copyOf(data), m.ttlSeconds());
    }

    static String distance(double km) {
        return km < 1 ? Math.round(km * 1000) + " m" : String.format(java.util.Locale.ROOT, "%.1f km", km);
    }

    private static String titleCase(String s) {
        return s.charAt(0) + s.substring(1).toLowerCase(java.util.Locale.ROOT);
    }
}
