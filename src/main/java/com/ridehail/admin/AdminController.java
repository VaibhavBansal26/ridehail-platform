package com.ridehail.admin;

import com.ridehail.common.ApiExceptions.NotFoundException;
import com.ridehail.common.VehicleType;
import com.ridehail.customer.Customer;
import com.ridehail.customer.CustomerRepository;
import com.ridehail.dispatch.OfferStatus;
import com.ridehail.dispatch.TripOffer;
import com.ridehail.dispatch.TripOfferRepository;
import com.ridehail.dispute.Dispute;
import com.ridehail.dispute.DisputeRepository;
import com.ridehail.dispute.DisputeStatus;
import com.ridehail.payment.Payment;
import com.ridehail.payment.PaymentRepository;
import com.ridehail.payment.PaymentStatus;
import com.ridehail.rider.Rider;
import com.ridehail.rider.RiderRepository;
import com.ridehail.rider.RiderStatus;
import com.ridehail.trip.Trip;
import com.ridehail.trip.TripRepository;
import com.ridehail.trip.TripService;
import com.ridehail.trip.TripStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Operations view for the admin panel. Role-gated to ADMIN in SecurityConfig. */
@RestController
@RequestMapping("/api/admin")
class AdminController {

    private static final int MAX_PAGE_SIZE = 100;
    /** The live map shows everything on the road, up to this many of each kind; beyond it the response says it was cut. */
    static final int LIVE_LIMIT = 5000;
    private static final Set<TripStatus> ACTIVE = EnumSet.of(TripStatus.REQUESTED, TripStatus.ACCEPTED, TripStatus.ARRIVED,
            TripStatus.IN_PROGRESS);
    private static final Set<RiderStatus> ONLINE = EnumSet.of(RiderStatus.AVAILABLE, RiderStatus.ON_TRIP);

    private final TripRepository trips;
    private final RiderRepository riders;
    private final CustomerRepository customers;
    private final PaymentRepository payments;
    private final DisputeRepository disputes;
    private final TripOfferRepository offers;
    private final TripService tripService;
    private final Clock clock;
    private final AdminAudit audit;

    AdminController(TripRepository trips, RiderRepository riders, CustomerRepository customers, PaymentRepository payments,
                    DisputeRepository disputes, TripOfferRepository offers, TripService tripService, Clock clock, AdminAudit audit) {
        this.audit = audit;
        this.trips = trips;
        this.riders = riders;
        this.customers = customers;
        this.payments = payments;
        this.disputes = disputes;
        this.offers = offers;
        this.tripService = tripService;
        this.clock = clock;
    }

    record PageView<T>(List<T> items, long total, int page, int size) {
        static <S, T> PageView<T> of(Page<S> p, Function<S, T> map) {
            return new PageView<>(p.getContent().stream().map(map).toList(), p.getTotalElements(), p.getNumber(), p.getSize());
        }
    }

    /** Names for the ids on a page of trips, fetched in two queries rather than two per row. */
    private record Names(Map<UUID, String> customers, Map<UUID, String> riders) {
        String customer(UUID id) { return id == null ? null : customers.get(id); }
        String rider(UUID id) { return id == null ? null : riders.get(id); }
    }

    private Names namesFor(Collection<Trip> list) {
        Set<UUID> customerIds = list.stream().map(Trip::getCustomerId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> riderIds = list.stream().map(Trip::getRiderId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> c = new HashMap<>();
        customers.findAllById(customerIds).forEach(x -> c.put(x.getId(), x.getName()));
        Map<UUID, String> r = new HashMap<>();
        riders.findAllById(riderIds).forEach(x -> r.put(x.getId(), x.getName()));
        return new Names(c, r);
    }

    record TripView(UUID id, TripStatus status, UUID customerId, String customerName, UUID riderId, String riderName,
                    VehicleType vehicleType, BigDecimal fare, BigDecimal surgeMultiplier, String currency, int offersSent,
                    double pickupLat, double pickupLon, double dropLat, double dropLon,
                    Instant requestedAt, Instant acceptedAt, Instant startedAt, Instant completedAt) {
        static TripView of(Trip t, Names n) {
            return new TripView(t.getId(), t.getStatus(), t.getCustomerId(), n.customer(t.getCustomerId()), t.getRiderId(),
                    n.rider(t.getRiderId()), t.getVehicleType(), t.getFare(), t.getSurgeMultiplier(), t.getCurrency(),
                    t.getOffersSent(), t.getPickupLat(), t.getPickupLon(), t.getDropLat(), t.getDropLon(),
                    t.getRequestedAt(), t.getAcceptedAt(), t.getStartedAt(), t.getCompletedAt());
        }
    }

    record RiderView(UUID id, String name, VehicleType vehicleType, RiderStatus status, Double lat, Double lon,
                     double rating, Instant locationUpdatedAt) {
        static RiderView of(Rider r) {
            return new RiderView(r.getId(), r.getName(), r.getVehicleType(), r.getStatus(), r.getLat(), r.getLon(),
                    r.getRating(), r.getLocationUpdatedAt());
        }
    }

    record OfferView(UUID id, UUID riderId, String riderName, OfferStatus status, double riderDistanceKm,
                     Instant createdAt, Instant expiresAt, Instant respondedAt) {}

    record PaymentSummary(UUID id, PaymentStatus status, java.math.BigDecimal amount, java.math.BigDecimal commission,
                          java.math.BigDecimal riderAmount, String currency, int attempts, String failureReason) {
        static PaymentSummary of(Payment p) {
            return new PaymentSummary(p.getId(), p.getStatus(), p.getAmount(), p.getCommissionAmount(), p.getRiderAmount(),
                    p.getCurrency(), p.getAttempts(), p.getFailureReason());
        }
    }

    record DisputeSummary(UUID id, DisputeStatus status, BigDecimal amount, String currency, String reason,
                          Instant evidenceDueBy) {
        static DisputeSummary of(Dispute d) {
            return new DisputeSummary(d.getId(), d.getStatus(), d.getAmount(), d.getCurrency(), d.getReason(), d.getEvidenceDueBy());
        }
    }

    record TripDetail(TripView trip, PaymentSummary payment, List<OfferView> offers, List<DisputeSummary> disputes) {}

    /** A rider or an active trip as the map needs it: where, what, and nothing it would have to look up. */
    record MapRider(UUID id, String name, VehicleType vehicleType, RiderStatus status, double lat, double lon,
                    Instant locationUpdatedAt) {}

    record MapTrip(UUID id, TripStatus status, VehicleType vehicleType, String customerName, String riderName, UUID riderId,
                   double pickupLat, double pickupLon, double dropLat, double dropLon, Instant requestedAt) {}

    record LiveSnapshot(Instant generatedAt, List<MapRider> riders, List<MapTrip> trips,
                        Map<RiderStatus, Long> riderCounts, Map<TripStatus, Long> tripCounts, boolean truncated) {}

    @GetMapping("/trips")
    PageView<TripView> trips(@RequestParam(required = false) TripStatus status,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size) {
        var paging = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "requestedAt"));
        Page<Trip> result = status == null ? trips.findAll(paging) : trips.findByStatus(status, paging);
        Names names = namesFor(result.getContent());
        return PageView.of(result, t -> TripView.of(t, names));
    }

