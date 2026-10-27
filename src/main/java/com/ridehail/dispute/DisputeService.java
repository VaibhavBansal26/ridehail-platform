package com.ridehail.dispute;

import com.ridehail.common.RideEvent;
import com.ridehail.common.RideEvent.Type;
import com.ridehail.dispute.DisputeNotice.State;
import com.ridehail.dispute.DisputeProperties.LossPolicy;
import com.ridehail.ledger.AccountType;
import com.ridehail.ledger.LedgerService;
import com.ridehail.ledger.LedgerService.Line;
import com.ridehail.ledger.TxKind;
import com.ridehail.payment.Payment;
import com.ridehail.payment.PaymentRepository;
import com.ridehail.payment.PaymentStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records payment disputes and keeps the ledger honest while they are decided.
 *
 * <h3>What happens to the money</h3>
 * When a chargeback opens, the provider immediately takes the disputed amount out of our balance. The ledger mirrors that:
 * <pre>
 *   open   Dr DISPUTES_IN_PROGRESS  A   Cr CASH               A     the contested money left cash; we may get it back
 *          Dr RIDER_PAYABLE         R   Cr RIDER_FUNDS_HELD   R     the rider's share is frozen: still theirs, not payable
 *   won    Dr CASH                  A   Cr DISPUTES_IN_PROGRESS A   money returned
 *          Dr RIDER_FUNDS_HELD      R   Cr RIDER_PAYABLE      R     freeze lifted
 *   lost   (platform pays)  Dr DISPUTE_LOSSES A  Cr DISPUTES_IN_PROGRESS A;  Dr RIDER_FUNDS_HELD R  Cr RIDER_PAYABLE R
 *          (rider pays)     Dr RIDER_FUNDS_HELD R  Cr DISPUTES_IN_PROGRESS R;  Dr DISPUTE_LOSSES (A-R)  Cr DISPUTES_IN_PROGRESS (A-R)
 *   fees   Dr DISPUTE_FEES f  Cr CASH f                           the provider's dispute fee (reversed if it is refunded)
 * </pre>
 * A is the disputed amount, R the rider's share of it (their share of the fare, pro rata for a partial dispute; zero if the
 * payment was already refunded). If the rider already withdrew the money, the freeze takes their payable balance negative:
 * that is a debt netted against future earnings, which is what lets the platform recover it under the RIDER policy.
 *
 * <h3>Robust to how providers deliver events</h3>
 * Events arrive at least once and not necessarily in order. Each notice carries the dispute's full current state, so this
 * works from state, not event type: a final state (won/lost/closed) is never reverted by a late older event, a missed
 * "created" is made up for before resolving, and every ledger step has its own idempotency key.
 */
@Service
public class DisputeService {
    private static final Logger log = LoggerFactory.getLogger(DisputeService.class);

