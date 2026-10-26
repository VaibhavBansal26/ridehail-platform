package com.ridehail.config;

import com.ridehail.common.RideEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Stand-in for the push/WebSocket/SMS fan-out. Replace with listeners that notify the rider app
 * of OFFER_SENT and the customer app of TRIP_ACCEPTED.
 */
@Component
class RideEventLogger {
    private static final Logger log = LoggerFactory.getLogger(RideEventLogger.class);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(RideEvent e) {
        log.info("{} trip={} customer={} rider={} offer={}", e.type(), e.tripId(), e.customerId(), e.riderId(), e.offerId());
    }
}
