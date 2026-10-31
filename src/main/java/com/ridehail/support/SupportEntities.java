package com.ridehail.support;

import com.ridehail.security.Role;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

enum Sender { USER, STAFF }

enum ConversationStatus { OPEN, RESOLVED }

/** The running conversation between one customer or rider and support. Their message after a resolution reopens it. */
@Entity
@Table(name = "support_conversations", uniqueConstraints = @UniqueConstraint(name = "uq_support_user", columnNames = "userId"), indexes = {
        @Index(name = "idx_support_inbox", columnList = "awaitingReply,awaitingSince"),
        @Index(name = "idx_support_recent", columnList = "status,lastMessageAt")})
@Getter @NoArgsConstructor
class SupportConversation {
    @Id private UUID id = UUID.randomUUID();
    @Version private Long version;

    private UUID userId;
    @Enumerated(EnumType.STRING) private Role role;
    private String name;
    private String phone;
    @Enumerated(EnumType.STRING) private ConversationStatus status = ConversationStatus.OPEN;
    private boolean awaitingReply;
    private Instant awaitingSince;
    private int userUnread;
    private Instant lastMessageAt;
    @Column(length = 160) private String lastPreview;
    private Instant createdAt;

    SupportConversation(UUID userId, Role role, String name, String phone, Instant now) {
        this.userId = userId;
        this.role = role;
        this.name = name;
        this.phone = phone;
        this.createdAt = now;
        this.lastMessageAt = now;
    }

    /** A time for the next message that sorts after every earlier one, even when the clock has not moved (or moved back a little). */
    Instant stampFor(Instant now) {
        return lastMessageAt != null && !now.isAfter(lastMessageAt) ? lastMessageAt.plusNanos(1_000) : now;
    }

    void userWrote(String body, Instant now) {
        status = ConversationStatus.OPEN;
        if (!awaitingReply) awaitingSince = now;
        awaitingReply = true;
        touch(body, now);
    }

    void staffWrote(String body, Instant now) {
        status = ConversationStatus.OPEN;
        awaitingReply = false;
        awaitingSince = null;
        userUnread++;
        touch(body, now);
    }

    void resolve() {
        status = ConversationStatus.RESOLVED;
        awaitingReply = false;
        awaitingSince = null;
    }

    void userRead() {
        userUnread = 0;
    }

    private void touch(String body, Instant now) {
        lastMessageAt = now;
        lastPreview = body.length() > 160 ? body.substring(0, 157) + "..." : body;
    }
}

@Entity
@Table(name = "support_messages", indexes = @Index(name = "idx_support_messages", columnList = "conversationId,createdAt"))
@Getter @NoArgsConstructor
class SupportMessage {
    @Id private UUID id = UUID.randomUUID();
    private UUID conversationId;
    @Enumerated(EnumType.STRING) private Sender sender;
    private UUID staffId;
    @Column(length = 2000, nullable = false) private String body;
    private UUID tripId;
    private Instant createdAt;

    SupportMessage(UUID conversationId, Sender sender, UUID staffId, String body, UUID tripId, Instant now) {
        this.conversationId = conversationId;
        this.sender = sender;
        this.staffId = staffId;
        this.body = body;
        this.tripId = tripId;
        this.createdAt = now;
    }
}
