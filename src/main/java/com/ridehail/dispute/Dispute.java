package com.ridehail.dispute;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A customer's dispute of a charge: its state, the deadline to answer it, and what the ledger did about it. */
@Entity
@Table(name = "disputes", uniqueConstraints = @UniqueConstraint(columnNames = "providerId"), indexes = {
        @Index(name = "idx_dispute_status", columnList = "status,createdAt"),
        @Index(name = "idx_dispute_customer", columnList = "customerId,status"),
        @Index(name = "idx_dispute_payment", columnList = "paymentId")})
@Getter @NoArgsConstructor
public class Dispute {
    @Id private UUID id = UUID.randomUUID();
    @Version private Long version;

    private String providerId;
    private UUID paymentId;
    private UUID tripId;
    private UUID customerId;
    private UUID riderId;
    /** False when we could not tie it to a payment we can account for: recorded for a person, no ledger entries made. */
    private boolean matched;

    @Column(precision = 12, scale = 2) private BigDecimal amount;
    private String currency;
    private String reason;
    private String providerStatus;
    @Enumerated(EnumType.STRING) private DisputeStatus status = DisputeStatus.INQUIRY;
    private Instant evidenceDueBy;

    /** The ledger has taken the contested money out of cash and frozen the rider's share. */
    private boolean frozen;
    @Column(precision = 12, scale = 2) private BigDecimal riderHeldAmount = BigDecimal.ZERO;
    @Column(precision = 12, scale = 2) private BigDecimal feeBooked = BigDecimal.ZERO;

    @Column(length = 512) private String providerUrl;
    @Column(length = 500) private String ledgerNote;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant resolvedAt;

    Dispute(DisputeNotice n, Instant now) {
        this.providerId = n.providerId();
        this.amount = n.amount();
        this.currency = n.currency();
        this.createdAt = now;
        this.updatedAt = now;
    }

    void link(UUID paymentId, UUID tripId, UUID customerId, UUID riderId, boolean matched, String note) {
        this.paymentId = paymentId;
        this.tripId = tripId;
        this.customerId = customerId;
        this.riderId = riderId;
        this.matched = matched;
        this.ledgerNote = note;
    }

    /** Facts that can change on any update, even after the money side is settled. */
    void refresh(DisputeNotice n, Instant now) {
        if (n.reason() != null) reason = n.reason();
        if (n.evidenceDueBy() != null) evidenceDueBy = n.evidenceDueBy();
        if (n.providerUrl() != null) providerUrl = n.providerUrl();
        providerStatus = n.providerStatus();
        updatedAt = now;
    }

    void freeze(BigDecimal heldFromRider) {
        frozen = true;
        riderHeldAmount = heldFromRider;
        status = DisputeStatus.OPEN;
    }

    /** Open at the provider but not something the ledger can act on (no matching payment): shown so its deadline is seen. */
    void markOpen() {
        status = DisputeStatus.OPEN;
    }

    void markInquiry() {
        status = DisputeStatus.INQUIRY;
    }

    void resolve(DisputeStatus finalStatus, Instant now) {
        status = finalStatus;
        resolvedAt = now;
    }

    void feeBooked(BigDecimal total) {
        feeBooked = total;
    }
}
