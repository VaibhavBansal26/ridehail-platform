package com.ridehail.payment;

import com.ridehail.common.ApiExceptions.ConflictException;
import com.ridehail.common.ApiExceptions.NotFoundException;
import com.ridehail.dispute.IncidentService;
import com.ridehail.ledger.AccountType;
import com.ridehail.ledger.LedgerService;
import com.ridehail.ledger.LedgerService.Line;
import com.ridehail.ledger.TxKind;
import com.ridehail.pricing.PricingProperties;
import com.ridehail.rider.RiderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
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
 *   reverse  Dr PAYOUTS_IN_TRANSIT   Cr RIDER_PAYABLE       (money goes back to the rider: only when we KNOW no transfer happened)
 * Reserving happens under a row lock on the rider's ledger account, so two concurrent requests can never both
 * spend the same balance. The provider call, like charges, happens outside any transaction and is idempotent.
 *
 * <h3>Waiting for funds</h3>
 * A rider can have earned money our ledger says we owe, while the provider's balance is still "pending" (card payments
 * settle after days) and cannot be transferred yet. That is not an error, so it gets its own patient path:
 * <ul>
 *   <li>before each attempt we ask the provider for the settled balance; if it is short, or the provider itself refuses
 *       for lack of funds, the payout becomes AWAITING_FUNDS: nothing is sent, no error attempt is used;</li>
 *   <li>it is looked at again every {@code funds-poll-seconds}, and immediately when the provider reports newly available
 *       funds (webhook), oldest first;</li>
 *   <li>after {@code funds-max-wait-days} it gives up. If no transfer was ever attempted we know nothing was sent, so the
 *       money returns to the rider (FAILED). If an earlier call had an unknown outcome it stays reserved for a person.</li>
 * </ul>
 * Genuine provider errors (timeouts, outages) use a separate, capped exponential backoff and attempt limit.
 */
