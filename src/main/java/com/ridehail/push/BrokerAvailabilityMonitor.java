package com.ridehail.push;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.messaging.simp.broker.BrokerAvailabilityEvent;
import org.springframework.stereotype.Component;

/**
 * Tells operators when this instance can or cannot reach the message broker. While it cannot, REST keeps working and
 * pushes are simply not delivered (best-effort); clients refetch state when they reconnect. Alert on the metric
 * {@code ridehail.realtime.broker.available} being 0.
 */
@Component
class BrokerAvailabilityMonitor implements ApplicationListener<BrokerAvailabilityEvent> {
    private static final Logger log = LoggerFactory.getLogger(BrokerAvailabilityMonitor.class);

    private final AtomicInteger available = new AtomicInteger(0);

    BrokerAvailabilityMonitor(MeterRegistry metrics) {
        Gauge.builder("ridehail.realtime.broker.available", available, AtomicInteger::get)
                .description("1 while this instance is connected to the message broker, 0 otherwise")
                .register(metrics);
    }

    @Override
    public void onApplicationEvent(BrokerAvailabilityEvent event) {
        available.set(event.isBrokerAvailable() ? 1 : 0);
        if (event.isBrokerAvailable()) {
            log.info("Message broker is available: realtime pushes reach users on every instance");
        } else {
            log.error("Message broker is UNAVAILABLE: realtime pushes are not being delivered until it returns");
        }
    }

    boolean isAvailable() {
        return available.get() == 1;
    }
}
