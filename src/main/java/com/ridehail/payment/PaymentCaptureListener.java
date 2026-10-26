package com.ridehail.payment;

import com.ridehail.common.RideEvent;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Charges the customer once a trip's completion has committed, on a background thread so the rider's
 * "complete" call does not wait for the payment provider. If this is lost (crash), the retry job finds the
 * PENDING payment and does it.
 */
@Component
class PaymentCaptureListener {
    private static final Logger log = LoggerFactory.getLogger(PaymentCaptureListener.class);

    private final PaymentService payments;
    private final TaskScheduler scheduler;

    PaymentCaptureListener(PaymentService payments, @Qualifier("taskScheduler") TaskScheduler scheduler) {
        this.payments = payments;
        this.scheduler = scheduler;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(RideEvent e) {
        if (e.type() != RideEvent.Type.TRIP_COMPLETED) return;
        scheduler.schedule(() -> {
            try {
                payments.captureForTrip(e.tripId());
            } catch (RuntimeException ex) {
                log.warn("Capture for trip {} failed; the retry job will pick it up", e.tripId(), ex);
            }
        }, Instant.now());
    }
}
