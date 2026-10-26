package com.ridehail.payment;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payouts", indexes = {
        @Index(name = "idx_payout_rider", columnList = "riderId,createdAt"),
        @Index(name = "idx_payout_due", columnList = "status,nextAttemptAt")})
@Getter @NoArgsConstructor
public class Payout {
    /** Doubles as the provider idempotency key. */
    @Id private UUID id = UUID.randomUUID();
    @Version private Long version;

    private UUID riderId;
    @Column(precision = 12, scale = 2) private BigDecimal amount;
    private String currency;
    @Enumerated(EnumType.STRING) private PayoutStatus status = PayoutStatus.PENDING;
    private int attempts;
    private Instant nextAttemptAt;
    private String gatewayReference;
    private String failureReason;
    private Instant createdAt;
    private Instant completedAt;

    public Payout(UUID riderId, BigDecimal amount, String currency, Instant now) {
        this.riderId = riderId;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = now;
        this.nextAttemptAt = now;
    }

    boolean isDue(Instant now) {
        return status == PayoutStatus.PENDING && !now.isBefore(nextAttemptAt);
    }

    void startAttempt(Instant now, long leaseSeconds) {
        attempts++;
        nextAttemptAt = now.plusSeconds(leaseSeconds);
    }

    void paid(String reference, Instant now) {
        status = PayoutStatus.PAID;
        gatewayReference = reference;
        completedAt = now;
    }

    void failed(String reason, Instant now) {
        status = PayoutStatus.FAILED;
        failureReason = reason;
        completedAt = now;
    }

    void retryAt(Instant when, String reason) {
        nextAttemptAt = when;
        failureReason = reason;
    }

    void requireAttention(String reason) {
        status = PayoutStatus.REQUIRES_ATTENTION;
        failureReason = reason;
    }
}
