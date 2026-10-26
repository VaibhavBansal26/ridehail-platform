package com.ridehail.payment;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutRepository extends JpaRepository<Payout, UUID> {
    List<Payout> findTop20ByRiderIdOrderByCreatedAtDesc(UUID riderId);

    boolean existsByRiderIdAndStatusIn(UUID riderId, Collection<PayoutStatus> statuses);

    List<Payout> findByRiderIdAndStatusIn(UUID riderId, Collection<PayoutStatus> statuses);

    List<Payout> findByStatusAndNextAttemptAtLessThanEqual(PayoutStatus status, Instant cutoff);

    Page<Payout> findByStatus(PayoutStatus status, Pageable pageable);
}
