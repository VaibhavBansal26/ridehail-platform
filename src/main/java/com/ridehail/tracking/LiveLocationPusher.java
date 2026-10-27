package com.ridehail.tracking;

import com.ridehail.common.RiderLocationUpdated;
import com.ridehail.common.RateLimiter;
import com.ridehail.ratelimit.RateLimiterFactory;
import com.ridehail.trip.Trip;
import com.ridehail.trip.TripRepository;
import com.ridehail.trip.TripStatus;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends a rider's position to the customer whose trip the rider is currently serving, on /user/topic/location.
 *
 * Who may see a rider's position is decided here, on the server, never by the client:
 *  - the recipient is looked up from the database as the customer of the rider's ACTIVE trip (accepted, arrived or
 *    in progress). No active trip means nothing is sent, so a position is never shared before pickup is agreed or
 *    after the trip ends, and no customer can ask for a rider who is not theirs;
 *  - it is deliberately not cached: another instance may have ended the trip, and a stale answer would keep
 *    broadcasting a rider's position after the ride.
 * Pushes are throttled per trip so the customer's app is not flooded; only the freshest position matters.
 */
@Component
class LiveLocationPusher {
    private static final Logger log = LoggerFactory.getLogger(LiveLocationPusher.class);
    static final String DESTINATION = com.ridehail.push.Destinations.LOCATION;
    private static final EnumSet<TripStatus> SHARING = EnumSet.of(TripStatus.ACCEPTED, TripStatus.ARRIVED, TripStatus.IN_PROGRESS);

    private final SimpMessagingTemplate ws;
    private final TripRepository trips;
    private final RateLimiter perTrip;

    LiveLocationPusher(SimpMessagingTemplate ws, TripRepository trips, TrackingProperties props, RateLimiterFactory limiters) {
        this.ws = ws;
        this.trips = trips;
        // one position per interval per trip, no bursting: bucket of 1 that refills once per interval
        // shared across instances too, or two servers handling one rider's updates would each push once per interval
        this.perTrip = limiters.create("location-push", 1000.0 / Math.max(1, props.pushMinIntervalMs()), 1);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(RiderLocationUpdated e) {
        try {
            Trip trip = trips.findFirstByRiderIdAndStatusIn(e.riderId(), SHARING).orElse(null);
            if (trip == null || !perTrip.tryAcquire(trip.getId().toString()).allowed()) return;

            var msg = new LinkedHashMap<String, Object>();
            msg.put("type", "RIDER_LOCATION");
            msg.put("tripId", trip.getId());
            msg.put("status", trip.getStatus().name());
            msg.put("lat", e.lat());
            msg.put("lon", e.lon());
            if (e.heading() != null) msg.put("heading", e.heading());
            msg.put("at", e.at().toString());
            ws.convertAndSendToUser(trip.getCustomerId().toString(), DESTINATION, msg);
        } catch (RuntimeException ex) {
            // location is best-effort and must never affect the rider's own update
            log.warn("Could not push location for rider {}", e.riderId(), ex);
        }
    }
}
