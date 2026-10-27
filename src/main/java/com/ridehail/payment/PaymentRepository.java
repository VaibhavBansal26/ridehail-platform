package com.ridehail.payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByTripId(UUID tripId);

    Optional<Payment> findByChargeKey(UUID chargeKey);

    /** The provider's id for the charge (Stripe PaymentIntent), which is how disputes and refunds point back at us. */
    Optional<Payment> findByGatewayReference(String gatewayReference);

    long countByStatus(PaymentStatus status);

    List<Payment> findByStatusAndNextAttemptAtLessThanEqual(PaymentStatus status, Instant cutoff);

    boolean existsByCustomerIdAndStatus(UUID customerId, PaymentStatus status);

    Page<Payment> findByStatus(PaymentStatus status, Pageable pageable);
}
