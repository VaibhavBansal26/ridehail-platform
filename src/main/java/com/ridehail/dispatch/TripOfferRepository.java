package com.ridehail.dispatch;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TripOfferRepository extends JpaRepository<TripOffer, UUID> {
    List<TripOffer> findByRiderIdAndStatusOrderByCreatedAtAsc(UUID riderId, OfferStatus status);

    List<TripOffer> findByTripIdAndStatus(UUID tripId, OfferStatus status);

    List<TripOffer> findByTripIdOrderByCreatedAtAsc(UUID tripId);

    List<TripOffer> findByStatusAndExpiresAtBefore(OfferStatus status, Instant cutoff);

    @Query("select o.riderId from TripOffer o where o.tripId = :tripId")
    Collection<UUID> riderIdsOfferedTrip(@Param("tripId") UUID tripId);

    @Query("select o.riderId from TripOffer o where o.status = com.ridehail.dispatch.OfferStatus.PENDING")
    Collection<UUID> riderIdsWithPendingOffers();
}
