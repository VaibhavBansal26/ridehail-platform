package com.ridehail.dispatch;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps timed-out offers and re-dispatches. Safe to run on several instances: the offer's version
 * column makes only one instance win each expiry. (Move to a Redis/Temporal timer at higher scale.)
 */
@Component
class OfferExpiryJob {
    private static final Logger log = LoggerFactory.getLogger(OfferExpiryJob.class);

    private final TripOfferRepository offers;
    private final OfferService offerService;
    private final Clock clock;

    OfferExpiryJob(TripOfferRepository offers, OfferService offerService, Clock clock) {
        this.offers = offers;
        this.offerService = offerService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${ridehail.dispatch.expiry-scan-ms}")
    void sweep() {
        for (TripOffer o : offers.findByStatusAndExpiresAtBefore(OfferStatus.PENDING, Instant.now(clock))) {
            try {
                offerService.expire(o.getId());
            } catch (OptimisticLockingFailureException e) {
                log.debug("Offer {} was resolved concurrently, skipping", o.getId());
            }
        }
    }
}
