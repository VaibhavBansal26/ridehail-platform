package com.ridehail.verification;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Is the review queue being worked? A rider waiting days to start earning is a rider lost. */
@Component
class VerificationMetrics {
    VerificationMetrics(MeterRegistry registry, RiderVerificationRepository verifications, Clock clock) {
        for (VerificationStatus s : new VerificationStatus[] {VerificationStatus.PENDING_REVIEW, VerificationStatus.SUSPENDED, VerificationStatus.EXPIRED}) {
            Gauge.builder("ridehail.verifications", () -> verifications.countByStatus(s)).tag("status", s.name()).register(registry);
        }
        Gauge.builder("ridehail.verification.oldest.pending.seconds",
                () -> verifications.oldestPending().map(t -> (double) Duration.between(t, Instant.now(clock)).toSeconds()).orElse(0.0))
                .register(registry);
    }
}
