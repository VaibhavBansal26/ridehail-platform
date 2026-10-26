package com.ridehail.payment;

import com.ridehail.common.ApiExceptions.ConflictException;
import com.ridehail.common.ApiExceptions.NotFoundException;
import com.ridehail.ledger.AccountType;
import com.ridehail.ledger.LedgerService;
import com.ridehail.ledger.LedgerService.Line;
import com.ridehail.ledger.TxKind;
import com.ridehail.pricing.PricingProperties;
import com.ridehail.rider.RiderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Paying riders out of their ledger balance. Money moves in three ledger steps so it is never "lost" mid-flight:
 *   reserve  Dr RIDER_PAYABLE        Cr PAYOUTS_IN_TRANSIT   (when requested: the rider can't spend it twice)
 *   settle   Dr PAYOUTS_IN_TRANSIT   Cr CASH                 (provider confirmed)
 *   reverse  Dr PAYOUTS_IN_TRANSIT   Cr RIDER_PAYABLE       (provider rejected: money goes back to the rider)
 * Reserving happens under a row lock on the rider's ledger account, so two concurrent requests can never both
 * spend the same balance. The provider call, like charges, happens outside any transaction and is idempotent.
 */
@Service
public class PayoutService {
    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);
    private static final EnumSet<PayoutStatus> OPEN = EnumSet.of(PayoutStatus.PENDING, PayoutStatus.REQUIRES_ATTENTION);

    private final PayoutRepository payouts;
    private final RiderRepository riders;
    private final LedgerService ledger;
    private final PaymentGateway gateway;
    private final PaymentProperties props;
    private final PricingProperties pricing;
    private final TransactionTemplate tx;
    private final Clock clock;

    PayoutService(PayoutRepository payouts, RiderRepository riders, LedgerService ledger, PaymentGateway gateway,
                  PaymentProperties props, PricingProperties pricing, TransactionTemplate tx, Clock clock) {
        this.payouts = payouts;
        this.riders = riders;
        this.ledger = ledger;
        this.gateway = gateway;
        this.props = props;
        this.pricing = pricing;
        this.tx = tx;
        this.clock = clock;
    }

    private record Claim(UUID payoutId, String token, BigDecimal amount, String currency) {}

    /** @param requested amount to withdraw, or null for the whole available balance */
    public Payout request(UUID riderId, BigDecimal requested) {
        Payout reserved = tx.execute(s -> reserve(riderId, requested));
        execute(reserved.getId());
        return payouts.findById(reserved.getId()).orElseThrow();
    }

    public void execute(UUID payoutId) {
        Claim claim;
        try {
            claim = tx.execute(s -> claim(payoutId));
        } catch (OptimisticLockingFailureException e) {
            return;
        }
        if (claim == null) return;

        GatewayResult result;
        try {
            result = gateway.payout(payoutId.toString(), claim.token(), claim.amount(), claim.currency());
        } catch (RuntimeException e) {
            log.warn("Gateway call failed for payout {}", payoutId, e);
            result = GatewayResult.transientError("Gateway unreachable");
        }
        GatewayResult outcome = result;
        tx.executeWithoutResult(s -> settle(payoutId, outcome));
    }

    public void retryDue() {
        for (Payout p : payouts.findByStatusAndNextAttemptAtLessThanEqual(PayoutStatus.PENDING, Instant.now(clock))) {
            try {
                execute(p.getId());
            } catch (RuntimeException e) {
                log.warn("Retry of payout {} failed", p.getId(), e);
            }
        }
    }

    // ---------- steps ----------

    private Payout reserve(UUID riderId, BigDecimal requested) {
        var rider = riders.findById(riderId).orElseThrow(() -> new NotFoundException("Rider", riderId));
        if (rider.getPayoutAccountToken() == null) throw new ConflictException("Add a payout account first");

        var account = ledger.lockRiderAccount(riderId); // serializes concurrent payout requests for this rider
        if (payouts.existsByRiderIdAndStatusIn(riderId, OPEN)) throw new ConflictException("A payout is already in progress");

        BigDecimal balance = ledger.balance(account);
        BigDecimal amount = (requested == null ? balance : requested).setScale(2, RoundingMode.UNNECESSARY);
        if (amount.compareTo(props.minPayout()) < 0) {
            throw new ConflictException("Minimum payout is " + props.minPayout() + " " + pricing.currency());
        }
        if (amount.compareTo(balance) > 0) throw new ConflictException("Insufficient balance: " + balance);

        Payout p = payouts.save(new Payout(riderId, amount, pricing.currency(), Instant.now(clock)));
        ledger.post("PAYOUT_RESERVE:" + p.getId(), TxKind.PAYOUT_RESERVE, "Payout " + p.getId(), java.util.List.of(
                Line.debit(account, amount),
                Line.credit(ledger.platform(AccountType.PAYOUTS_IN_TRANSIT), amount)));
        return p;
    }

    private Claim claim(UUID payoutId) {
        Instant now = Instant.now(clock);
        Payout p = payouts.findById(payoutId).orElseThrow(() -> new NotFoundException("Payout", payoutId));
        if (!p.isDue(now)) return null;
        var rider = riders.findById(p.getRiderId()).orElseThrow();
        p.startAttempt(now, props.leaseSeconds());
        return new Claim(p.getId(), rider.getPayoutAccountToken(), p.getAmount(), p.getCurrency());
    }

    private void settle(UUID payoutId, GatewayResult result) {
        Instant now = Instant.now(clock);
        Payout p = payouts.findById(payoutId).orElseThrow();
        if (p.getStatus() != PayoutStatus.PENDING) return;

        switch (result.outcome()) {
            case SUCCEEDED -> {
                p.paid(result.reference(), now);
                ledger.post("PAYOUT_SETTLE:" + p.getId(), TxKind.PAYOUT_SETTLE, "Payout " + p.getId(), java.util.List.of(
                        Line.debit(ledger.platform(AccountType.PAYOUTS_IN_TRANSIT), p.getAmount()),
                        Line.credit(ledger.platform(AccountType.CASH), p.getAmount())));
            }
            case DECLINED -> {
                p.failed(result.message(), now);
                ledger.post("PAYOUT_REVERSE:" + p.getId(), TxKind.PAYOUT_REVERSE, "Payout " + p.getId() + " rejected", java.util.List.of(
                        Line.debit(ledger.platform(AccountType.PAYOUTS_IN_TRANSIT), p.getAmount()),
                        Line.credit(ledger.riderAccount(p.getRiderId()), p.getAmount())));
            }
            case TRANSIENT_ERROR -> {
                if (p.getAttempts() >= props.maxAttempts()) {
                    // outcome unknown: do NOT return the money, the provider may have paid
                    p.requireAttention("Gave up after " + p.getAttempts() + " attempts: " + result.message());
                } else {
                    p.retryAt(now.plusSeconds(props.retryBackoffSeconds() * (1L << (p.getAttempts() - 1))), result.message());
                }
            }
        }
    }
}
