package com.ridehail.ops;

import com.ridehail.common.RideEvent;
import com.ridehail.dispute.DisputeRepository;
import com.ridehail.dispute.DisputeStatus;
import com.ridehail.dispute.FinanceIncidentRepository;
import com.ridehail.dispute.IncidentStatus;
import com.ridehail.ledger.LedgerService;
import com.ridehail.payment.PaymentRepository;
import com.ridehail.payment.PaymentStatus;
import com.ridehail.payment.PayoutRepository;
import com.ridehail.payment.PayoutStatus;
import com.ridehail.rider.RiderRepository;
import com.ridehail.rider.RiderStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * What an on-call person needs on a dashboard and in alerts, as Prometheus metrics:
 *  - ridehail_ride_events_total{type}   every trip/payment event, so request, accept and completion rates are visible
 *  - ridehail_riders{status}            how many riders are online / on a trip
 *  - ridehail_payments{status}          payments waiting for a retry, or stuck for a person (REQUIRES_ATTENTION)
 *  - ridehail_payouts{status}           payouts in flight, waiting for funds, or stuck
 *  - ridehail_disputes_open, ridehail_incidents_open   chargebacks and provider problems waiting for a person
 *  - ridehail_ledger_balanced           1 while debits equal credits (checked every few minutes: it reads the whole ledger)
 * Gauges read the database when scraped, so they are never stale and cost one cheap count each.
 */
@Component
class RideMetrics {
    private static final Logger log = LoggerFactory.getLogger(RideMetrics.class);

    private final MeterRegistry registry;
    private final LedgerService ledger;
    private final AtomicInteger ledgerBalanced = new AtomicInteger(1);

    RideMetrics(MeterRegistry registry, RiderRepository riders, PaymentRepository payments, PayoutRepository payouts,
                DisputeRepository disputes, FinanceIncidentRepository incidents, LedgerService ledger) {
        this.registry = registry;
        this.ledger = ledger;

        for (RiderStatus s : RiderStatus.values()) {
            Gauge.builder("ridehail.riders", () -> riders.countByStatus(s)).tag("status", s.name()).register(registry);
        }
        for (PaymentStatus s : new PaymentStatus[] {PaymentStatus.PENDING, PaymentStatus.REQUIRES_ATTENTION}) {
            Gauge.builder("ridehail.payments", () -> payments.countByStatus(s)).tag("status", s.name()).register(registry);
        }
        for (PayoutStatus s : new PayoutStatus[] {PayoutStatus.PENDING, PayoutStatus.AWAITING_FUNDS, PayoutStatus.REQUIRES_ATTENTION}) {
            Gauge.builder("ridehail.payouts", () -> payouts.countByStatus(s)).tag("status", s.name()).register(registry);
        }
        Gauge.builder("ridehail.disputes.open", () -> disputes.countByStatus(DisputeStatus.OPEN)).register(registry);
        Gauge.builder("ridehail.incidents.open", () -> incidents.countByStatus(IncidentStatus.OPEN)).register(registry);
        Gauge.builder("ridehail.ledger.balanced", ledgerBalanced, AtomicInteger::get).register(registry);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(RideEvent e) {
        registry.counter("ridehail.ride.events", "type", e.type().name()).increment();
    }

    /** The one number that must never be 0. Failing to compute it is logged, and leaves the last known value. */
    @Scheduled(initialDelayString = "${ridehail.metrics.ledger-check-initial-ms:30000}", fixedDelayString = "${ridehail.metrics.ledger-check-ms:300000}")
    void checkLedger() {
        try {
            boolean balanced = ledger.trialBalance().balanced();
            ledgerBalanced.set(balanced ? 1 : 0);
            if (!balanced) log.error("LEDGER IS NOT BALANCED: debits and credits differ. Stop payouts and investigate.");
        } catch (RuntimeException ex) {
            log.warn("Could not check the ledger balance for metrics", ex);
        }
    }
}
