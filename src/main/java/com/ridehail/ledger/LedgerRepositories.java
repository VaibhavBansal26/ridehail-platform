package com.ridehail.ledger;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repositories for the ledger package, grouped in one file. */
final class LedgerRepositories {
    private LedgerRepositories() {}
}

interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {
    Optional<LedgerAccount> findByTypeAndOwnerId(AccountType type, UUID ownerId);

    /** Row lock: serializes things that must not interleave, e.g. two payouts from one rider balance. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LedgerAccount a where a.type = :type and a.ownerId = :ownerId")
    Optional<LedgerAccount> lockByTypeAndOwnerId(@Param("type") AccountType type, @Param("ownerId") UUID ownerId);
}

interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {
    Optional<LedgerTransaction> findByRefKey(String refKey);
}

interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
    @Query("select sum(e.amount) from LedgerEntry e where e.accountId = :accountId and e.direction = :direction")
    BigDecimal sum(@Param("accountId") UUID accountId, @Param("direction") Direction direction);

    @Query("""
            select a.type, sum(e.amount) from LedgerEntry e join LedgerAccount a on a.id = e.accountId
            where e.direction = :direction group by a.type
            """)
    List<Object[]> sumByAccountType(@Param("direction") Direction direction);

    List<LedgerEntry> findTop20ByAccountIdOrderByCreatedAtDesc(UUID accountId);
}
