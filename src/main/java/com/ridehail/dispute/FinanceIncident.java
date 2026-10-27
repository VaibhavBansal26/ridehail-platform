package com.ridehail.dispute;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Something went wrong at the payment provider that the books cannot fix on their own. It stays OPEN, visible to admins,
 * until a person resolves it (or, for a failed bank payout, until the rider's bank details are updated).
 */
@Entity
@Table(name = "finance_incidents", uniqueConstraints = @UniqueConstraint(columnNames = {"type", "providerRef"}), indexes = {
        @Index(name = "idx_incident_status", columnList = "status,createdAt"),
        @Index(name = "idx_incident_rider", columnList = "riderId,type,status")})
@Getter @NoArgsConstructor
public class FinanceIncident {
    @Id private UUID id = UUID.randomUUID();
    @Version private Long version;

    @Enumerated(EnumType.STRING) private IncidentType type;
    private String providerRef;
    private UUID riderId;
    private UUID paymentId;
    private UUID payoutId;
    @Column(precision = 12, scale = 2) private BigDecimal amount;
    private String currency;
    @Column(length = 1000) private String detail;
    @Enumerated(EnumType.STRING) private IncidentStatus status = IncidentStatus.OPEN;
    private Instant createdAt;
    private Instant resolvedAt;
    @Column(length = 500) private String resolutionNote;

    FinanceIncident(IncidentType type, String providerRef, UUID riderId, UUID paymentId, UUID payoutId,
                    BigDecimal amount, String currency, String detail, Instant now) {
        this.type = type;
        this.providerRef = providerRef;
        this.riderId = riderId;
        this.paymentId = paymentId;
        this.payoutId = payoutId;
        this.amount = amount;
        this.currency = currency;
        this.detail = detail == null ? null : detail.substring(0, Math.min(detail.length(), 1000));
        this.createdAt = now;
    }

    void resolve(String note, Instant now) {
        status = IncidentStatus.RESOLVED;
        resolvedAt = now;
        resolutionNote = note == null ? null : note.substring(0, Math.min(note.length(), 500));
    }
}
