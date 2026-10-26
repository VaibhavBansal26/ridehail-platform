package com.ridehail.payment;

import com.ridehail.common.ApiExceptions.ConflictException;
import com.ridehail.common.ApiExceptions.NotFoundException;
import com.ridehail.common.RideEvent;
import com.ridehail.common.RideEvent.Type;
import com.ridehail.customer.CustomerRepository;
import com.ridehail.ledger.AccountType;
import com.ridehail.ledger.LedgerService;
import com.ridehail.ledger.LedgerService.Line;
import com.ridehail.ledger.TxKind;
import com.ridehail.pricing.PricingProperties;
import com.ridehail.trip.Trip;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Charging a customer for a completed trip.
 *
 * The provider call is never made inside a database transaction. An attempt is three steps:
 *   1. claim   (tx)  bump attempts, push nextAttemptAt out by a lease; the @Version column lets exactly one worker win
 *   2. call    (no tx) charge with the payment's idempotency key
 *   3. settle  (tx)  on success: mark CAPTURED and post the ledger entries together; on failure: schedule a retry
 * If the process dies between 2 and 3, the lease expires, the retry replays the same key, the provider returns the
 * original success, and step 3 completes. Nobody is charged twice and the ledger is posted once (unique refKey).
 */
@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final CustomerRepository customers;
    private final LedgerService ledger;
    private final PaymentGateway gateway;
    private final PaymentProperties props;
    private final PricingProperties pricing;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    PaymentService(PaymentRepository payments, CustomerRepository customers, LedgerService ledger,
                   PaymentGateway gateway, PaymentProperties props, PricingProperties pricing,
                   TransactionTemplate tx, ApplicationEventPublisher events, Clock clock) {
        this.payments = payments;
        this.customers = customers;
        this.ledger = ledger;
        this.gateway = gateway;
        this.props = props;
        this.pricing = pricing;
        this.tx = tx;
        this.events = events;
        this.clock = clock;
    }

    private record Claim(UUID paymentId, String key, String token, BigDecimal amount, String currency) {}

    // ---------- booking gate & creation ----------

    /** A customer needs a card on file and no unresolved failed payment before booking another ride. */
    @Transactional(readOnly = true)
    public void assertCustomerCanBook(UUID customerId) {
        var customer = customers.findById(customerId).orElseThrow(() -> new NotFoundException("Customer", customerId));
        if (customer.getPaymentMethodToken() == null) {
            throw new ConflictException("Add a payment method before booking a ride");
        }
        if (payments.existsByCustomerIdAndStatus(customerId, PaymentStatus.REQUIRES_ATTENTION)) {
            throw new ConflictException("You have an unpaid trip; resolve it before booking another ride");
        }
    }

    /** Called inside the trip-completion transaction, so a completed trip always has exactly one payment. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Payment createForTrip(Trip trip) {
        return payments.findByTripId(trip.getId()).orElseGet(() -> {
            BigDecimal commission = trip.getFare().multiply(props.commissionRate()).setScale(2, RoundingMode.HALF_UP);
            return payments.save(new Payment(trip.getId(), trip.getCustomerId(), trip.getRiderId(), trip.getFare(),
                    commission, trip.getCurrency(), Instant.now(clock)));
        });
    }

    // ---------- capture ----------

    public void captureForTrip(UUID tripId) {
        payments.findByTripId(tripId).ifPresent(p -> capture(p.getId()));
    }

    /** Safe to call any number of times, from any number of instances. */
    public void capture(UUID paymentId) {
        Claim claim;
        try {
            claim = tx.execute(s -> claim(paymentId));
        } catch (OptimisticLockingFailureException e) {
            return; // another worker claimed this attempt first
        }
        if (claim == null) return;

        GatewayResult result;
        try {
            result = gateway.charge(claim.key(), claim.token(), claim.amount(), claim.currency());
        } catch (RuntimeException e) {
            log.warn("Gateway call failed for payment {}", paymentId, e);
            result = GatewayResult.transientError("Gateway unreachable");
        }
        GatewayResult outcome = result;
        tx.executeWithoutResult(s -> settle(paymentId, outcome));
    }

    /**
     * The provider says this charge went through (webhook). Idempotent. Also rescues payments we had
     * given up on, because the money has really been taken and must be accounted for.
     */
    public void confirmCaptured(UUID chargeKey, String reference) {
        tx.executeWithoutResult(s -> payments.findByChargeKey(chargeKey).ifPresent(p -> {
            if (p.getStatus() == PaymentStatus.REQUIRES_ATTENTION) p.reopenForConfirmation();
            if (p.getStatus() == PaymentStatus.PENDING) settle(p.getId(), GatewayResult.ok(reference));
        }));
    }

    /** Picks up payments whose retry time has come. Run by the scheduler. */
    public void retryDue() {
        for (Payment p : payments.findByStatusAndNextAttemptAtLessThanEqual(PaymentStatus.PENDING, Instant.now(clock))) {
            try {
                capture(p.getId());
            } catch (RuntimeException e) {
                log.warn("Retry of payment {} failed", p.getId(), e);
            }
        }
    }

    private Claim claim(UUID paymentId) {
        Instant now = Instant.now(clock);
        Payment p = payments.findById(paymentId).orElseThrow(() -> new NotFoundException("Payment", paymentId));
        if (!p.isDue(now)) return null;
        var customer = customers.findById(p.getCustomerId()).orElseThrow();
        if (customer.getPaymentMethodToken() == null) {
            p.requireAttention("No payment method on file");
            events.publishEvent(new RideEvent(Type.PAYMENT_FAILED, p.getTripId(), p.getCustomerId(), p.getRiderId(), null));
            return null;
        }
        p.startAttempt(now, props.leaseSeconds());
        return new Claim(p.getId(), p.getChargeKey().toString(), customer.getPaymentMethodToken(), p.getAmount(), p.getCurrency());
    }

    private void settle(UUID paymentId, GatewayResult result) {
        Instant now = Instant.now(clock);
        Payment p = payments.findById(paymentId).orElseThrow();
        if (p.getStatus() != PaymentStatus.PENDING) return; // already settled by a replay

        switch (result.outcome()) {
            case SUCCEEDED -> {
                p.captured(result.reference(), now);
                postCapture(p);
                events.publishEvent(new RideEvent(Type.PAYMENT_CAPTURED, p.getTripId(), p.getCustomerId(), p.getRiderId(), null));
            }
            case DECLINED -> {
                p.requireAttention(result.message());
                events.publishEvent(new RideEvent(Type.PAYMENT_FAILED, p.getTripId(), p.getCustomerId(), p.getRiderId(), null));
            }
            case TRANSIENT_ERROR -> {
                if (p.getAttempts() >= props.maxAttempts()) {
                    p.requireAttention("Gave up after " + p.getAttempts() + " attempts: " + result.message());
                    events.publishEvent(new RideEvent(Type.PAYMENT_FAILED, p.getTripId(), p.getCustomerId(), p.getRiderId(), null));
                } else {
                    long delay = props.retryBackoffSeconds() * (1L << (p.getAttempts() - 1));
                    p.retryAt(now.plusSeconds(delay), result.message());
                }
            }
        }
    }

    /** Customer pays `amount`; the rider is owed their share, the platform keeps the commission. */
    private void postCapture(Payment p) {
        var lines = new ArrayList<Line>();
        lines.add(Line.debit(ledger.platform(AccountType.CASH), p.getAmount()));
        lines.add(Line.credit(ledger.riderAccount(p.getRiderId()), p.getRiderAmount()));
        if (p.getCommissionAmount().signum() > 0) {
            lines.add(Line.credit(ledger.platform(AccountType.COMMISSION_REVENUE), p.getCommissionAmount()));
        }
        ledger.post("PAYMENT_CAPTURE:" + p.getId(), TxKind.PAYMENT_CAPTURE, "Trip " + p.getTripId(), lines);
    }

    // ---------- admin operations ----------

    /** After the customer fixes their card (or ops resolves an outage), try the charge again with a fresh key. */
    public Payment retry(UUID paymentId) {
        tx.executeWithoutResult(s -> {
            Payment p = find(paymentId);
            if (p.getStatus() != PaymentStatus.REQUIRES_ATTENTION) throw new ConflictException("Payment is " + p.getStatus());
            p.resetForRetry(Instant.now(clock));
        });
        capture(paymentId);
        return get(paymentId);
    }

    /** Full refund: money back to the customer, earnings and commission reversed in the ledger. */
    public Payment refund(UUID paymentId) {
        Payment p = get(paymentId);
        if (p.getStatus() != PaymentStatus.CAPTURED) throw new ConflictException("Only captured payments can be refunded");

        GatewayResult r = gateway.refund("refund-" + p.getId(), p.getGatewayReference(), p.getAmount());
        if (!r.succeeded()) throw new ConflictException("Refund was not completed: " + r.message());

        tx.executeWithoutResult(s -> {
            Payment fresh = find(paymentId);
            if (fresh.getStatus() != PaymentStatus.CAPTURED) return;
            fresh.refunded(Instant.now(clock));
            var lines = new ArrayList<Line>();
            lines.add(Line.credit(ledger.platform(AccountType.CASH), fresh.getAmount()));
            lines.add(Line.debit(ledger.riderAccount(fresh.getRiderId()), fresh.getRiderAmount()));
            if (fresh.getCommissionAmount().signum() > 0) {
                lines.add(Line.debit(ledger.platform(AccountType.COMMISSION_REVENUE), fresh.getCommissionAmount()));
            }
            ledger.post("PAYMENT_REFUND:" + fresh.getId(), TxKind.PAYMENT_REFUND, "Refund trip " + fresh.getTripId(), lines);
        });
        return get(paymentId);
    }

    @Transactional(readOnly = true)
    public Payment get(UUID paymentId) {
        return find(paymentId);
    }

    private Payment find(UUID id) {
        return payments.findById(id).orElseThrow(() -> new NotFoundException("Payment", id));
    }

    public String currency() {
        return pricing.currency();
    }
}
