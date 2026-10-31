package com.ridehail.rider;

import com.ridehail.common.ApiExceptions.NotFoundException;
import com.ridehail.common.RiderLocationUpdated;
import com.ridehail.common.VehicleType;
import com.ridehail.tracking.LocationRateLimiter;
import com.ridehail.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** The signed-in rider's own availability and position ("/me"). Registration lives in /api/auth. */
@RestController
@RequestMapping("/api/riders/me")
class RiderController {

    private final RiderRepository riders;
    private final Clock clock;
    private final LocationRateLimiter rateLimiter;
    private final ApplicationEventPublisher events;

    RiderController(RiderRepository riders, Clock clock, LocationRateLimiter rateLimiter, ApplicationEventPublisher events) {
        this.riders = riders;
        this.clock = clock;
        this.rateLimiter = rateLimiter;
        this.events = events;
    }

    record LocationRequest(@DecimalMin("-90") @DecimalMax("90") double lat,
                           @DecimalMin("-180") @DecimalMax("180") double lon,
                           /** Direction of travel in degrees, optional; passed on to the customer's map, not stored. */
                           @DecimalMin("0") @DecimalMax("360") Double heading) {}
    record RiderResponse(UUID id, String name, VehicleType vehicleType, RiderStatus status, Double lat, Double lon) {
        static RiderResponse of(Rider r) {
            return new RiderResponse(r.getId(), r.getName(), r.getVehicleType(), r.getStatus(), r.getLat(), r.getLon());
        }
    }

    @GetMapping
    RiderResponse me(@AuthenticationPrincipal Jwt jwt) {
        return RiderResponse.of(find(jwt));
    }

    @PostMapping("/online")
    @Transactional
    RiderResponse online(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LocationRequest l) {
        var rider = find(jwt);
        if (!rider.isVerified()) {
            throw new com.ridehail.common.ApiExceptions.ForbiddenException(
                    "Your account is not verified yet. Open Verification to send your documents or see where they stand.");
        }
        rider.goOnline(l.lat(), l.lon(), Instant.now(clock));
        return RiderResponse.of(rider);
    }

    @PostMapping("/offline")
    @Transactional
    RiderResponse offline(@AuthenticationPrincipal Jwt jwt) {
        var rider = find(jwt);
        rider.goOffline();
        return RiderResponse.of(rider);
    }

    /**
     * Called by the rider app every few seconds while online. Rate limited per rider (429 + Retry-After); while the
     * rider is serving a trip, the position is also pushed to that trip's customer over the WebSocket.
     */
    @PutMapping("/location")
    @Transactional
    RiderResponse location(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LocationRequest l) {
        rateLimiter.check(CurrentUser.profileId(jwt)); // refuse before any database work
        var rider = find(jwt);
        Instant now = Instant.now(clock);
        rider.updateLocation(l.lat(), l.lon(), now);
        events.publishEvent(new RiderLocationUpdated(rider.getId(), l.lat(), l.lon(), l.heading(), now));
        return RiderResponse.of(rider);
    }

    private Rider find(Jwt jwt) {
        UUID id = CurrentUser.profileId(jwt);
        return riders.findById(id).orElseThrow(() -> new NotFoundException("Rider", id));
    }
}
