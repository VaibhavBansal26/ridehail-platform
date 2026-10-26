package com.ridehail.dispatch;

import com.ridehail.security.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * The rider gets offers pushed over WebSocket (/user/queue/offers); this lists whatever is still pending
 * (e.g. after reconnecting) and is where the rider accepts or declines.
 */
@RestController
@RequestMapping("/api/riders/me/offers")
class OfferController {

    private final OfferService offers;

    OfferController(OfferService offers) {
        this.offers = offers;
    }

    record OfferResponse(UUID offerId, UUID tripId, double distanceToPickupKm, Instant expiresAt) {}

    @GetMapping
    List<OfferResponse> pending(@AuthenticationPrincipal Jwt jwt) {
        return offers.pendingFor(CurrentUser.profileId(jwt)).stream()
                .map(o -> new OfferResponse(o.getId(), o.getTripId(), o.getRiderDistanceKm(), o.getExpiresAt()))
                .toList();
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
