package com.ridehail.admin;

import com.ridehail.common.ApiExceptions.ConflictException;
import com.ridehail.common.ApiExceptions.NotFoundException;
import com.ridehail.security.Role;
import com.ridehail.security.UserAccount;
import com.ridehail.security.UserAccountRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Named admin accounts, so every action has a person behind it. ADMIN-only via SecurityConfig. */
@RestController
@RequestMapping("/api/admin")
class AdminAccountController {
    static final int MIN_PASSWORD = 12;

    private final UserAccountRepository accounts;
    private final PasswordEncoder passwords;
    private final AdminActionRepository actions;
    private final AdminAudit audit;
    private final Clock clock;

    AdminAccountController(UserAccountRepository accounts, PasswordEncoder passwords, AdminActionRepository actions,
                           AdminAudit audit, Clock clock) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.actions = actions;
        this.audit = audit;
        this.clock = clock;
    }

    record AdminView(UUID id, String phone, boolean enabled, boolean you, Instant createdAt) {}

    record CreateAdmin(@NotBlank @Size(max = 100) String phone, @NotBlank @Size(min = MIN_PASSWORD, max = 72) String password) {}

    record ActionView(UUID id, UUID actorAccountId, String actorPhone, String action, String targetType, String targetId,
                      String detail, Instant createdAt) {}

    @GetMapping("/admins")
    List<AdminView> admins(@AuthenticationPrincipal Jwt jwt) {
        return accounts.findAll().stream().filter(a -> a.getRole() == Role.ADMIN)
                .sorted(Comparator.comparing(UserAccount::getCreatedAt))
                .map(a -> view(a, jwt)).toList();
    }

    @PostMapping("/admins")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    AdminView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateAdmin r) {
        String phone = r.phone().trim();
        if (phone.isEmpty()) throw new ConflictException("Enter a login name");
        if (accounts.findByPhone(phone).isPresent()) throw new ConflictException("That login name is already taken");
        var account = accounts.save(new UserAccount(phone, passwords.encode(r.password()), Role.ADMIN, UUID.randomUUID(), Instant.now(clock)));
        audit.record(jwt, "ADMIN_CREATED", "ADMIN", account.getId(), "Created admin " + phone);
        return view(account, jwt);
    }

    @PostMapping("/admins/{id}/disable")
    @Transactional
    AdminView disable(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var account = admin(id);
        if (account.getId().toString().equals(jwt.getSubject())) throw new ConflictException("You cannot disable your own account");
        if (account.isEnabled() && accounts.findAll().stream().filter(a -> a.getRole() == Role.ADMIN && a.isEnabled()).count() <= 1) {
            throw new ConflictException("There must be at least one active admin");
        }
        account.setEnabled(false);
        audit.record(jwt, "ADMIN_DISABLED", "ADMIN", id, "Disabled admin " + account.getPhone());
        return view(account, jwt);
    }

    @PostMapping("/admins/{id}/enable")
    @Transactional
    AdminView enable(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        var account = admin(id);
        account.setEnabled(true);
        audit.record(jwt, "ADMIN_ENABLED", "ADMIN", id, "Enabled admin " + account.getPhone());
        return view(account, jwt);
    }

    @GetMapping("/audit")
    AdminController.PageView<ActionView> audit(@RequestParam(required = false) String action,
                                               @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var paging = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        var result = action == null || action.isBlank() ? actions.findAll(paging) : actions.findByAction(action, paging);
        return AdminController.PageView.of(result, a -> new ActionView(a.getId(), a.getActorAccountId(), a.getActorPhone(), a.getAction(),
                a.getTargetType(), a.getTargetId(), a.getDetail(), a.getCreatedAt()));
    }

    private UserAccount admin(UUID id) {
        return accounts.findById(id).filter(a -> a.getRole() == Role.ADMIN).orElseThrow(() -> new NotFoundException("Admin", id));
    }

    private static AdminView view(UserAccount a, Jwt jwt) {
        return new AdminView(a.getId(), a.getPhone(), a.isEnabled(), a.getId().toString().equals(jwt.getSubject()), a.getCreatedAt());
    }
}
