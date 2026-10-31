package com.ridehail.trip;

import com.ridehail.common.VehicleType;
import com.ridehail.security.CurrentUser;
import com.ridehail.security.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Who is acting always comes from the verified JWT, never from the request. */
@RestController
@RequestMapping("/api/trips")
class TripController {

    private final TripService trips;

    TripController(TripService trips) {
        this.trips = trips;
    }

    record BookRequest(@NotNull UUID quoteId) {}

    record TripResponse(UUID id, TripStatus status, UUID customerId, UUID riderId, VehicleType vehicleType,
                        BigDecimal fare, BigDecimal surgeMultiplier, String currency,
                        double pickupLat, double pickupLon, double dropLat, double dropLon) {
        static TripResponse of(Trip t) {
            return new TripResponse(t.getId(), t.getStatus(), t.getCustomerId(), t.getRiderId(),
                    t.getVehicleType(), t.getFare(), t.getSurgeMultiplier(), t.getCurrency(),
                    t.getPickupLat(), t.getPickupLon(), t.getDropLat(), t.getDropLon());
        }
    }

    /** Customer books the quoted fare; the nearest rider is offered the trip. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TripResponse book(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody BookRequest r) {
        return TripResponse.of(trips.request(CurrentUser.profileId(jwt), r.quoteId()));
    }

    /** The caller's current trip (customer or rider), or 204 when there is none. */
    @GetMapping("/active")
    ResponseEntity<TripResponse> active(@AuthenticationPrincipal Jwt jwt) {
        return trips.activeFor(CurrentUser.profileId(jwt), CurrentUser.role(jwt))
                .map(t -> ResponseEntity.ok(TripResponse.of(t)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{id}")
    TripResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return TripResponse.of(trips.getFor(id, CurrentUser.profileId(jwt), CurrentUser.role(jwt) == Role.ADMIN));
    }

    /** Last known position of the trip's rider; the live feed (WebSocket /user/topic/location) keeps it fresh after this. */
    @GetMapping("/{id}/rider-location")
    TripService.RiderPosition riderLocation(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return trips.riderPosition(id, CurrentUser.profileId(jwt), CurrentUser.role(jwt) == Role.ADMIN);
    }

    @PostMapping("/{id}/cancel")
    TripResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return TripResponse.of(trips.cancel(id, CurrentUser.profileId(jwt)));
    }

    @PostMapping("/{id}/arrive")
    TripResponse arrive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return TripResponse.of(trips.riderArrived(id, CurrentUser.profileId(jwt)));
    }

    @PostMapping("/{id}/start")
    TripResponse start(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return TripResponse.of(trips.start(id, CurrentUser.profileId(jwt)));
    }

    @PostMapping("/{id}/complete")
    TripResponse complete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return TripResponse.of(trips.complete(id, CurrentUser.profileId(jwt)));
    }
}
