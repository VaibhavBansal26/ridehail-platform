package com.ridehail.dispute;

import com.ridehail.common.ApiExceptions.BadGatewayException;
import com.ridehail.common.ApiExceptions.BadRequestException;
import com.ridehail.common.ApiExceptions.ConflictException;
import com.ridehail.common.ApiExceptions.NotFoundException;
import com.ridehail.customer.CustomerRepository;
import com.ridehail.payment.GatewayResult;
import com.ridehail.trip.Trip;
import com.ridehail.trip.TripRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Answering a chargeback. The facts (who, when, where, how much) are compiled from our own records at the moment of viewing and
 * again, authoritatively, at submission; the admin adds the explanation. Submitting is one-way: the provider takes a single
 * response, so a dispute can be answered once and never after its deadline.
 */
@Service
public class DisputeEvidenceService {
    private static final Logger log = LoggerFactory.getLogger(DisputeEvidenceService.class);
    public static final int MAX_EXPLANATION = 5000;

    public record View(UUID disputeId, String explanation, Facts facts, boolean submitted, Instant submittedAt,
                       String lastError, boolean canSubmit, String cannotSubmitBecause, Instant evidenceDueBy) {}

    /** The part we write ourselves. For a dispute we could not tie to a trip all of it is null and the explanation must carry the case. */
    public record Facts(String customerName, String productDescription, String serviceDate, String accessActivityLog) {}

    private final DisputeRepository disputes;
    private final DisputeEvidenceRepository evidence;
    private final TripRepository trips;
    private final CustomerRepository customers;
    private final DisputeEvidenceGateway gateway;
    private final Clock clock;

    DisputeEvidenceService(DisputeRepository disputes, DisputeEvidenceRepository evidence, TripRepository trips,
                           CustomerRepository customers, DisputeEvidenceGateway gateway, Clock clock) {
        this.disputes = disputes;
        this.evidence = evidence;
        this.trips = trips;
        this.customers = customers;
        this.gateway = gateway;
        this.clock = clock;
    }

    public View view(UUID disputeId) {
        var dispute = dispute(disputeId);
        var saved = evidence.findById(disputeId).orElse(null);
        return toView(dispute, saved, saved != null && saved.isSubmitted() ? sent(saved) : compile(dispute));
    }

    public View saveDraft(UUID disputeId, String explanation, UUID by) {
        var dispute = dispute(disputeId);
        var saved = evidence.findById(disputeId).orElseGet(() -> new DisputeEvidence(disputeId));
        if (saved.isSubmitted()) throw new ConflictException("The evidence for this dispute has already been submitted");
        if (dispute.getStatus().isFinal()) throw new ConflictException("This dispute is already decided");
        saved.saveDraft(blankToNull(explanation), by, Instant.now(clock));
        saved = evidence.save(saved);
        return toView(dispute, saved, compile(dispute));
    }

    /**
     * Sends the evidence to the provider. Deliberately not one database transaction: the provider call can be slow, and we only
     * record "submitted" once the provider has said yes. If recording fails after a yes, the retry reuses the same idempotency
     * key and the provider repeats its answer.
     */
    public View submit(UUID disputeId, UUID by) {
        var dispute = dispute(disputeId);
        var saved = evidence.findById(disputeId).orElseGet(() -> new DisputeEvidence(disputeId));
        String problem = cannotSubmit(dispute, saved);
        if (problem != null) throw new ConflictException(problem);

        var facts = compile(dispute);
        var fields = new DisputeEvidenceFields(facts.customerName(), facts.productDescription(), facts.serviceDate(),
                facts.accessActivityLog(), saved.getExplanation());
        GatewayResult result = gateway.submitEvidence("dispute-evidence-" + disputeId, dispute.getProviderId(), fields);
        switch (result.outcome()) {
            case SUCCEEDED -> {
                saved.submitted(fields, by, Instant.now(clock));
                log.info("Dispute {} evidence submitted", disputeId);
                return toView(dispute, evidence.save(saved), sent(saved));
            }
            case DECLINED -> {
                saved.failed(result.message() == null ? "The payment provider refused the evidence" : result.message());
                evidence.save(saved);
                throw new BadRequestException("The payment provider refused the evidence: " + saved.getLastError());
            }
            default -> throw new BadGatewayException("The payment provider could not be reached; nothing was submitted. Try again.", null);
        }
    }

    // ----------------------------------------------------------------- internals

    private Dispute dispute(UUID id) {
        return disputes.findById(id).orElseThrow(() -> new NotFoundException("Dispute", id));
    }

    private View toView(Dispute d, DisputeEvidence saved, Facts facts) {
        var current = saved == null ? new DisputeEvidence(d.getId()) : saved;
        String problem = cannotSubmit(d, current);
        return new View(d.getId(), current.getExplanation(), facts, current.isSubmitted(), current.getSubmittedAt(),
                current.getLastError(), problem == null, problem, d.getEvidenceDueBy());
    }

    private String cannotSubmit(Dispute d, DisputeEvidence e) {
        if (e.isSubmitted()) return "The evidence has already been submitted";
        if (d.getStatus().isFinal()) return "This dispute is already decided";
        if (d.getProviderId() == null) return "This dispute has no reference at the payment provider";
        if (d.getEvidenceDueBy() != null && d.getEvidenceDueBy().isBefore(Instant.now(clock))) return "The deadline to answer has passed";
        if (e.getExplanation() == null || e.getExplanation().isBlank()) return "Write the explanation first";
        return null;
    }

    private Facts sent(DisputeEvidence e) {
        return new Facts(e.getCustomerName(), e.getProductDescription(), e.getServiceDate(), e.getAccessActivityLog());
    }

    private Facts compile(Dispute d) {
        Trip trip = d.getTripId() == null ? null : trips.findById(d.getTripId()).orElse(null);
        if (trip == null) return new Facts(null, null, null, null);
        String name = d.getCustomerId() == null ? null : customers.findById(d.getCustomerId()).map(c -> c.getName()).orElse(null);
        Instant when = trip.getCompletedAt() != null ? trip.getCompletedAt() : trip.getStartedAt();
        var log = new ArrayList<String>();
        log.add("Trip " + trip.getId() + " (" + trip.getVehicleType() + ").");
        if (trip.getRequestedAt() != null) log.add("Requested by the customer in the app at " + trip.getRequestedAt() + ".");
        if (trip.getAcceptedAt() != null) log.add("Accepted by a driver at " + trip.getAcceptedAt() + ".");
        if (trip.getStartedAt() != null) log.add("Trip started at " + trip.getStartedAt() + ".");
        if (trip.getCompletedAt() != null) log.add("Trip completed at " + trip.getCompletedAt() + ".");
        if (trip.getFare() != null) {
            log.add("Fare " + trip.getFare().toPlainString() + " " + trip.getCurrency()
                    + (trip.getSurgeMultiplier() != null && trip.getSurgeMultiplier().signum() > 0 ? " (surge x" + trip.getSurgeMultiplier().toPlainString() + ")" : "")
                    + ", charged to the card the customer saved in their account.");
        }
        String description = "Ride-hailing trip (" + trip.getVehicleType() + ") from " + coords(trip.getPickupLat(), trip.getPickupLon())
                + " to " + coords(trip.getDropLat(), trip.getDropLon()) + ".";
        return new Facts(name, description, when == null ? null : when.atZone(ZoneOffset.UTC).toLocalDate().toString(), String.join("\n", log));
    }

    private static String coords(double lat, double lon) {
        return String.format(java.util.Locale.ROOT, "%.5f,%.5f", lat, lon);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
