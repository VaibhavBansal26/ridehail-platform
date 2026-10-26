package com.ridehail.dispatch;

import com.ridehail.common.ApiExceptions.ConflictException;
import com.ridehail.common.ApiExceptions.ForbiddenException;
import com.ridehail.common.ApiExceptions.NotFoundException;
import com.ridehail.common.RideEvent;
import com.ridehail.common.RideEvent.Type;
import com.ridehail.rider.RiderRepository;
import com.ridehail.trip.TripRepository;
import com.ridehail.trip.TripStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OfferService {

    private final TripOfferRepository offers;
    private final TripRepository trips;
    private final RiderRepository riders;
    private final DispatchService dispatch;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    OfferService(TripOfferRepository offers, TripRepository trips, RiderRepository riders,
                 DispatchService dispatch, ApplicationEventPublisher events, Clock clock) {
        this.offers = offers;
        this.trips = trips;
        this.riders = riders;
        this.dispatch = dispatch;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TripOffer> pendingFor(UUID riderId) {
        Instant now = Instant.now(clock);
        return offers.findByRiderIdAndStatusOrderByCreatedAtAsc(riderId, OfferStatus.PENDING).stream()
                .filter(o -> !o.isExpiredAt(now))
                .toList();
    }

    /**
     * The rider accepts the customer's trip. Atomically: offer PENDING -> ACCEPTED, trip REQUESTED -> ACCEPTED,
     * rider AVAILABLE -> ON_TRIP. Version columns turn any concurrent double-claim into a 409.
     */
    @Transactional
    public void accept(UUID offerId, UUID riderId) {
        Instant now = Instant.now(clock);
        TripOffer offer = ownedOffer(offerId, riderId);
        if (offer.getStatus() != OfferStatus.PENDING) throw new ConflictException("Offer is already " + offer.getStatus());
        if (offer.isExpiredAt(now)) throw new ConflictException("Offer expired");

        var trip = trips.findById(offer.getTripId()).orElseThrow(() -> new NotFoundException("Trip", offer.getTripId()));
        if (trip.getStatus() != TripStatus.REQUESTED) throw new ConflictException("Trip is no longer open: " + trip.getStatus());

        var rider = riders.findById(riderId).orElseThrow(() -> new NotFoundException("Rider", riderId));
        rider.claimForTrip();
        trip.accept(riderId, now);
        offer.resolve(OfferStatus.ACCEPTED, now);
        cancelOtherPending(trip.getId(), offerId, now);

        events.publishEvent(new RideEvent(Type.TRIP_ACCEPTED, trip.getId(), trip.getCustomerId(), riderId, offerId));
    }

    @Transactional
    public void decline(UUID offerId, UUID riderId) {
        Instant now = Instant.now(clock);
        TripOffer offer = ownedOffer(offerId, riderId);
        offer.resolve(OfferStatus.DECLINED, now);
        var trip = trips.findById(offer.getTripId()).orElseThrow();
        events.publishEvent(new RideEvent(Type.OFFER_DECLINED, trip.getId(), trip.getCustomerId(), riderId, offerId));
        dispatch.offerToNextRider(offer.getTripId());
    }

    /** Called by the scheduler, one transaction per offer so one failure cannot block the rest. */
    @Transactional
    public void expire(UUID offerId) {
        Instant now = Instant.now(clock);
        TripOffer offer = offers.findById(offerId).orElseThrow();
        if (offer.getStatus() != OfferStatus.PENDING || !offer.isExpiredAt(now)) return;
        offer.resolve(OfferStatus.EXPIRED, now);
        var trip = trips.findById(offer.getTripId()).orElseThrow();
        events.publishEvent(new RideEvent(Type.OFFER_EXPIRED, trip.getId(), trip.getCustomerId(),
                offer.getRiderId(), offerId));
        dispatch.offerToNextRider(offer.getTripId());
    }

    private TripOffer ownedOffer(UUID offerId, UUID riderId) {
        TripOffer offer = offers.findById(offerId).orElseThrow(() -> new NotFoundException("Offer", offerId));
        if (!offer.getRiderId().equals(riderId)) throw new ForbiddenException("Offer belongs to another rider");
        return offer;
    }

    private void cancelOtherPending(UUID tripId, UUID keepOfferId, Instant now) {
        for (TripOffer o : offers.findByTripIdAndStatus(tripId, OfferStatus.PENDING)) {
            if (!o.getId().equals(keepOfferId)) o.resolve(OfferStatus.CANCELLED, now);
        }
    }
}
