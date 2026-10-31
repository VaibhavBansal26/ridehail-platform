package com.ridehail.support;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Is anyone answering? The longest-waiting person is the number that matters. */
@Component
class SupportMetrics {
    SupportMetrics(MeterRegistry registry, SupportConversationRepository conversations, Clock clock) {
        Gauge.builder("ridehail.support.awaiting", conversations::countByAwaitingReplyTrue).register(registry);
        Gauge.builder("ridehail.support.oldest.waiting.seconds",
                () -> conversations.oldestWaiting().map(t -> (double) Duration.between(t, Instant.now(clock)).toSeconds()).orElse(0.0))
                .register(registry);
    }
}
