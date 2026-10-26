package com.ridehail.ledger;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ledger_accounts", uniqueConstraints = @UniqueConstraint(columnNames = {"type", "ownerId"}))
@Getter @NoArgsConstructor
public class LedgerAccount {
    @Id private UUID id = UUID.randomUUID();
    @Enumerated(EnumType.STRING) private AccountType type;
    /** Rider id for RIDER_PAYABLE; a fixed all-zero id for platform accounts (keeps the unique key null-free). */
    private UUID ownerId;
    private String currency;
    private Instant createdAt;

    public LedgerAccount(AccountType type, UUID ownerId, String currency, Instant now) {
        this.type = type;
        this.ownerId = ownerId;
        this.currency = currency;
        this.createdAt = now;
    }
}
