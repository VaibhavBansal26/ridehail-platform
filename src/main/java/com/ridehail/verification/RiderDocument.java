package com.ridehail.verification;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** What was uploaded (the file itself is in the DocumentStore, encrypted). One per rider and type: uploading again replaces it. */
@Entity
@Table(name = "rider_documents", uniqueConstraints = @UniqueConstraint(name = "rider_documents_rider_type_key", columnNames = {"rider_id", "type"}))
public class RiderDocument {
    @Id private UUID id = UUID.randomUUID();
    @Column(name = "rider_id", nullable = false) private UUID riderId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private DocumentType type;
    @Column(name = "content_type", nullable = false, length = 64) private String contentType;
    @Column(name = "size_bytes", nullable = false) private int sizeBytes;
    @Column(name = "expires_on") private LocalDate expiresOn;
    @Column(name = "expiry_warned", nullable = false) private boolean expiryWarned;
    @Column(name = "uploaded_at", nullable = false) private Instant uploadedAt;

    protected RiderDocument() {}

    RiderDocument(UUID riderId, DocumentType type, Instant now) {
        this.riderId = riderId;
        this.type = type;
        this.uploadedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getRiderId() { return riderId; }
    public DocumentType getType() { return type; }
    public String getContentType() { return contentType; }
    public int getSizeBytes() { return sizeBytes; }
    public LocalDate getExpiresOn() { return expiresOn; }
    public boolean isExpiryWarned() { return expiryWarned; }
    public Instant getUploadedAt() { return uploadedAt; }

    void replaceWith(String contentType, int sizeBytes, LocalDate expiresOn, Instant now) {
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.expiresOn = expiresOn;
        this.expiryWarned = false;
        this.uploadedAt = now;
    }

    void warned() { this.expiryWarned = true; }

    boolean expiredOn(LocalDate today) { return expiresOn != null && !expiresOn.isAfter(today); }
}
