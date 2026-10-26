package com.ridehail.trip;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripRepository extends JpaRepository<Trip, UUID> {
    Optional<Trip> findByQuoteId(UUID quoteId);

    Page<Trip> findByStatus(TripStatus status, Pageable pageable);

    boolean existsByCustomerIdAndStatusIn(UUID customerId, Collection<TripStatus> statuses);
}
