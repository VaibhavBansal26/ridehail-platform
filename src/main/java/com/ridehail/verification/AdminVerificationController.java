package com.ridehail.verification;

import com.ridehail.admin.AdminAudit;
import com.ridehail.common.ApiExceptions.NotFoundException;
import com.ridehail.rider.RiderRepository;
import com.ridehail.verification.VerificationService.QueueItem;
import com.ridehail.verification.VerificationService.StoredDocument;
import com.ridehail.verification.VerificationService.View;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Review of riders' documents. ADMIN only (SecurityConfig). Every decision AND every time a document is opened is written to the
 * admin audit log with the person behind it: identity documents are the most sensitive data the platform holds.
 */
@RestController
@RequestMapping("/api/admin/verifications")
class AdminVerificationController {
    private final VerificationService verification;
    private final RiderRepository riders;
    private final AdminAudit audit;

    AdminVerificationController(VerificationService verification, RiderRepository riders, AdminAudit audit) {
        this.verification = verification;
        this.riders = riders;
        this.audit = audit;
    }

    record PageView<T>(List<T> items, long total, int page, int size) {
        static <T> PageView<T> of(Page<T> p) { return new PageView<>(p.getContent(), p.getTotalElements(), p.getNumber(), p.getSize()); }
    }
    record Detail(UUID riderId, String name, String phone, String vehicleType, View verification) {}
    record Reason(@Size(max = 500) String reason) {}

    @GetMapping
    PageView<QueueItem> queue(@RequestParam(defaultValue = "PENDING_REVIEW") VerificationStatus status,
                              @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return PageView.of(verification.queue(status, page, size));
    }

    @GetMapping("/{riderId}")
    Detail detail(@PathVariable UUID riderId) {
        var rider = riders.findById(riderId).orElseThrow(() -> new NotFoundException("Rider", riderId));
        return new Detail(riderId, rider.getName(), rider.getPhone(), rider.getVehicleType() == null ? "" : rider.getVehicleType().name(),
                verification.detail(riderId));
    }

    /** The file itself, inline, with headers that stop a browser from guessing, caching or running anything in it. */
    @GetMapping("/{riderId}/documents/{type}")
    ResponseEntity<byte[]> document(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID riderId, @PathVariable DocumentType type) {
        StoredDocument doc = verification.document(riderId, type);
        audit.record(jwt, "VERIFICATION_DOCUMENT_VIEWED", "RIDER", riderId, type.label());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(doc.contentType()))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + type.name().toLowerCase() + extension(doc.contentType()) + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(doc.bytes());
    }

    @PostMapping("/{riderId}/approve")
    View approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID riderId) {
        View v = verification.approve(riderId, UUID.fromString(jwt.getSubject()));
        audit.record(jwt, "VERIFICATION_APPROVED", "RIDER", riderId, null);
        return v;
    }

    @PostMapping("/{riderId}/reject")
    View reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID riderId, @Valid @RequestBody Reason r) {
        View v = verification.reject(riderId, UUID.fromString(jwt.getSubject()), r.reason());
        audit.record(jwt, "VERIFICATION_REJECTED", "RIDER", riderId, r.reason());
        return v;
    }

    @PostMapping("/{riderId}/suspend")
    View suspend(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID riderId, @Valid @RequestBody Reason r) {
        View v = verification.suspend(riderId, UUID.fromString(jwt.getSubject()), r.reason());
        audit.record(jwt, "VERIFICATION_SUSPENDED", "RIDER", riderId, r.reason());
        return v;
    }

    @PostMapping("/{riderId}/reinstate")
    View reinstate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID riderId) {
        View v = verification.reinstate(riderId, UUID.fromString(jwt.getSubject()));
        audit.record(jwt, "VERIFICATION_REINSTATED", "RIDER", riderId, null);
        return v;
    }

    private static String extension(String contentType) {
        return switch (contentType) { case "image/jpeg" -> ".jpg"; case "image/png" -> ".png"; default -> ".pdf"; };
    }
}