    private final DisputeRepository disputes;
    private final PaymentRepository payments;
    private final LedgerService ledger;
    private final DisputeProperties props;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    DisputeService(DisputeRepository disputes, PaymentRepository payments, LedgerService ledger, DisputeProperties props,
                   ApplicationEventPublisher events, Clock clock) {
        this.disputes = disputes;
        this.payments = payments;
        this.ledger = ledger;
        this.props = props;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Dispute apply(DisputeNotice n) {
        Instant now = Instant.now(clock);
        Dispute d = disputes.findByProviderId(n.providerId()).orElseGet(() -> disputes.save(open(n, now)));
        d.refresh(n, now);
        if (d.getStatus().isFinal()) return d;       // a late, older event must never undo a decided dispute

        if (!d.isMatched()) {                         // nothing we can account for: keep the record and the latest state
            record(d, n.state(), now);
            return d;
        }

        Payment payment = payments.findById(d.getPaymentId()).orElseThrow();
        switch (n.state()) {
            case INQUIRY -> d.markInquiry();
            case CLOSED -> d.resolve(DisputeStatus.CLOSED, now);                       // an inquiry that never became a chargeback
            case OPEN, WON, LOST -> {
                if (!d.isFrozen()) freeze(d, payment);                                  // also covers a missed "created" event
                syncFee(d, n.feeTotal());
                if (n.state() == State.WON) won(d, payment, now);
                if (n.state() == State.LOST) lost(d, payment, now);
            }
        }
        return d;
    }

    // ---------- creating the record ----------

    private Dispute open(DisputeNotice n, Instant now) {
        Dispute d = new Dispute(n, now);
        var payment = n.paymentReference() == null ? null : payments.findByGatewayReference(n.paymentReference()).orElse(null);
        if (payment == null) {
            d.link(null, null, null, null, false, "No payment of ours matches provider charge " + n.paymentReference());
            log.error("Dispute {} is for a charge we cannot match ({}); recorded without ledger entries", n.providerId(), n.paymentReference());
        } else if (payment.getStatus() != PaymentStatus.CAPTURED && payment.getStatus() != PaymentStatus.REFUNDED) {
            d.link(payment.getId(), payment.getTripId(), payment.getCustomerId(), payment.getRiderId(), false,
                    "Payment is " + payment.getStatus() + ", not a settled charge; no ledger entries were made");
            log.error("Dispute {} is for payment {} in unexpected state {}", n.providerId(), payment.getId(), payment.getStatus());
        } else {
            d.link(payment.getId(), payment.getTripId(), payment.getCustomerId(), payment.getRiderId(), true, null);
        }
        return d;
    }

    private void record(Dispute d, State state, Instant now) {
        switch (state) {
            case INQUIRY -> d.markInquiry();
            case OPEN -> d.markOpen();                    // unmatched: shown as open (it has a deadline) but nothing is frozen
            case CLOSED -> d.resolve(DisputeStatus.CLOSED, now);
            case WON -> d.resolve(DisputeStatus.WON, now);
            case LOST -> d.resolve(DisputeStatus.LOST, now);
        }
    }

    // ---------- ledger steps ----------

    private void freeze(Dispute d, Payment p) {
        BigDecimal a = d.getAmount();
        BigDecimal rider = riderShare(a, p);
        if (rider.signum() > 0) ledger.lockRiderAccount(p.getRiderId());   // serialize with payouts reading this balance

        var lines = new ArrayList<Line>();
        lines.add(Line.debit(ledger.platform(AccountType.DISPUTES_IN_PROGRESS), a));
        lines.add(Line.credit(ledger.platform(AccountType.CASH), a));
        if (rider.signum() > 0) {
            lines.add(Line.debit(ledger.riderAccount(p.getRiderId()), rider));
            lines.add(Line.credit(ledger.riderHeldAccount(p.getRiderId()), rider));
        }
        ledger.post("DISPUTE_OPEN:" + d.getProviderId(), TxKind.DISPUTE_OPEN, "Dispute " + d.getProviderId() + " opened", lines);

        d.freeze(rider);
        p.markDisputed();
        if (rider.signum() > 0) notifyRider(Type.FUNDS_ON_HOLD, d);
    }

    private void won(Dispute d, Payment p, Instant now) {
        BigDecimal a = d.getAmount(), r = d.getRiderHeldAmount();
        var lines = new ArrayList<Line>();
        lines.add(Line.debit(ledger.platform(AccountType.CASH), a));
        lines.add(Line.credit(ledger.platform(AccountType.DISPUTES_IN_PROGRESS), a));
        addRelease(lines, p, r);
        ledger.post("DISPUTE_WON:" + d.getProviderId(), TxKind.DISPUTE_WON, "Dispute " + d.getProviderId() + " won", lines);

        d.resolve(DisputeStatus.WON, now);
        p.markDisputeWon();
        if (r.signum() > 0) notifyRider(Type.HOLD_ENDED, d);
    }

    private void lost(Dispute d, Payment p, Instant now) {
        BigDecimal a = d.getAmount(), r = d.getRiderHeldAmount();
        var lines = new ArrayList<Line>();
        var inProgress = ledger.platform(AccountType.DISPUTES_IN_PROGRESS);
        var losses = ledger.platform(AccountType.DISPUTE_LOSSES);
        if (props.lossPolicy() == LossPolicy.RIDER && r.signum() > 0) {
            lines.add(Line.debit(ledger.riderHeldAccount(p.getRiderId()), r));      // the rider's share is forfeited ...
            lines.add(Line.credit(inProgress, r));
            BigDecimal platformShare = a.subtract(r);
            if (platformShare.signum() > 0) {                                        // ... and the platform absorbs the rest
                lines.add(Line.debit(losses, platformShare));
                lines.add(Line.credit(inProgress, platformShare));
            }
        } else {
            lines.add(Line.debit(losses, a));                                        // the platform absorbs all of it
            lines.add(Line.credit(inProgress, a));
            addRelease(lines, p, r);                                                 // and the rider's share is released
        }
        ledger.post("DISPUTE_LOST:" + d.getProviderId(), TxKind.DISPUTE_LOST, "Dispute " + d.getProviderId() + " lost", lines);

        d.resolve(DisputeStatus.LOST, now);
        p.markChargedBack();
        if (r.signum() > 0) notifyRider(Type.HOLD_ENDED, d);
    }

    private void addRelease(List<Line> lines, Payment p, BigDecimal r) {
        if (r.signum() <= 0) return;
        lines.add(Line.debit(ledger.riderHeldAccount(p.getRiderId()), r));
        lines.add(Line.credit(ledger.riderAccount(p.getRiderId()), r));
    }

    /** Keeps the booked dispute fees equal to what the provider says it has charged (it can refund the fee on a win). */
    private void syncFee(Dispute d, BigDecimal reported) {
        BigDecimal target = reported == null ? BigDecimal.ZERO : reported.max(BigDecimal.ZERO);
        BigDecimal delta = target.subtract(d.getFeeBooked());
        if (delta.signum() == 0) return;

        var cash = ledger.platform(AccountType.CASH);
        var fees = ledger.platform(AccountType.DISPUTE_FEES);
        var lines = delta.signum() > 0
                ? List.of(Line.debit(fees, delta), Line.credit(cash, delta))
                : List.of(Line.debit(cash, delta.negate()), Line.credit(fees, delta.negate()));
        ledger.post("DISPUTE_FEE:" + d.getProviderId() + ":" + d.getFeeBooked() + ">" + target, TxKind.DISPUTE_FEE,
                "Dispute " + d.getProviderId() + " fee", lines);
        d.feeBooked(target);
    }

    /** The rider's cut of the disputed amount: their share of the fare, pro rata for a partial dispute. */
    private static BigDecimal riderShare(BigDecimal disputed, Payment p) {
        if (p.getStatus() == PaymentStatus.REFUNDED) return BigDecimal.ZERO;   // the refund already took their earnings back
        if (disputed.compareTo(p.getAmount()) >= 0) return p.getRiderAmount();
        BigDecimal share = disputed.multiply(p.getRiderAmount()).divide(p.getAmount(), 2, RoundingMode.HALF_UP);
        return share.min(p.getRiderAmount()).min(disputed);
    }

    private void notifyRider(Type type, Dispute d) {
        events.publishEvent(new RideEvent(type, d.getTripId(), null, d.getRiderId(), null));
    }
}
