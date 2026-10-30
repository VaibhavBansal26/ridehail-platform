package com.ridehail.dispute;

import com.ridehail.admin.AdminAudit;
import com.ridehail.common.ApiExceptions.NotFoundException;
import com.ridehail.ledger.AccountType;
import com.ridehail.ledger.LedgerService;
import com.ridehail.payment.PaymentRepository;
import com.ridehail.payment.PaymentStatus;
import com.ridehail.payment.PayoutRepository;
import com.ridehail.payment.PayoutStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** What an admin needs to see and do about disputes and provider problems. Role-gated to ADMIN in SecurityConfig. */
@RestController
@RequestMapping("/api/admin")
class AdminFinanceController {

    private final DisputeRepository disputes;
    private final FinanceIncidentRepository incidents;
    private final IncidentService incidentService;
    private final PaymentRepository payments;
    private final PayoutRepository payouts;
    private final LedgerService ledger;
    private final Clock clock;
    private final AdminAudit audit;

    AdminFinanceController(DisputeRepository disputes, FinanceIncidentRepository incidents, IncidentService incidentService,
                           PaymentRepository payments, PayoutRepository payouts, LedgerService ledger, Clock clock, AdminAudit audit) {
        this.audit = audit;
        this.disputes = disputes;
        this.incidents = incidents;
        this.incidentService = incidentService;
        this.payments = payments;
        this.payouts = payouts;
        this.ledger = ledger;
        this.clock = clock;
    }

    record PageView<T>(List<T> items, long total, int page, int size) {
        static <S, T> PageView<T> of(Page<S> p, Function<S, T> map) {
            return new PageView<>(p.getContent().stream().map(map).toList(), p.getTotalElements(), p.getNumber(), p.getSize());
        }
    }

    record DisputeView(UUID id, String providerId, DisputeStatus status, String providerStatus, boolean matched,
                       BigDecimal amount, String currency, String reason, Instant evidenceDueBy, boolean evidenceOverdue,
                       UUID paymentId, UUID tripId, UUID customerId, UUID riderId, boolean frozen,
                       BigDecimal riderHeldAmount, BigDecimal feeBooked, String providerUrl, String ledgerNote,
                       Instant createdAt, Instant resolvedAt) {
        static DisputeView of(Dispute d, Instant now) {
            boolean unanswered = d.getStatus() == DisputeStatus.OPEN || d.getStatus() == DisputeStatus.INQUIRY;
            return new DisputeView(d.getId(), d.getProviderId(), d.getStatus(), d.getProviderStatus(), d.isMatched(),
                    d.getAmount(), d.getCurrency(), d.getReason(), d.getEvidenceDueBy(),
                    unanswered && d.getEvidenceDueBy() != null && d.getEvidenceDueBy().isBefore(now),
                    d.getPaymentId(), d.getTripId(), d.getCustomerId(), d.getRiderId(), d.isFrozen(),
                    d.getRiderHeldAmount(), d.getFeeBooked(), d.getProviderUrl(), d.getLedgerNote(),
                    d.getCreatedAt(), d.getResolvedAt());
        }
    }

    record IncidentView(UUID id, IncidentType type, String providerRef, IncidentStatus status, UUID riderId, UUID paymentId,
                        UUID payoutId, BigDecimal amount, String currency, String detail, Instant createdAt,
                        Instant resolvedAt, String resolutionNote) {
        static IncidentView of(FinanceIncident i) {
            return new IncidentView(i.getId(), i.getType(), i.getProviderRef(), i.getStatus(), i.getRiderId(), i.getPaymentId(),
                    i.getPayoutId(), i.getAmount(), i.getCurrency(), i.getDetail(), i.getCreatedAt(), i.getResolvedAt(),
                    i.getResolutionNote());
        }
    }

    record ResolveRequest(@Size(max = 500) String note) {}

    record DisputeSummary(long open, long inquiries, BigDecimal openAmount, long overdue, Instant earliestEvidenceDueBy) {}

