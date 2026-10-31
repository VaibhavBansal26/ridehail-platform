package com.ridehail.support;

import com.ridehail.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** A customer's or rider's own conversation with support. Who they are comes from the token, never from the request. */
@RestController
@RequestMapping("/api/support")
class SupportController {
    record Send(@Size(max = SupportService.MAX_BODY) String body, UUID tripId) {}

    private final SupportService support;

    SupportController(SupportService support) {
        this.support = support;
    }

    @GetMapping
    SupportService.ConversationView mine(@AuthenticationPrincipal Jwt jwt) {
        return support.mine(CurrentUser.profileId(jwt));
    }

    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    SupportService.ConversationView send(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Send s) {
        return support.send(CurrentUser.profileId(jwt), CurrentUser.role(jwt), s.body(), s.tripId());
    }

    /** The person has opened the conversation: clear the unread count. */
    @PostMapping("/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(@AuthenticationPrincipal Jwt jwt) {
        support.markRead(CurrentUser.profileId(jwt));
    }
}
