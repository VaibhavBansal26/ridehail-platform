package com.ridehail.rider;

import com.ridehail.common.ApiExceptions.NotFoundException;
import com.ridehail.common.VehicleType;
import com.ridehail.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
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

    RiderController(RiderRepository riders, Clock clock) {
        this.riders = riders;
        this.clock = clock;
    }

    record LocationRequest(@DecimalMin("-90") @DecimalMax("90") double lat,
                           @DecimalMin("-180") @DecimalMax("180") double lon) {}
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

    /** Called by the rider app every few seconds while online. */
    @PutMapping("/location")
    @Transactional
    RiderResponse location(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LocationRequest l) {
        var rider = find(jwt);
        rider.updateLocation(l.lat(), l.lon(), Instant.now(clock));
        return RiderResponse.of(rider);
    }

    private Rider find(Jwt jwt) {
        UUID id = CurrentUser.profileId(jwt);
        return riders.findById(id).orElseThrow(() -> new NotFoundException("Rider", id));
    }
}
