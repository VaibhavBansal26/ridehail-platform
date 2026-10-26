package com.ridehail.ledger;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Immutable: entries are never updated or deleted, mistakes are fixed by posting a reversal. */
@Entity
@Table(name = "ledger_entries", indexes = {
        @Index(name = "idx_entry_account", columnList = "accountId,direction"),
        @Index(name = "idx_entry_tx", columnList = "transactionId")})
@Getter @NoArgsConstructor
public class LedgerEntry {
    @Id private UUID id = UUID.randomUUID();
    private UUID transactionId;
    private UUID accountId;
    @Enumerated(EnumType.STRING) private Direction direction;
    @Column(precision = 12, scale = 2) private BigDecimal amount;
    private Instant createdAt;

    public LedgerEntry(UUID transactionId, UUID accountId, Direction direction, BigDecimal amount, Instant now) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.direction = direction;
        this.amount = amount;
        this.createdAt = now;
    }
}
