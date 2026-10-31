package com.ridehail.support;

import com.ridehail.admin.AdminAudit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Support inbox for admins. Role-gated to ADMIN in SecurityConfig (everything under /api/admin). */
@RestController
@RequestMapping("/api/admin/support")
class AdminSupportController {
    record Page(List<SupportService.InboxItem> items, long total, int page, int size) {}
    record Reply(@Size(max = SupportService.MAX_BODY) String body) {}

    private final SupportService support;
    private final AdminAudit audit;

    AdminSupportController(SupportService support, AdminAudit audit) {
        this.support = support;
        this.audit = audit;
    }

    /** @param filter awaiting (someone is waiting for an answer), open, resolved or all */
    @GetMapping
    Page inbox(@RequestParam(required = false) String filter, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var p = support.inbox(filter, page, size);
        return new Page(p.getContent(), p.getTotalElements(), p.getNumber(), p.getSize());
    }

    @GetMapping("/summary")
    SupportService.Summary summary() {
        return support.summary();
    }

    @GetMapping("/{id}")
    SupportService.Thread thread(@PathVariable UUID id) {
        return support.thread(id);
    }

    @PostMapping("/{id}/reply")
    @ResponseStatus(HttpStatus.CREATED)
    SupportService.MessageView reply(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Reply r) {
        var message = support.reply(id, UUID.fromString(jwt.getSubject()), r.body());
        audit.record(jwt, "SUPPORT_REPLIED", "SUPPORT_CONVERSATION", id, "Replied to a support conversation");
        return message;
    }

    @PostMapping("/{id}/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        support.resolve(id);
        audit.record(jwt, "SUPPORT_RESOLVED", "SUPPORT_CONVERSATION", id, "Marked a support conversation resolved");
    }
}
