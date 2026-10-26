package com.ridehail.pricing;

import com.ridehail.common.VehicleType;
import com.ridehail.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/fares")
class FareController {

    private final FareService fares;

    FareController(FareService fares) {
        this.fares = fares;
    }

    record QuoteRequest(
            @NotNull VehicleType vehicleType,
            @DecimalMin("-90") @DecimalMax("90") double pickupLat,
            @DecimalMin("-180") @DecimalMax("180") double pickupLon,
            @DecimalMin("-90") @DecimalMax("90") double dropLat,
            @DecimalMin("-180") @DecimalMax("180") double dropLon) {}

    record QuoteResponse(UUID quoteId, VehicleType vehicleType, double distanceKm, double durationMin,
                         BigDecimal subtotal, BigDecimal surgeMultiplier, BigDecimal total,
                         String currency, boolean surgeActive, Instant expiresAt) {
        static QuoteResponse of(FareQuote q) {
            return new QuoteResponse(q.getId(), q.getVehicleType(), q.getDistanceKm(), q.getDurationMin(),
                    q.getSubtotal(), q.getSurgeMultiplier(), q.getTotal(), q.getCurrency(),
                    q.getSurgeMultiplier().compareTo(BigDecimal.ONE) > 0, q.getExpiresAt());
        }
    }

    @PostMapping("/quote")
    @ResponseStatus(HttpStatus.CREATED)
    QuoteResponse quote(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody QuoteRequest r) {
        return QuoteResponse.of(fares.quote(CurrentUser.profileId(jwt), r.vehicleType(),
                r.pickupLat(), r.pickupLon(), r.dropLat(), r.dropLon()));
    }
}
