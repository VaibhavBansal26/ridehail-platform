package com.ridehail.dispute;

import com.ridehail.common.ApiExceptions.ConflictException;
import com.ridehail.common.ApiExceptions.NotFoundException;
import com.ridehail.common.RideEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentService {
    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    private final FinanceIncidentRepository incidents;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    IncidentService(FinanceIncidentRepository incidents, ApplicationEventPublisher events, Clock clock) {
        this.incidents = incidents;
        this.events = events;
        this.clock = clock;
    }

    /** Raises an incident once: the same provider object reported again (a redelivered event) returns the existing one. */
    @Transactional
    public FinanceIncident record(IncidentType type, String providerRef, UUID riderId, UUID paymentId, UUID payoutId,
                                  BigDecimal amount, String currency, String detail) {
        var existing = incidents.findByTypeAndProviderRef(type, providerRef);
        if (existing.isPresent()) return existing.get();

        var incident = incidents.save(new FinanceIncident(type, providerRef, riderId, paymentId, payoutId, amount, currency,
                detail, Instant.now(clock)));
        // ERROR level on purpose: this is the line to alert on, as well as the admin API
        log.error("Finance incident {} ({}): {} rider={} payment={} payout={} {} {}", type, providerRef, detail, riderId,
                paymentId, payoutId, amount, currency);
        if (type == IncidentType.CONNECT_PAYOUT_FAILED && riderId != null) {
            events.publishEvent(new RideEvent(RideEvent.Type.BANK_PAYOUT_FAILED, null, null, riderId, null));
        }
        return incident;
    }

    @Transactional
    public FinanceIncident resolve(UUID id, String note) {
        var incident = incidents.findById(id).orElseThrow(() -> new NotFoundException("Incident", id));
        if (incident.getStatus() == IncidentStatus.RESOLVED) throw new ConflictException("Incident is already resolved");
        incident.resolve(note, Instant.now(clock));
        return incident;
    }

    /** The rider updated their bank details at the provider, so payouts to them can be tried again. */
    @Transactional
    public int resolveBankIssues(UUID riderId, String note) {
        var open = incidents.findByRiderIdAndTypeAndStatus(riderId, IncidentType.CONNECT_PAYOUT_FAILED, IncidentStatus.OPEN);
        Instant now = Instant.now(clock);
        open.forEach(i -> i.resolve(note, now));
        return open.size();
    }

    @Transactional(readOnly = true)
    public boolean hasOpenBankIssue(UUID riderId) {
        return incidents.existsByRiderIdAndTypeAndStatus(riderId, IncidentType.CONNECT_PAYOUT_FAILED, IncidentStatus.OPEN);
    }
}
