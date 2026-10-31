package com.ridehail.dispute;

import com.ridehail.admin.AdminAudit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Answering a dispute from the admin panel. The panel's proxy only forwards GET and POST, so saving a draft is a POST. Role-gated to ADMIN in SecurityConfig (everything under /api/admin). */
@RestController
@RequestMapping("/api/admin/disputes/{id}/evidence")
class AdminDisputeEvidenceController {
    record Draft(@Size(max = DisputeEvidenceService.MAX_EXPLANATION) String explanation) {}

    private final DisputeEvidenceService service;
    private final AdminAudit audit;

    AdminDisputeEvidenceController(DisputeEvidenceService service, AdminAudit audit) {
        this.service = service;
        this.audit = audit;
    }

    @GetMapping
    DisputeEvidenceService.View view(@PathVariable UUID id) {
        return service.view(id);
    }

    @PostMapping
    DisputeEvidenceService.View save(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Draft draft) {
        var view = service.saveDraft(id, draft.explanation(), UUID.fromString(jwt.getSubject()));
        audit.record(jwt, "DISPUTE_EVIDENCE_SAVED", "DISPUTE", id, "Draft explanation saved");
        return view;
    }

    @PostMapping("/submit")
    DisputeEvidenceService.View submit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var view = service.submit(id, UUID.fromString(jwt.getSubject()));
        audit.record(jwt, "DISPUTE_EVIDENCE_SUBMITTED", "DISPUTE", id, "Evidence sent to the payment provider");
        return view;
    }
}
