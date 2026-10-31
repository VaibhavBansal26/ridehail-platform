package com.ridehail.dispute;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisputeRepository extends JpaRepository<Dispute, UUID> {
    Optional<Dispute> findByProviderId(String providerId);

    Page<Dispute> findByStatus(DisputeStatus status, Pageable pageable);

    List<Dispute> findByStatusIn(Collection<DisputeStatus> statuses);

    long countByStatus(DisputeStatus status);

    boolean existsByCustomerIdAndStatus(UUID customerId, DisputeStatus status);

    List<Dispute> findByTripId(UUID tripId);
}
