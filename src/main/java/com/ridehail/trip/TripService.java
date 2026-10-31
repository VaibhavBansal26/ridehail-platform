package com.ridehail.trip;

import com.ridehail.common.ApiExceptions.ConflictException;
import com.ridehail.common.ApiExceptions.ForbiddenException;
import com.ridehail.common.ApiExceptions.NotFoundException;
import com.ridehail.common.RideEvent;
import com.ridehail.common.RideEvent.Type;
import com.ridehail.dispatch.DispatchService;
import com.ridehail.dispatch.OfferStatus;
import com.ridehail.dispatch.TripOfferRepository;
import com.ridehail.payment.PaymentService;
import com.ridehail.pricing.FareQuoteRepository;
import com.ridehail.rider.RiderRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TripService {

    private final TripRepository trips;
    private final FareQuoteRepository quotes;
    private final RiderRepository riders;
    private final TripOfferRepository offers;
    private final DispatchService dispatch;
    private final PaymentService paymentService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    TripService(TripRepository trips, FareQuoteRepository quotes, RiderRepository riders,
                TripOfferRepository offers, DispatchService dispatch, PaymentService paymentService,
                ApplicationEventPublisher events, Clock clock) {
        this.trips = trips;
        this.quotes = quotes;
        this.riders = riders;
        this.offers = offers;
        this.dispatch = dispatch;
        this.paymentService = paymentService;
        this.events = events;
        this.clock = clock;
    }

    /** Books a trip against a quote. Idempotent per quote, so client retries return the same trip. */
    @Transactional
    public Trip request(UUID customerId, UUID quoteId) {
        var existing = trips.findByQuoteId(quoteId);
        if (existing.isPresent()) {
            if (!existing.get().getCustomerId().equals(customerId)) throw new ForbiddenException("Quote belongs to another customer");
            return existing.get();
        }

        var quote = quotes.findById(quoteId).orElseThrow(() -> new NotFoundException("Quote", quoteId));
        if (!quote.getCustomerId().equals(customerId)) throw new ForbiddenException("Quote belongs to another customer");
        Instant now = Instant.now(clock);
        if (!now.isBefore(quote.getExpiresAt())) throw new ConflictException("Quote expired, request a new fare");
        if (trips.existsByCustomerIdAndStatusIn(customerId, activeStatuses())) {
            throw new ConflictException("Customer already has an active trip");
        }

        paymentService.assertCustomerCanBook(customerId);

        quote.setUsed(true);
        Trip trip = trips.save(Trip.fromQuote(quote, now));
        events.publishEvent(new RideEvent(Type.TRIP_REQUESTED, trip.getId(), customerId, null, null));
        dispatch.offerToNextRider(trip.getId());
        return trip;
    }

    /** Visible to the trip's customer, its assigned rider, and admins. */
    @Transactional(readOnly = true)
    public Trip getFor(UUID tripId, UUID profileId, boolean admin) {
        Trip t = find(tripId);
        boolean party = profileId.equals(t.getCustomerId()) || profileId.equals(t.getRiderId());
        if (!admin && !party) throw new ForbiddenException("Not your trip");
        return t;
    }

    /**
     * The trip this customer or rider is in right now, so an app that was closed (or reloaded) can pick up where it was.
     * Empty when there is none; admins have none.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Trip> activeFor(UUID profileId, com.ridehail.security.Role role) {
        return switch (role) {
            case CUSTOMER -> trips.findFirstByCustomerIdAndStatusIn(profileId, activeStatuses());
            case RIDER -> trips.findFirstByRiderIdAndStatusIn(profileId, activeStatuses());
            default -> java.util.Optional.empty();
        };
    }

    public record RiderPosition(UUID tripId, TripStatus status, double lat, double lon, Instant at) {}

    /**
     * The assigned rider's last reported position, for a customer who has just opened the app or reconnected. Same
     * rules as the live feed: only the trip's parties (and admins), and only while the trip is active, so a position
     * is never available before pickup is agreed or after the trip ends.
     */
    @Transactional(readOnly = true)
    public RiderPosition riderPosition(UUID tripId, UUID profileId, boolean admin) {
        Trip t = getFor(tripId, profileId, admin);
        if (!EnumSet.of(TripStatus.ACCEPTED, TripStatus.ARRIVED, TripStatus.IN_PROGRESS).contains(t.getStatus())) {
            throw new ConflictException("Rider location is only available while the trip is active");
        }
        var rider = riders.findById(t.getRiderId()).orElseThrow(() -> new NotFoundException("Rider", t.getRiderId()));
        if (rider.getLat() == null || rider.getLon() == null) throw new NotFoundException("Rider location", t.getRiderId());
        return new RiderPosition(t.getId(), t.getStatus(), rider.getLat(), rider.getLon(), rider.getLocationUpdatedAt());
    }

    @Transactional
    public Trip riderArrived(UUID tripId, UUID riderId) {
        Trip t = ownedByRider(tripId, riderId);
        t.arrive();
        publish(Type.RIDER_ARRIVED, t);
        return t;
    }

    @Transactional
    public Trip start(UUID tripId, UUID riderId) {
        Trip t = ownedByRider(tripId, riderId);
        t.start(Instant.now(clock));
        publish(Type.TRIP_STARTED, t);
        return t;
    }

    @Transactional
    public Trip complete(UUID tripId, UUID riderId) {
        Trip t = ownedByRider(tripId, riderId);
        t.complete(Instant.now(clock));
        riders.findById(riderId).orElseThrow().release();
        paymentService.createForTrip(t); // same transaction: a completed trip always has its payment record
        publish(Type.TRIP_COMPLETED, t);
        return t;
    }

    @Transactional
    public Trip cancel(UUID tripId, UUID customerId) {
        Trip t = find(tripId);
        if (!t.getCustomerId().equals(customerId)) throw new ForbiddenException("Not your trip");
        return doCancel(t);
    }

    @Transactional
    public Trip adminCancel(UUID tripId) {
        return doCancel(find(tripId));
    }

    private Trip doCancel(Trip t) {
        UUID tripId = t.getId();
        t.cancel();
        Instant now = Instant.now(clock);
        offers.findByTripIdAndStatus(tripId, OfferStatus.PENDING).forEach(o -> o.resolve(OfferStatus.CANCELLED, now));
        if (t.getRiderId() != null) riders.findById(t.getRiderId()).orElseThrow().release();
        publish(Type.TRIP_CANCELLED, t);
        return t;
    }

    private Trip ownedByRider(UUID tripId, UUID riderId) {
        Trip t = find(tripId);
        if (!riderId.equals(t.getRiderId())) throw new ForbiddenException("Trip is not assigned to this rider");
        return t;
    }

    private Trip find(UUID id) {
        return trips.findById(id).orElseThrow(() -> new NotFoundException("Trip", id));
    }

    private void publish(Type type, Trip t) {
        events.publishEvent(new RideEvent(type, t.getId(), t.getCustomerId(), t.getRiderId(), null));
    }

    private static EnumSet<TripStatus> activeStatuses() {
        return EnumSet.of(TripStatus.REQUESTED, TripStatus.ACCEPTED, TripStatus.ARRIVED, TripStatus.IN_PROGRESS);
    }
}
