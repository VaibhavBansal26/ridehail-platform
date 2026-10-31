package com.ridehail.verification;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Where one rider stands in verification. One row per rider, created the first time anyone looks. */
@Entity
@Table(name = "rider_verifications")
public class RiderVerification {
    @Id
    @Column(name = "rider_id")
    private UUID riderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private VerificationStatus status = VerificationStatus.NOT_SUBMITTED;

    @Column(name = "submitted_at") private Instant submittedAt;
    @Column(name = "reviewed_at") private Instant reviewedAt;
    /** The admin account that last decided (approve, reject, suspend, reinstate). */
    @Column(name = "reviewed_by") private UUID reviewedBy;
    /** Shown to the rider: why it was rejected or suspended. Never contains anything about other people. */
    @Column(length = 500) private String note;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected RiderVerification() {}

    public RiderVerification(UUID riderId, Instant now) {
        this.riderId = riderId;
        this.updatedAt = now;
    }

    public UUID getRiderId() { return riderId; }
    public VerificationStatus getStatus() { return status; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public UUID getReviewedBy() { return reviewedBy; }
    public String getNote() { return note; }

    void submitted(Instant now) { status = VerificationStatus.PENDING_REVIEW; submittedAt = now; note = null; updatedAt = now; }

    void decided(VerificationStatus to, UUID admin, String note, Instant now) {
        this.status = to;
        this.reviewedBy = admin;
        this.reviewedAt = now;
        this.note = note;
        this.updatedAt = now;
    }

    /** Not a person's decision: a document ran out. */
    void expired(String note, Instant now) { status = VerificationStatus.EXPIRED; this.note = note; updatedAt = now; }
}
