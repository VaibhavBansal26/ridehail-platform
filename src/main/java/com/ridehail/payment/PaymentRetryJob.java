package com.ridehail.payment;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Safety net: retries failed attempts and picks up anything lost between commit and the async capture. */
@Component
class PaymentRetryJob {
    private final PaymentService payments;
    private final PayoutService payouts;

    PaymentRetryJob(PaymentService payments, PayoutService payouts) {
        this.payments = payments;
        this.payouts = payouts;
    }

    @Scheduled(fixedDelayString = "${ridehail.payments.retry-scan-ms}")
    void run() {
        payments.retryDue();
        payouts.retryDue();
    }
}
