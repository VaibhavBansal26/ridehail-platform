package com.ridehail.dispatch;

import com.ridehail.common.ApiExceptions.ConflictException;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A time-boxed request sent to one rider; the rider accepts or declines before it expires. */
@Entity
@Table(name = "trip_offers", indexes = {
        @Index(name = "idx_offer_rider", columnList = "riderId,status"),
        @Index(name = "idx_offer_trip", columnList = "tripId,status"),
        @Index(name = "idx_offer_expiry", columnList = "status,expiresAt")})
@Getter @NoArgsConstructor
public class TripOffer {
    @Id private UUID id = UUID.randomUUID();
    /** Optimistic lock: expiry job and rider response cannot both win. */
    @Version private Long version;

    private UUID tripId;
    private UUID riderId;
    @Enumerated(EnumType.STRING) private OfferStatus status = OfferStatus.PENDING;
    private double riderDistanceKm;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant respondedAt;

    public TripOffer(UUID tripId, UUID riderId, double riderDistanceKm, Instant now, Instant expiresAt) {
        this.tripId = tripId;
        this.riderId = riderId;
        this.riderDistanceKm = riderDistanceKm;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    public boolean isExpiredAt(Instant now) { return !now.isBefore(expiresAt); }

    public void resolve(OfferStatus target, Instant now) {
        if (status != OfferStatus.PENDING) throw new ConflictException("Offer is already " + status);
        this.status = target;
        this.respondedAt = now;
    }
}
