package com.ridehail.verification;

import com.ridehail.common.ApiExceptions.BadRequestException;
import com.ridehail.common.ApiExceptions.ConflictException;
import com.ridehail.common.ApiExceptions.NotFoundException;
import com.ridehail.common.ApiExceptions.PayloadTooLargeException;
import com.ridehail.common.ApiExceptions.UnsupportedMediaException;
import com.ridehail.notify.NotificationService;
import com.ridehail.notify.PushNotifier;
import com.ridehail.rider.Rider;
import com.ridehail.rider.RiderRepository;
import com.ridehail.rider.RiderStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may drive. A rider uploads four documents and submits; an admin approves or rejects; only approved riders can go online or be
 * offered trips (Rider.verified, which the dispatch query also checks). Licences, registrations and insurance expire, and a rider
 * with an expired one is taken offline until they send current ones.
 */
@Service
public class VerificationService {
    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);
    private static final Set<VerificationStatus> RIDER_MAY_UPLOAD = EnumSet.of(VerificationStatus.NOT_SUBMITTED, VerificationStatus.REJECTED, VerificationStatus.EXPIRED);
    private static final int WARN_DAYS = 14;

    public record RequiredDocument(DocumentType type, String label, boolean needsExpiry, boolean imageOnly) {}
    public record DocumentView(DocumentType type, String label, String contentType, int sizeBytes, Instant uploadedAt, LocalDate expiresOn) {}
    public record View(boolean required, VerificationStatus status, String note, Instant submittedAt, Instant reviewedAt,
                       List<RequiredDocument> requirements, List<DocumentView> documents, List<DocumentType> missing,
                       boolean canUpload, boolean canSubmit) {}
    public record QueueItem(UUID riderId, String name, String phone, String vehicleType, VerificationStatus status, Instant submittedAt, int documents) {}
    public record StoredDocument(String contentType, byte[] bytes) {}

    private final VerificationProperties props;
    private final RiderVerificationRepository verifications;
    private final RiderDocumentRepository documents;
    private final DocumentStore store;
    private final RiderRepository riders;
    private final NotificationService notifications;
    private final Clock clock;

    VerificationService(VerificationProperties props, RiderVerificationRepository verifications, RiderDocumentRepository documents,
                        DocumentStore store, RiderRepository riders, NotificationService notifications, Clock clock) {
        this.props = props;
        this.verifications = verifications;
        this.documents = documents;
        this.store = store;
        this.riders = riders;
        this.notifications = notifications;
        this.clock = clock;
    }

    public boolean required() { return props.required(); }

    private LocalDate today() { return LocalDate.now(clock.withZone(ZoneOffset.UTC)); }

    // ---------------------------------------------------------------- the rider's side

    @Transactional
    public View view(UUID riderId) {
        Rider rider = rider(riderId);
        RiderVerification v = row(rider);
        return viewOf(v, documents.findByRiderId(riderId));
    }

    /** Store one document. Replaces an earlier one of the same type. Only while the rider can still change things. */
    @Transactional
    public View upload(UUID riderId, DocumentType type, byte[] bytes, LocalDate expiresOn) {
        Rider rider = rider(riderId);
        RiderVerification v = row(rider);
        if (!RIDER_MAY_UPLOAD.contains(v.getStatus())) {
            throw new ConflictException(v.getStatus() == VerificationStatus.PENDING_REVIEW
                    ? "Your documents are being reviewed. They cannot be changed until the review is done."
                    : "Documents cannot be changed right now (" + v.getStatus() + ").");
        }
        if (bytes.length == 0) throw new BadRequestException("The file is empty");
        if (bytes.length > props.maxDocumentBytes()) {
            throw new PayloadTooLargeException("The file is larger than " + props.maxDocumentBytes() / (1024 * 1024) + " MB");
        }
        String real = FileSniffer.sniff(bytes);
        if (real == null) throw new UnsupportedMediaException("Only JPEG and PNG pictures and PDF files are accepted");
        if (type.imageOnly() && real.equals("application/pdf")) throw new UnsupportedMediaException(type.label() + " must be a picture, not a PDF");

        LocalDate expiry = null;
        if (type.needsExpiry()) {
            if (expiresOn == null) throw new BadRequestException(type.label() + " needs its expiry date");
            if (!expiresOn.isAfter(today())) throw new BadRequestException("That " + type.label().toLowerCase() + " has already expired");
            if (expiresOn.isAfter(today().plusYears(30))) throw new BadRequestException("That expiry date is too far away to be right");
        }
        expiry = type.needsExpiry() ? expiresOn : null;

        RiderDocument doc = documents.findByRiderIdAndType(riderId, type).orElseGet(() -> new RiderDocument(riderId, type, Instant.now(clock)));
        doc.replaceWith(real, bytes.length, expiry, Instant.now(clock));
        doc = documents.saveAndFlush(doc);
        store.put(doc.getId(), bytes);
        return viewOf(v, documents.findByRiderId(riderId));
    }

    @Transactional
    public View submit(UUID riderId) {
        Rider rider = rider(riderId);
        RiderVerification v = row(rider);
        if (!RIDER_MAY_UPLOAD.contains(v.getStatus())) throw new ConflictException("Nothing to submit (" + v.getStatus() + ")");
        List<RiderDocument> docs = documents.findByRiderId(riderId);
        List<DocumentType> missing = missing(docs);
        if (!missing.isEmpty()) throw new BadRequestException("Still needed: " + labels(missing));
        List<DocumentType> expired = expiredTypes(docs);
        if (!expired.isEmpty()) throw new BadRequestException("Expired: " + labels(expired) + ". Upload a current one.");
        v.submitted(Instant.now(clock));
        verifications.save(v);
        return viewOf(v, docs);
    }

    // ---------------------------------------------------------------- the admin's side

    @Transactional(readOnly = true)
    public Page<QueueItem> queue(VerificationStatus status, int page, int size) {
        var paging = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.ASC, "submittedAt"));
        Page<RiderVerification> found = verifications.findByStatus(status, paging);
        Map<UUID, Rider> byId = riders.findAllById(found.map(RiderVerification::getRiderId).toList()).stream().collect(Collectors.toMap(Rider::getId, r -> r));
        Map<UUID, Long> counts = documents.findByRiderIdIn(byId.keySet()).stream().collect(Collectors.groupingBy(RiderDocument::getRiderId, Collectors.counting()));
        return found.map(v -> {
            Rider r = byId.get(v.getRiderId());
            return new QueueItem(v.getRiderId(), r == null ? "(removed)" : r.getName(), r == null ? "" : r.getPhone(),
                    r == null || r.getVehicleType() == null ? "" : r.getVehicleType().name(), v.getStatus(), v.getSubmittedAt(),
                    counts.getOrDefault(v.getRiderId(), 0L).intValue());
        });
    }

    @Transactional
    public View detail(UUID riderId) { return view(riderId); }

    /** The decrypted file. The caller must record that an admin looked at it. */
    @Transactional(readOnly = true)
    public StoredDocument document(UUID riderId, DocumentType type) {
        RiderDocument doc = documents.findByRiderIdAndType(riderId, type).orElseThrow(() -> new NotFoundException("Document", type));
        byte[] bytes = store.get(doc.getId()).orElseThrow(() -> new NotFoundException("Document", type));
        return new StoredDocument(doc.getContentType(), bytes);
    }

    @Transactional
    public View approve(UUID riderId, UUID admin) {
        Rider rider = rider(riderId);
        RiderVerification v = row(rider);
        requireStatus(v, VerificationStatus.PENDING_REVIEW, "approved");
        List<RiderDocument> docs = documents.findByRiderId(riderId);
        if (!missing(docs).isEmpty() || !expiredTypes(docs).isEmpty()) {
            throw new ConflictException("The documents are incomplete or have expired since they were submitted; reject it so the rider can fix them");
        }
        v.decided(VerificationStatus.APPROVED, admin, null, Instant.now(clock));
        rider.setVerified(true);
        notify(riderId, "You're approved", "You can go online and start taking trips.", "VERIFICATION_APPROVED");
        return viewOf(v, docs);
    }

    @Transactional
    public View reject(UUID riderId, UUID admin, String reason) {
        Rider rider = rider(riderId);
        RiderVerification v = row(rider);
        requireStatus(v, VerificationStatus.PENDING_REVIEW, "rejected");
        if (reason == null || reason.isBlank()) throw new BadRequestException("Say why, so the rider knows what to fix");
        v.decided(VerificationStatus.REJECTED, admin, reason.trim(), Instant.now(clock));
        notify(riderId, "Your documents need attention", "Open Verification to see what to fix.", "VERIFICATION_REJECTED");
        return viewOf(v, documents.findByRiderId(riderId));
    }

    @Transactional
    public View suspend(UUID riderId, UUID admin, String reason) {
        Rider rider = rider(riderId);
        RiderVerification v = row(rider);
        requireStatus(v, VerificationStatus.APPROVED, "suspended");
        if (reason == null || reason.isBlank()) throw new BadRequestException("Say why");
        v.decided(VerificationStatus.SUSPENDED, admin, reason.trim(), Instant.now(clock));
        takeOffTheRoad(rider);
        notify(riderId, "Your account is suspended", "You cannot take trips right now. Open Verification for details.", "VERIFICATION_SUSPENDED");
        return viewOf(v, documents.findByRiderId(riderId));
    }

    @Transactional
    public View reinstate(UUID riderId, UUID admin) {
        Rider rider = rider(riderId);
        RiderVerification v = row(rider);
        requireStatus(v, VerificationStatus.SUSPENDED, "reinstated");
        List<RiderDocument> docs = documents.findByRiderId(riderId);
        if (!missing(docs).isEmpty() || !expiredTypes(docs).isEmpty()) {
            // lifting a suspension must not also lift a lapsed document
            v.expired("Your documents have expired. Upload current ones and submit again.", Instant.now(clock));
            notify(riderId, "Your documents have expired", "Upload current ones to start driving again.", "VERIFICATION_EXPIRED");
        } else {
            v.decided(VerificationStatus.APPROVED, admin, null, Instant.now(clock));
            rider.setVerified(true);
            notify(riderId, "Your account is active again", "You can go online.", "VERIFICATION_APPROVED");
        }
        return viewOf(v, docs);
    }

    // ---------------------------------------------------------------- expiry

    /**
     * Hourly: a rider whose licence, registration or insurance has run out is taken offline until they send current ones, and
     * one whose document runs out within two weeks is warned once. A failure for one rider never stops the others.
     */
    @Scheduled(initialDelayString = "${ridehail.verification.expiry-initial-ms:60000}", fixedDelayString = "${ridehail.verification.expiry-check-ms:3600000}")
    public void checkExpiry() {
        try {
            expireAndWarn();
        } catch (RuntimeException e) {
            log.warn("Checking document expiry failed; it will run again", e);
        }
    }

    @Transactional
    public int expireAndWarn() {
        LocalDate today = today();
        List<RiderDocument> due = documents.expiringBy(today.plusDays(WARN_DAYS));
        int expiredRiders = 0;
        for (RiderDocument doc : due) {
            try {
                RiderVerification v = verifications.findById(doc.getRiderId()).orElse(null);
                if (v == null || v.getStatus() != VerificationStatus.APPROVED) continue;
                if (doc.expiredOn(today)) {
                    Rider rider = riders.findById(doc.getRiderId()).orElse(null);
                    if (rider == null) continue;
                    v.expired("Your " + doc.getType().label().toLowerCase() + " has expired. Upload a current one and submit again.", Instant.now(clock));
                    takeOffTheRoad(rider);
                    notify(rider.getId(), "Your " + doc.getType().label().toLowerCase() + " has expired", "Upload a current one to start driving again.", "VERIFICATION_EXPIRED");
                    expiredRiders++;
                } else if (!doc.isExpiryWarned()) {
                    doc.warned();
                    notify(doc.getRiderId(), "Your " + doc.getType().label().toLowerCase() + " expires on " + doc.getExpiresOn(),
                            "Get a current one ready: you will be taken offline when it expires.", "VERIFICATION_EXPIRING");
                }
            } catch (RuntimeException e) {
                log.warn("Could not process the expiry of document {} for rider {}", doc.getId(), doc.getRiderId(), e);
            }
        }
        return expiredRiders;
    }

    // ---------------------------------------------------------------- pieces

    private Rider rider(UUID riderId) {
        return riders.findById(riderId).orElseThrow(() -> new NotFoundException("Rider", riderId));
    }

    /** A rider who never had a row: one registered before verification existed counts as approved, a new one starts from nothing. */
    private RiderVerification row(Rider rider) {
        return verifications.findById(rider.getId()).orElseGet(() -> {
            var v = new RiderVerification(rider.getId(), Instant.now(clock));
            if (rider.isVerified()) v.decided(VerificationStatus.APPROVED, null, null, Instant.now(clock));
            return verifications.save(v);
        });
    }

    private void requireStatus(RiderVerification v, VerificationStatus expected, String verb) {
        if (v.getStatus() != expected) throw new ConflictException("This rider is " + v.getStatus() + ", so it cannot be " + verb);
    }

    /** Offline now if free; a rider mid-trip finishes it, and Rider.release() keeps them off the road afterwards. */
    private void takeOffTheRoad(Rider rider) {
        rider.setVerified(false);
        if (rider.getStatus() == RiderStatus.AVAILABLE) rider.goOffline();
    }

    private void notify(UUID riderId, String title, String body, String type) {
        notifications.notify(riderId, new PushNotifier.Message(title, body, Map.of("type", type), 0));
    }

    private List<DocumentType> missing(List<RiderDocument> docs) {
        Set<DocumentType> have = docs.stream().map(RiderDocument::getType).collect(Collectors.toSet());
        return Arrays.stream(DocumentType.values()).filter(t -> !have.contains(t)).toList();
    }

    private List<DocumentType> expiredTypes(List<RiderDocument> docs) {
        LocalDate today = today();
        return docs.stream().filter(d -> d.expiredOn(today)).map(RiderDocument::getType).toList();
    }

    private static String labels(List<DocumentType> types) {
        return types.stream().map(t -> t.label().toLowerCase()).collect(Collectors.joining(", "));
    }

    private View viewOf(RiderVerification v, List<RiderDocument> docs) {
        var requirements = Arrays.stream(DocumentType.values()).map(t -> new RequiredDocument(t, t.label(), t.needsExpiry(), t.imageOnly())).toList();
        var shown = docs.stream().sorted(java.util.Comparator.comparing(d -> d.getType().ordinal()))
                .map(d -> new DocumentView(d.getType(), d.getType().label(), d.getContentType(), d.getSizeBytes(), d.getUploadedAt(), d.getExpiresOn())).toList();
        boolean canUpload = RIDER_MAY_UPLOAD.contains(v.getStatus());
        var missing = missing(docs);
        return new View(props.required(), v.getStatus(), v.getNote(), v.getSubmittedAt(), v.getReviewedAt(), requirements, shown, missing,
                canUpload, canUpload && missing.isEmpty() && expiredTypes(docs).isEmpty());
    }
}
