package com.ridehail.dispatch;

import com.ridehail.security.CurrentUser;
import com.ridehail.trip.Trip;
import com.ridehail.trip.TripRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * The rider gets offers pushed over WebSocket (/user/topic/offers); this lists whatever is still pending
 * (e.g. after reconnecting) and is where the rider accepts or declines.
 */
@RestController
@RequestMapping("/api/riders/me/offers")
class OfferController {

    private final OfferService offers;
    private final TripRepository trips;

    OfferController(OfferService offers, TripRepository trips) {
        this.offers = offers;
        this.trips = trips;
    }

    /** Everything the rider needs to decide, the same as the OFFER_RECEIVED push, so an app that was closed can show it too. */
    record OfferResponse(UUID offerId, UUID tripId, double distanceToPickupKm, Instant expiresAt,
                         BigDecimal fare, String currency, BigDecimal surgeMultiplier,
                         double pickupLat, double pickupLon, double dropLat, double dropLon) {}

    @GetMapping
    List<OfferResponse> pending(@AuthenticationPrincipal Jwt jwt) {
        var pending = offers.pendingFor(CurrentUser.profileId(jwt));
        Map<UUID, Trip> byId = trips.findAllById(pending.stream().map(o -> o.getTripId()).toList()).stream()
                .collect(Collectors.toMap(Trip::getId, Function.identity()));
        return pending.stream().filter(o -> byId.containsKey(o.getTripId())).map(o -> {
            Trip t = byId.get(o.getTripId());
            return new OfferResponse(o.getId(), o.getTripId(), o.getRiderDistanceKm(), o.getExpiresAt(),
                    t.getFare(), t.getCurrency(), t.getSurgeMultiplier(),
                    t.getPickupLat(), t.getPickupLon(), t.getDropLat(), t.getDropLon());
        }).toList();
    }

    @PostMapping("/{offerId}/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID offerId) {
        offers.accept(offerId, CurrentUser.profileId(jwt));
    }

    @PostMapping("/{offerId}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void decline(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID offerId) {
        offers.decline(offerId, CurrentUser.profileId(jwt));
    }
}
