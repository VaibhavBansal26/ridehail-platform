package com.ridehail.security;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Login identity. profileId points at the Customer or Rider row (or itself for admins). */
@Entity
@Table(name = "user_accounts", uniqueConstraints = @UniqueConstraint(columnNames = "phone"))
@Getter @NoArgsConstructor
public class UserAccount {
    @Id private UUID id = UUID.randomUUID();
    @Version private Long version;

    private String phone;
    private String passwordHash;
    @Enumerated(EnumType.STRING) private Role role;
    private UUID profileId;
    @Setter private boolean enabled = true;
    private Instant createdAt;

    public UserAccount(String phone, String passwordHash, Role role, UUID profileId, Instant now) {
        this.phone = phone;
        this.passwordHash = passwordHash;
        this.role = role;
        this.profileId = profileId;
        this.createdAt = now;
    }
}