@Service
public class PayoutService {
    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);
    private static final EnumSet<PayoutStatus> OPEN =
            EnumSet.of(PayoutStatus.PENDING, PayoutStatus.AWAITING_FUNDS, PayoutStatus.REQUIRES_ATTENTION);
    private static final EnumSet<PayoutStatus> UNFINISHED = EnumSet.of(PayoutStatus.PENDING, PayoutStatus.AWAITING_FUNDS);

    private final PayoutRepository payouts;
    private final RiderRepository riders;
    private final LedgerService ledger;
    private final IncidentService incidents;
    private final PaymentGateway gateway;
    private final PaymentProperties props;
    private final PricingProperties pricing;
    private final TransactionTemplate tx;
    private final Clock clock;

    PayoutService(PayoutRepository payouts, RiderRepository riders, LedgerService ledger, IncidentService incidents, PaymentGateway gateway,
                  PaymentProperties props, PricingProperties pricing, TransactionTemplate tx, Clock clock) {
        this.payouts = payouts;
        this.riders = riders;
        this.ledger = ledger;
        this.incidents = incidents;
        this.gateway = gateway;
        this.props = props;
        this.pricing = pricing;
        this.tx = tx;
        this.clock = clock;
    }

    private record Claim(UUID payoutId, String token, BigDecimal amount, String currency) {}

    public record Funding(String currency, BigDecimal providerAvailable, BigDecimal awaitingTotal, long awaitingCount) {}

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

        // Is there settled money to send? Advisory: it saves a refused call, but the provider has the final say below.
        Optional<BigDecimal> available = balance(claim.currency());
        if (available.isPresent() && available.get().compareTo(claim.amount()) < 0) {
            String reason = "Waiting for funds to settle at the payment provider";
            tx.executeWithoutResult(s -> awaitFunds(payoutId, reason));
            return;
        }

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

    /** Works through every payout whose time has come, oldest first. Run by the scheduler. */
    public void retryDue() {
        var due = payouts.findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(UNFINISHED, Instant.now(clock));
        for (Payout p : due) {
            try {
                execute(p.getId());
            } catch (RuntimeException e) {
                log.warn("Retry of payout {} failed", p.getId(), e);
            }
        }
    }

    /** The provider says new funds are available: look at every waiting payout now rather than at the next poll. */
    public void onFundsAvailable() {
        Instant now = Instant.now(clock);
        tx.executeWithoutResult(s -> payouts.findByStatusOrderByCreatedAtAsc(PayoutStatus.AWAITING_FUNDS).forEach(p -> p.wake(now)));
        retryDue();
    }

    /** For ops: how much settled money the provider has against how much riders are waiting for. */
    public Funding funding() {
        var waiting = tx.execute(s -> payouts.findByStatusOrderByCreatedAtAsc(PayoutStatus.AWAITING_FUNDS));
        BigDecimal total = waiting.stream().map(Payout::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Funding(pricing.currency(), balance(pricing.currency()).orElse(null), total, waiting.size());
    }

    // ---------- steps ----------

    private Payout reserve(UUID riderId, BigDecimal requested) {
        var rider = riders.findById(riderId).orElseThrow(() -> new NotFoundException("Rider", riderId));
        if (rider.getPayoutAccountToken() == null) throw new ConflictException("Add a payout account first");
        if (incidents.hasOpenBankIssue(riderId)) {
            throw new ConflictException("Your bank rejected the last payout. Update your bank details, then try again");
        }

        var account = ledger.lockRiderAccount(riderId); // serializes concurrent payout requests for this rider
        if (payouts.existsByRiderIdAndStatusIn(riderId, OPEN)) throw new ConflictException("A payout is already in progress");

        BigDecimal balance = ledger.balance(account);
        BigDecimal amount = (requested == null ? balance : requested).setScale(2, RoundingMode.UNNECESSARY);
        if (amount.compareTo(props.minPayout()) < 0) {
            throw new ConflictException("Minimum payout is " + props.minPayout() + " " + pricing.currency());
        }
        if (amount.compareTo(balance) > 0) throw new ConflictException("Insufficient balance: " + balance);

        Payout p = payouts.save(new Payout(riderId, amount, pricing.currency(), Instant.now(clock)));
        ledger.post("PAYOUT_RESERVE:" + p.getId(), TxKind.PAYOUT_RESERVE, "Payout " + p.getId(), List.of(
                Line.debit(account, amount),
                Line.credit(ledger.platform(AccountType.PAYOUTS_IN_TRANSIT), amount)));
        return p;
    }

    private Claim claim(UUID payoutId) {
        Instant now = Instant.now(clock);
        Payout p = payouts.findById(payoutId).orElseThrow(() -> new NotFoundException("Payout", payoutId));
        if (!p.isDue(now)) return null;
        var rider = riders.findById(p.getRiderId()).orElseThrow();
        p.lease(now, props.leaseSeconds()); // the version column makes exactly one worker win this
        return new Claim(p.getId(), rider.getPayoutAccountToken(), p.getAmount(), p.getCurrency());
    }

    private void settle(UUID payoutId, GatewayResult result) {
        Instant now = Instant.now(clock);
        Payout p = payouts.findById(payoutId).orElseThrow();
        if (!p.isUnfinished()) return;

        switch (result.outcome()) {
            case SUCCEEDED -> {
                p.attempted();
                p.paid(result.reference(), now);
                ledger.post("PAYOUT_SETTLE:" + p.getId(), TxKind.PAYOUT_SETTLE, "Payout " + p.getId(), List.of(
                        Line.debit(ledger.platform(AccountType.PAYOUTS_IN_TRANSIT), p.getAmount()),
                        Line.credit(ledger.platform(AccountType.CASH), p.getAmount())));
            }
            case DECLINED -> {
                p.attempted();
                returnToRider(p, result.message(), now);
            }
            case AWAITING_FUNDS -> awaitFunds(p, "Waiting for funds to settle at the payment provider", now);
            case TRANSIENT_ERROR -> {
                p.attempted();
                if (p.getAttempts() >= props.maxAttempts()) {
                    // outcome unknown: do NOT return the money, the provider may have paid
                    p.requireAttention("Gave up after " + p.getAttempts() + " attempts: " + result.message());
                } else {
                    long delay = Backoff.seconds(p.getAttempts(), props.retryBackoffSeconds(), props.retryBackoffMaxSeconds());
                    p.retryAt(now.plusSeconds(delay), result.message());
                }
            }
        }
    }

    private void awaitFunds(UUID payoutId, String reason) {
        Payout p = payouts.findById(payoutId).orElseThrow();
        if (p.isUnfinished()) awaitFunds(p, reason, Instant.now(clock));
    }

    private void awaitFunds(Payout p, String reason, Instant now) {
        boolean waitedTooLong = p.getWaitingSince() != null
                && Duration.between(p.getWaitingSince(), now).compareTo(Duration.ofDays(props.fundsMaxWaitDays())) > 0;
        if (!waitedTooLong) {
            p.awaitFunds(now, now.plusSeconds(props.fundsPollSeconds()), reason);
        } else if (p.getAttempts() == 0) {
            // every look so far was a clean "not yet": nothing was ever sent, so it is safe to give the money back
            returnToRider(p, "Funds were not available at the payment provider after " + props.fundsMaxWaitDays()
                    + " days; the amount was returned to your balance", now);
        } else {
            // an earlier call had an unknown outcome, so a transfer may have gone out: leave the money reserved
            p.requireAttention("Waited " + props.fundsMaxWaitDays() + " days for provider funds after an earlier error");
        }
    }

    /** The payout definitely did not happen: free the reserved money back to the rider. */
    private void returnToRider(Payout p, String reason, Instant now) {
        p.failed(reason, now);
        ledger.post("PAYOUT_REVERSE:" + p.getId(), TxKind.PAYOUT_REVERSE, "Payout " + p.getId() + " not completed", List.of(
                Line.debit(ledger.platform(AccountType.PAYOUTS_IN_TRANSIT), p.getAmount()),
                Line.credit(ledger.riderAccount(p.getRiderId()), p.getAmount())));
    }

    private Optional<BigDecimal> balance(String currency) {
        try {
            return gateway.availableBalance(currency);
        } catch (RuntimeException e) {
            log.warn("Could not read the provider balance; the transfer will decide", e);
            return Optional.empty();
        }
    }
}
