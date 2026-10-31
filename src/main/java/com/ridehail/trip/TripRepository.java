package com.ridehail.trip;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripRepository extends JpaRepository<Trip, UUID> {
    Optional<Trip> findByQuoteId(UUID quoteId);

    /** The rider's current trip, if any (a rider serves one trip at a time). */
    Optional<Trip> findFirstByRiderIdAndStatusIn(UUID riderId, Collection<TripStatus> statuses);

    Page<Trip> findByStatus(TripStatus status, Pageable pageable);

    /** Trips in any of these states, capped by the page size (used by the live ops map). */
    java.util.List<Trip> findByStatusIn(Collection<TripStatus> statuses, Pageable pageable);

    long countByStatus(TripStatus status);

    /** The customer's current trip, if any (booking is refused while one is going on). */
    Optional<Trip> findFirstByCustomerIdAndStatusIn(UUID customerId, Collection<TripStatus> statuses);

    boolean existsByCustomerIdAndStatusIn(UUID customerId, Collection<TripStatus> statuses);
}
