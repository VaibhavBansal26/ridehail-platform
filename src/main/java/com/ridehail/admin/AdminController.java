package com.ridehail.admin;

import com.ridehail.common.VehicleType;
import com.ridehail.rider.Rider;
import com.ridehail.rider.RiderRepository;
import com.ridehail.rider.RiderStatus;
import com.ridehail.trip.Trip;
import com.ridehail.trip.TripRepository;
import com.ridehail.trip.TripService;
import com.ridehail.trip.TripStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

/** Operations view for the admin panel. Role-gated to ADMIN in SecurityConfig. */
@RestController
@RequestMapping("/api/admin")
class AdminController {

    private static final int MAX_PAGE_SIZE = 100;

    private final TripRepository trips;
    private final RiderRepository riders;
    private final TripService tripService;

    AdminController(TripRepository trips, RiderRepository riders, TripService tripService) {
        this.trips = trips;
        this.riders = riders;
        this.tripService = tripService;
    }

    record PageView<T>(List<T> items, long total, int page, int size) {
        static <S, T> PageView<T> of(Page<S> p, Function<S, T> map) {
            return new PageView<>(p.getContent().stream().map(map).toList(), p.getTotalElements(), p.getNumber(), p.getSize());
        }
    }

    record TripView(UUID id, TripStatus status, UUID customerId, UUID riderId, VehicleType vehicleType,
                    BigDecimal fare, BigDecimal surgeMultiplier, String currency, int offersSent) {
        static TripView of(Trip t) {
            return new TripView(t.getId(), t.getStatus(), t.getCustomerId(), t.getRiderId(), t.getVehicleType(),
                    t.getFare(), t.getSurgeMultiplier(), t.getCurrency(), t.getOffersSent());
        }
    }

    record RiderView(UUID id, String name, VehicleType vehicleType, RiderStatus status, Double lat, Double lon) {
        static RiderView of(Rider r) {
            return new RiderView(r.getId(), r.getName(), r.getVehicleType(), r.getStatus(), r.getLat(), r.getLon());
        }
    }

    @GetMapping("/trips")
    PageView<TripView> trips(@RequestParam(required = false) TripStatus status,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size) {
        var paging = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "requestedAt"));
        Page<Trip> result = status == null ? trips.findAll(paging) : trips.findByStatus(status, paging);
        return PageView.of(result, TripView::of);
    }

    @GetMapping("/riders")
    PageView<RiderView> riders(@RequestParam(required = false) RiderStatus status,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        var paging = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), Sort.by("name"));
        Page<Rider> result = status == null ? riders.findAll(paging) : riders.findByStatus(status, paging);
        return PageView.of(result, RiderView::of);
    }

    @PostMapping("/trips/{id}/cancel")
    TripView cancel(@PathVariable UUID id) {
        return TripView.of(tripService.adminCancel(id));
    }
}
