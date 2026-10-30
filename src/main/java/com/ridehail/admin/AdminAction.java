package com.ridehail.admin;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One thing an administrator did. Rows are only ever inserted. */
@Entity
@Table(name = "admin_actions")
@Getter @NoArgsConstructor
public class AdminAction {
    @Id private UUID id = UUID.randomUUID();
    private UUID actorAccountId;
    private String actorPhone;
    @Column(length = 64) private String action;
    @Column(length = 64) private String targetType;
    @Column(length = 64) private String targetId;
    @Column(length = 500) private String detail;
    private Instant createdAt;

    AdminAction(UUID actorAccountId, String actorPhone, String action, String targetType, String targetId, String detail, Instant now) {
        this.actorAccountId = actorAccountId;
        this.actorPhone = actorPhone;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail == null ? null : detail.substring(0, Math.min(detail.length(), 500));
        this.createdAt = now;
    }
}
