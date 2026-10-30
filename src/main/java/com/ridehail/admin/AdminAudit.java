package com.ridehail.admin;

import com.ridehail.security.UserAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/** Records admin actions against the signed-in person (taken from the verified token, never from the request). */
@Service
public class AdminAudit {
    private final AdminActionRepository actions;
    private final UserAccountRepository accounts;
    private final Clock clock;

    AdminAudit(AdminActionRepository actions, UserAccountRepository accounts, Clock clock) {
        this.actions = actions;
        this.accounts = accounts;
        this.clock = clock;
    }

    /** Call after the action succeeded. */
    public void record(Jwt actor, String action, String targetType, Object targetId, String detail) {
        UUID accountId = UUID.fromString(actor.getSubject());
        String phone = accounts.findById(accountId).map(a -> a.getPhone()).orElse("unknown");
        actions.save(new AdminAction(accountId, phone, action, targetType, targetId == null ? null : targetId.toString(), detail,
                Instant.now(clock)));
    }
}
