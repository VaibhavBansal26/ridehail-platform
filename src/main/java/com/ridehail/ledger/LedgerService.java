package com.ridehail.ledger;

import com.ridehail.pricing.PricingProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Double-entry bookkeeping. Every posting is a set of debit and credit lines that must sum to the same
 * amount, is written atomically with the business change that caused it (caller's transaction), and is
 * idempotent by refKey. Balances are always derived from entries, never stored.
 */
@Service
public class LedgerService {

    public static final UUID PLATFORM = new UUID(0L, 0L);

    private final LedgerAccountRepository accounts;
    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;
    private final PricingProperties pricing;
    private final Clock clock;

    LedgerService(LedgerAccountRepository accounts, LedgerTransactionRepository transactions,
                  LedgerEntryRepository entries, PricingProperties pricing, Clock clock) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.entries = entries;
        this.pricing = pricing;
        this.clock = clock;
    }

    public record Line(LedgerAccount account, Direction direction, BigDecimal amount) {
        public static Line debit(LedgerAccount a, BigDecimal amount) { return new Line(a, Direction.DEBIT, amount); }
        public static Line credit(LedgerAccount a, BigDecimal amount) { return new Line(a, Direction.CREDIT, amount); }
    }

    public record EntryView(Instant at, Direction direction, BigDecimal amount, TxKind kind, String description) {}

    public record TypeTotals(BigDecimal debits, BigDecimal credits) {}

    public record TrialBalance(Map<AccountType, TypeTotals> byType, BigDecimal totalDebits, BigDecimal totalCredits,
                               boolean balanced) {}

    // ---------- accounts ----------

    @Transactional
    public void openPlatformAccounts() {
        for (AccountType t : AccountType.values()) {
            if (t.isPlatformAccount()) open(t, PLATFORM);
        }
    }

    /** Opens a rider's accounts: what we owe them, and the part of it currently frozen by a dispute. */
    @Transactional
    public LedgerAccount openRiderAccount(UUID riderId) {
        open(AccountType.RIDER_FUNDS_HELD, riderId);
        return open(AccountType.RIDER_PAYABLE, riderId);
    }

    /** Riders registered before disputes existed have no held account yet; it is created the first time it is needed. */
    @Transactional
    public LedgerAccount riderHeldAccount(UUID riderId) {
        return open(AccountType.RIDER_FUNDS_HELD, riderId);
    }

    /** What is frozen for this rider (zero if they never had anything held). */
    @Transactional(readOnly = true)
    public BigDecimal riderHeldBalance(UUID riderId) {
        return accounts.findByTypeAndOwnerId(AccountType.RIDER_FUNDS_HELD, riderId).map(this::balance).orElse(BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public LedgerAccount platform(AccountType type) {
        return get(type, PLATFORM);
    }

    @Transactional(readOnly = true)
    public LedgerAccount riderAccount(UUID riderId) {
        return get(AccountType.RIDER_PAYABLE, riderId);
    }

    /** Locks the rider's account row until the caller's transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerAccount lockRiderAccount(UUID riderId) {
        return accounts.lockByTypeAndOwnerId(AccountType.RIDER_PAYABLE, riderId)
                .orElseThrow(() -> new IllegalStateException("No ledger account for rider " + riderId));
    }

    // ---------- posting ----------

    /**
     * @throws IllegalArgumentException if the lines are malformed or debits != credits (nothing is written)
     * @return the existing transaction if this refKey was already posted
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerTransaction post(String refKey, TxKind kind, String description, List<Line> lines) {
        validate(refKey, lines);
        var existing = transactions.findByRefKey(refKey);
        if (existing.isPresent()) return existing.get();

        Instant now = Instant.now(clock);
        LedgerTransaction tx = transactions.save(new LedgerTransaction(refKey, kind, description, now));
        for (Line l : lines) {
            entries.save(new LedgerEntry(tx.getId(), l.account().getId(), l.direction(), l.amount(), now));
        }
        return tx;
    }

    private static void validate(String refKey, List<Line> lines) {
        if (refKey == null || refKey.isBlank()) throw new IllegalArgumentException("refKey required");
        if (lines == null || lines.size() < 2) throw new IllegalArgumentException("A posting needs at least two lines");
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (Line l : lines) {
            if (l.amount() == null || l.amount().signum() <= 0) throw new IllegalArgumentException("Amounts must be positive");
            if (l.amount().scale() > 2) throw new IllegalArgumentException("Amounts have at most 2 decimals");
            if (l.direction() == Direction.DEBIT) debits = debits.add(l.amount());
            else credits = credits.add(l.amount());
        }
        if (debits.compareTo(credits) != 0) {
            throw new IllegalArgumentException("Unbalanced posting: debits " + debits + " != credits " + credits);
        }
    }

    // ---------- reading ----------

    /** Balance on the account's normal side (e.g. what we owe a rider, for RIDER_PAYABLE). */
    @Transactional(readOnly = true)
    public BigDecimal balance(LedgerAccount account) {
        BigDecimal debits = nz(entries.sum(account.getId(), Direction.DEBIT));
        BigDecimal credits = nz(entries.sum(account.getId(), Direction.CREDIT));
        return account.getType().normalSide() == Direction.DEBIT ? debits.subtract(credits) : credits.subtract(debits);
    }

    @Transactional(readOnly = true)
    public List<EntryView> recent(LedgerAccount account) {
        var rows = entries.findTop20ByAccountIdOrderByCreatedAtDesc(account.getId());
        Map<UUID, LedgerTransaction> txs = new java.util.HashMap<>();
        transactions.findAllById(rows.stream().map(LedgerEntry::getTransactionId).distinct().toList())
                .forEach(t -> txs.put(t.getId(), t));
        return rows.stream().map(e -> {
            var t = txs.get(e.getTransactionId());
            return new EntryView(e.getCreatedAt(), e.getDirection(), e.getAmount(), t.getKind(), t.getDescription());
        }).toList();
    }

    /** Integrity check: across the whole ledger, total debits must equal total credits. */
    @Transactional(readOnly = true)
    public TrialBalance trialBalance() {
        Map<AccountType, BigDecimal> debits = totals(Direction.DEBIT);
        Map<AccountType, BigDecimal> credits = totals(Direction.CREDIT);
        Map<AccountType, TypeTotals> byType = new EnumMap<>(AccountType.class);
        BigDecimal totalD = BigDecimal.ZERO, totalC = BigDecimal.ZERO;
        for (AccountType t : AccountType.values()) {
            BigDecimal d = debits.getOrDefault(t, BigDecimal.ZERO), c = credits.getOrDefault(t, BigDecimal.ZERO);
            byType.put(t, new TypeTotals(d, c));
            totalD = totalD.add(d);
            totalC = totalC.add(c);
        }
        return new TrialBalance(byType, totalD, totalC, totalD.compareTo(totalC) == 0);
    }

    private Map<AccountType, BigDecimal> totals(Direction direction) {
        Map<AccountType, BigDecimal> m = new EnumMap<>(AccountType.class);
        for (Object[] row : entries.sumByAccountType(direction)) m.put((AccountType) row[0], (BigDecimal) row[1]);
        return m;
    }

    private LedgerAccount open(AccountType type, UUID owner) {
        return accounts.findByTypeAndOwnerId(type, owner)
                .orElseGet(() -> accounts.save(new LedgerAccount(type, owner, pricing.currency(), Instant.now(clock))));
    }

    private LedgerAccount get(AccountType type, UUID owner) {
        return accounts.findByTypeAndOwnerId(type, owner)
                .orElseThrow(() -> new IllegalStateException("Missing ledger account " + type + "/" + owner));
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
