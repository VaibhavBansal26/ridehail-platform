package com.ridehail.dispatch;

import com.ridehail.common.GeoUtils;
import com.ridehail.common.RideEvent;
import com.ridehail.common.RideEvent.Type;
import com.ridehail.rider.Rider;
import com.ridehail.rider.RiderRepository;
import com.ridehail.rider.RiderStatus;
import com.ridehail.trip.Trip;
import com.ridehail.trip.TripRepository;
import com.ridehail.trip.TripStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sequential dispatch: offer the trip to the nearest eligible rider, one at a time.
 * Decline or timeout moves on to the next rider until the offer budget is spent.
 */
@Service
public class DispatchService {

    private final DispatchProperties props;
    private final TripRepository trips;
    private final RiderRepository riders;
    private final TripOfferRepository offers;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    DispatchService(DispatchProperties props, TripRepository trips, RiderRepository riders,
                    TripOfferRepository offers, ApplicationEventPublisher events, Clock clock) {
        this.props = props;
        this.trips = trips;
        this.riders = riders;
        this.offers = offers;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void offerToNextRider(UUID tripId) {
        Trip trip = trips.findById(tripId).orElseThrow();
        if (trip.getStatus() != TripStatus.REQUESTED) return;

        if (trip.getOffersSent() >= props.maxOffersPerTrip()) {
            giveUp(trip);
            return;
        }

        var excluded = new HashSet<UUID>(offers.riderIdsOfferedTrip(tripId));
        excluded.addAll(offers.riderIdsWithPendingOffers()); // a rider sees one offer at a time

        var box = GeoUtils.boundingBox(trip.getPickupLat(), trip.getPickupLon(), props.searchRadiusKm());
        var candidate = riders.findInBox(RiderStatus.AVAILABLE, trip.getVehicleType(),
                        box.minLat(), box.maxLat(), box.minLon(), box.maxLon()).stream()
                .filter(r -> !excluded.contains(r.getId()))
                .map(r -> new Candidate(r, distanceToPickup(r, trip)))
                .filter(c -> c.km() <= props.searchRadiusKm())
                .min(Comparator.comparingDouble(Candidate::km)
                        .thenComparing(c -> -c.rider().getRating()));

        if (candidate.isEmpty()) {
            giveUp(trip);
            return;
        }

        Instant now = Instant.now(clock);
        var offer = offers.save(new TripOffer(tripId, candidate.get().rider().getId(), candidate.get().km(),
                now, now.plus(Duration.ofSeconds(props.offerTtlSeconds()))));
        trip.offerSent();
        events.publishEvent(new RideEvent(Type.OFFER_SENT, tripId, trip.getCustomerId(),
                offer.getRiderId(), offer.getId()));
    }

    private void giveUp(Trip trip) {
        trip.noRidersAvailable();
        events.publishEvent(new RideEvent(Type.NO_RIDERS_AVAILABLE, trip.getId(), trip.getCustomerId(), null, null));
    }

    private static double distanceToPickup(Rider r, Trip t) {
        return GeoUtils.haversineKm(r.getLat(), r.getLon(), t.getPickupLat(), t.getPickupLon());
    }

    private record Candidate(Rider rider, double km) {}
}
