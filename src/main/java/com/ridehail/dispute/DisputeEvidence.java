package com.ridehail.dispute;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Our answer to a dispute: the admin's explanation (a draft until submitted) and, once submitted, exactly what was sent.
 * The facts about the trip are compiled from our own records when the evidence is viewed, then frozen here on submission.
 */
@Entity
@Table(name = "dispute_evidence")
@Getter @NoArgsConstructor
public class DisputeEvidence {
    @Id private UUID disputeId;
    @Version private Long version;

    @Column(length = 5000) private String explanation;
    private Instant savedAt;
    private UUID savedBy;

    private boolean submitted;
    private Instant submittedAt;
    private UUID submittedBy;
    @Column(length = 255) private String customerName;
    @Column(length = 500) private String productDescription;
    @Column(length = 32) private String serviceDate;
    @Column(length = 5000) private String accessActivityLog;
    /** The provider refused the last attempt: shown so the admin can fix and retry. */
    @Column(length = 500) private String lastError;

    DisputeEvidence(UUID disputeId) {
        this.disputeId = disputeId;
    }

    void saveDraft(String explanation, UUID by, Instant now) {
        this.explanation = explanation;
        this.savedBy = by;
        this.savedAt = now;
        this.lastError = null;
    }

    void failed(String error) {
        this.lastError = error.length() > 500 ? error.substring(0, 500) : error;
    }

    void submitted(DisputeEvidenceFields sent, UUID by, Instant now) {
        submitted = true;
        submittedAt = now;
        submittedBy = by;
        customerName = sent.customerName();
        productDescription = sent.productDescription();
        serviceDate = sent.serviceDate();
        accessActivityLog = sent.accessActivityLog();
        lastError = null;
    }
}
