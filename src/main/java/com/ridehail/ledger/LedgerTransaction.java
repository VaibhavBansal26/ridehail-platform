package com.ridehail.ledger;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One business event, made of balanced entries. refKey is unique, so replaying the same event
 * (retry, duplicate message, second instance) can never post twice.
 */
@Entity
@Table(name = "ledger_transactions", uniqueConstraints = @UniqueConstraint(columnNames = "refKey"))
@Getter @NoArgsConstructor
public class LedgerTransaction {
    @Id private UUID id = UUID.randomUUID();
    private String refKey;
    @Enumerated(EnumType.STRING) private TxKind kind;
    private String description;
    private Instant createdAt;

    public LedgerTransaction(String refKey, TxKind kind, String description, Instant now) {
        this.refKey = refKey;
        this.kind = kind;
        this.description = description;
        this.createdAt = now;
    }
}
