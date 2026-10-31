package com.ridehail.notify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One phone that may be sent notifications for one customer or rider. The token is the provider's address for the device. */
@Entity
@Table(name = "device_tokens")
public class DeviceToken {
    @Id
    @Column(length = 255)
    private String token;

    @Column(name = "profile_id", nullable = false)
    private UUID profileId;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(nullable = false, length = 16)
    private String platform;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    protected DeviceToken() {}

    public DeviceToken(String token, UUID profileId, String role, String platform, Instant now) {
        this.token = token;
        this.profileId = profileId;
        this.role = role;
        this.platform = platform;
        this.createdAt = now;
        this.lastSeenAt = now;
    }

    public String getToken() { return token; }
    public UUID getProfileId() { return profileId; }
    public String getRole() { return role; }
    public String getPlatform() { return platform; }
    public Instant getLastSeenAt() { return lastSeenAt; }

    /** The same phone signed in as someone else, or opened the app again: it now belongs to this account. */
    public void claim(UUID profileId, String role, String platform, Instant now) {
        this.profileId = profileId;
        this.role = role;
        this.platform = platform;
        this.lastSeenAt = now;
    }
}