    @GetMapping("/trips/{id}")
    TripDetail trip(@PathVariable UUID id) {
        Trip trip = trips.findById(id).orElseThrow(() -> new NotFoundException("trip", id));
        Names names = namesFor(List.of(trip));
        List<TripOffer> sent = offers.findByTripIdOrderByCreatedAtAsc(id);
        Map<UUID, String> offered = new HashMap<>();
        riders.findAllById(sent.stream().map(TripOffer::getRiderId).collect(Collectors.toSet()))
                .forEach(r -> offered.put(r.getId(), r.getName()));
        return new TripDetail(TripView.of(trip, names),
                payments.findByTripId(id).map(PaymentSummary::of).orElse(null),
                sent.stream().map(o -> new OfferView(o.getId(), o.getRiderId(), offered.get(o.getRiderId()), o.getStatus(),
                        o.getRiderDistanceKm(), o.getCreatedAt(), o.getExpiresAt(), o.getRespondedAt())).toList(),
                disputes.findByTripId(id).stream().map(DisputeSummary::of).toList());
    }

    @GetMapping("/riders")
    PageView<RiderView> riders(@RequestParam(required = false) RiderStatus status,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        var paging = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), Sort.by("name"));
        Page<Rider> result = status == null ? riders.findAll(paging) : riders.findByStatus(status, paging);
        return PageView.of(result, RiderView::of);
    }

    /** Everything the live map draws, in one request: online riders with a known position and trips in progress. */
    @GetMapping("/ops/live")
    LiveSnapshot live() {
        var paging = PageRequest.of(0, LIVE_LIMIT + 1, Sort.by("id"));
        List<Rider> online = riders.findByStatusIn(ONLINE, paging);
        List<Trip> active = trips.findByStatusIn(ACTIVE, paging);
        boolean truncated = online.size() > LIVE_LIMIT || active.size() > LIVE_LIMIT;
        if (online.size() > LIVE_LIMIT) online = online.subList(0, LIVE_LIMIT);
        if (active.size() > LIVE_LIMIT) active = active.subList(0, LIVE_LIMIT);
        Names names = namesFor(active);

        Map<RiderStatus, Long> riderCounts = new java.util.EnumMap<>(RiderStatus.class);
        for (RiderStatus s : RiderStatus.values()) riderCounts.put(s, riders.countByStatus(s));
        Map<TripStatus, Long> tripCounts = new java.util.EnumMap<>(TripStatus.class);
        for (TripStatus s : ACTIVE) tripCounts.put(s, trips.countByStatus(s));

        return new LiveSnapshot(clock.instant(),
                online.stream().filter(r -> r.getLat() != null && r.getLon() != null)
                        .map(r -> new MapRider(r.getId(), r.getName(), r.getVehicleType(), r.getStatus(), r.getLat(), r.getLon(),
                                r.getLocationUpdatedAt())).toList(),
                active.stream().map(t -> new MapTrip(t.getId(), t.getStatus(), t.getVehicleType(), names.customer(t.getCustomerId()),
                        names.rider(t.getRiderId()), t.getRiderId(), t.getPickupLat(), t.getPickupLon(), t.getDropLat(),
                        t.getDropLon(), t.getRequestedAt())).toList(),
                riderCounts, tripCounts, truncated);
    }

    @PostMapping("/trips/{id}/cancel")
    TripView cancel(@AuthenticationPrincipal Jwt jwt,
                    @PathVariable UUID id) {
        Trip cancelled = tripService.adminCancel(id);
        audit.record(jwt, "TRIP_CANCELLED", "TRIP", id, "Cancelled trip");
        return TripView.of(cancelled, namesFor(List.of(cancelled)));
    }
}
