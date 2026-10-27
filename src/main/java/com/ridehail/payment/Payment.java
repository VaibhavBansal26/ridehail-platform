package com.ridehail.payment;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** The charge for one completed trip: what the customer pays, and how it splits between rider and platform. */
@Entity
@Table(name = "payments", uniqueConstraints = @UniqueConstraint(columnNames = "tripId"),
        indexes = @Index(name = "idx_payment_due", columnList = "status,nextAttemptAt"))
@Getter @NoArgsConstructor
public class Payment {
    @Id private UUID id = UUID.randomUUID();
    /** Optimistic lock: only one worker can claim an attempt. */
    @Version private Long version;

    private UUID tripId;
    private UUID customerId;
    private UUID riderId;
    @Column(precision = 12, scale = 2) private BigDecimal amount;
    @Column(precision = 12, scale = 2) private BigDecimal commissionAmount;
    @Column(precision = 12, scale = 2) private BigDecimal riderAmount;
    private String currency;

    @Enumerated(EnumType.STRING) private PaymentStatus status = PaymentStatus.PENDING;
    private int attempts;
    private Instant nextAttemptAt;
    /** Idempotency key sent to the provider. Rotated only on an admin retry after a decline. */
    private UUID chargeKey = UUID.randomUUID();
    private String gatewayReference;
    private String failureReason;

    private Instant createdAt;
    private Instant capturedAt;
    private Instant refundedAt;

    public Payment(UUID tripId, UUID customerId, UUID riderId, BigDecimal amount, BigDecimal commission,
                   String currency, Instant now) {
        this.tripId = tripId;
        this.customerId = customerId;
        this.riderId = riderId;
        this.amount = amount;
        this.commissionAmount = commission;
        this.riderAmount = amount.subtract(commission);
        this.currency = currency;
        this.createdAt = now;
        this.nextAttemptAt = now;
    }

    boolean isDue(Instant now) {
        return status == PaymentStatus.PENDING && !now.isBefore(nextAttemptAt);
    }

    void startAttempt(Instant now, long leaseSeconds) {
        attempts++;
        nextAttemptAt = now.plusSeconds(leaseSeconds);
    }

    void captured(String reference, Instant now) {
        status = PaymentStatus.CAPTURED;
        gatewayReference = reference;
        capturedAt = now;
        failureReason = null;
    }

    void retryAt(Instant when, String reason) {
        nextAttemptAt = when;
        failureReason = reason;
    }

    void requireAttention(String reason) {
        status = PaymentStatus.REQUIRES_ATTENTION;
        failureReason = reason;
    }

    /** The provider confirmed money was taken after we had given up (e.g. a delayed "processing" charge). */
    void reopenForConfirmation() {
        status = PaymentStatus.PENDING;
    }

    public void markDisputed() {
        if (status == PaymentStatus.CAPTURED) status = PaymentStatus.DISPUTED;
    }

    public void markDisputeWon() {
        if (status == PaymentStatus.DISPUTED) status = PaymentStatus.CAPTURED;
    }

    public void markChargedBack() {
        if (status == PaymentStatus.DISPUTED) status = PaymentStatus.CHARGED_BACK;
    }

    void refunded(Instant now) {
        status = PaymentStatus.REFUNDED;
        refundedAt = now;
    }

    void resetForRetry(Instant now) {
        status = PaymentStatus.PENDING;
        attempts = 0;
        nextAttemptAt = now;
        chargeKey = UUID.randomUUID();
        failureReason = null;
    }
}
