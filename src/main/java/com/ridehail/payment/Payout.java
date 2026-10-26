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
    /** Provider calls that counted: succeeded, were declined, or failed with an error. Waiting for funds does not count. */
    private int attempts;
    private Instant nextAttemptAt;
    private String gatewayReference;
    /** Why it is not PAID (yet): the last error, or the reason it is waiting. */
    private String failureReason;
    /** When it first started waiting for provider funds; the deadline is measured from here. */
    private Instant waitingSince;
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
        return isUnfinished() && !now.isBefore(nextAttemptAt);
    }

    boolean isUnfinished() {
        return status == PayoutStatus.PENDING || status == PayoutStatus.AWAITING_FUNDS;
    }

    /** Blocks other workers from picking this up while one is working on it. */
    void lease(Instant now, long leaseSeconds) {
        nextAttemptAt = now.plusSeconds(leaseSeconds);
    }

    void attempted() {
        attempts++;
    }

    void paid(String reference, Instant now) {
        status = PayoutStatus.PAID;
        gatewayReference = reference;
        failureReason = null;
        waitingSince = null;
        completedAt = now;
    }

    void failed(String reason, Instant now) {
        status = PayoutStatus.FAILED;
        failureReason = reason;
        completedAt = now;
    }

    void retryAt(Instant when, String reason) {
        status = PayoutStatus.PENDING;
        nextAttemptAt = when;
        failureReason = reason;
    }

    void awaitFunds(Instant now, Instant nextLook, String reason) {
        status = PayoutStatus.AWAITING_FUNDS;
        if (waitingSince == null) waitingSince = now;
        nextAttemptAt = nextLook;
        failureReason = reason;
    }

    /** Provider says funds arrived: look again right away. */
    void wake(Instant now) {
        if (status == PayoutStatus.AWAITING_FUNDS) nextAttemptAt = now;
    }

    void requireAttention(String reason) {
        status = PayoutStatus.REQUIRES_ATTENTION;
        failureReason = reason;
    }
}