    record IncidentSummary(long open, Map<IncidentType, Long> byType) {}

    /** One glance at everything that currently needs a person. */
    record Attention(boolean needsAction, DisputeSummary disputes, IncidentSummary incidents,
                     long paymentsRequiringAttention, long payoutsRequiringAttention, long payoutsAwaitingFunds,
                     BigDecimal riderFundsOnHold) {}

    // ---------- disputes ----------

    @GetMapping("/disputes")
    PageView<DisputeView> disputes(@RequestParam(required = false) DisputeStatus status,
                                   @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var paging = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        Instant now = Instant.now(clock);
        return PageView.of(status == null ? disputes.findAll(paging) : disputes.findByStatus(status, paging), d -> DisputeView.of(d, now));
    }

    @GetMapping("/disputes/{id}")
    DisputeView dispute(@PathVariable UUID id) {
        return DisputeView.of(disputes.findById(id).orElseThrow(() -> new NotFoundException("Dispute", id)), Instant.now(clock));
    }

    // ---------- incidents ----------

    @GetMapping("/incidents")
    PageView<IncidentView> incidents(@RequestParam(required = false) IncidentStatus status,
                                     @RequestParam(required = false) IncidentType type,
                                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var paging = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<FinanceIncident> result =
                status != null && type != null ? incidents.findByStatusAndType(status, type, paging)
                : status != null ? incidents.findByStatus(status, paging)
                : type != null ? incidents.findByType(type, paging)
                : incidents.findAll(paging);
        return PageView.of(result, IncidentView::of);
    }

    /** Record that a person dealt with it (e.g. reconciled with the provider). Does not move any money. */
    @PostMapping("/incidents/{id}/resolve")
    IncidentView resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody(required = false) ResolveRequest r) {
        var incident = incidentService.resolve(id, r == null ? null : r.note());
        audit.record(jwt, "INCIDENT_RESOLVED", "INCIDENT", id, r == null || r.note() == null ? "Resolved without a note" : r.note());
        return IncidentView.of(incident);
    }

    // ---------- overview ----------

    @GetMapping("/finance/attention")
    Attention attention() {
        Instant now = Instant.now(clock);
        var open = disputes.findByStatusIn(List.of(DisputeStatus.OPEN, DisputeStatus.INQUIRY));
        var chargebacks = open.stream().filter(d -> d.getStatus() == DisputeStatus.OPEN).toList();
        BigDecimal openAmount = chargebacks.stream().map(Dispute::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        long overdue = open.stream().filter(d -> d.getEvidenceDueBy() != null && d.getEvidenceDueBy().isBefore(now)).count();
        Instant earliest = open.stream().map(Dispute::getEvidenceDueBy).filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
        var disputeSummary = new DisputeSummary(chargebacks.size(), open.size() - chargebacks.size(), openAmount, overdue, earliest);

        Map<IncidentType, Long> byType = new EnumMap<>(IncidentType.class);
        var openIncidents = incidents.findByStatus(IncidentStatus.OPEN);
        openIncidents.forEach(i -> byType.merge(i.getType(), 1L, Long::sum));
        var incidentSummary = new IncidentSummary(openIncidents.size(), byType);

        var held = ledger.trialBalance().byType().get(AccountType.RIDER_FUNDS_HELD);
        BigDecimal onHold = held.credits().subtract(held.debits());
        long paymentsAttention = payments.countByStatus(PaymentStatus.REQUIRES_ATTENTION);
        long payoutsAttention = payouts.countByStatus(PayoutStatus.REQUIRES_ATTENTION);
        long awaiting = payouts.countByStatus(PayoutStatus.AWAITING_FUNDS);

        boolean needsAction = !chargebacks.isEmpty() || !openIncidents.isEmpty() || paymentsAttention > 0 || payoutsAttention > 0;
        return new Attention(needsAction, disputeSummary, incidentSummary, paymentsAttention, payoutsAttention, awaiting, onHold);
    }
}
