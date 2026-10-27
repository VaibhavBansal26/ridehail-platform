package com.ridehail.security;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the first admin from configuration if it does not exist yet (safe when several instances start at once). */
@Component
class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final SecurityProperties props;
    private final UserAccountRepository accounts;
    private final PasswordEncoder passwords;
    private final Clock clock;

    AdminBootstrap(SecurityProperties props, UserAccountRepository accounts, PasswordEncoder passwords, Clock clock) {
        this.props = props;
        this.accounts = accounts;
        this.passwords = passwords;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        var cfg = props.bootstrapAdmin();
        if (cfg == null || isBlank(cfg.phone()) || isBlank(cfg.password())) return;
        if (accounts.findByPhone(cfg.phone()).isPresent()) return;
        UUID id = UUID.randomUUID();
        try {
            accounts.save(new UserAccount(cfg.phone(), passwords.encode(cfg.password()), Role.ADMIN, id, Instant.now(clock)));
            log.info("Bootstrapped admin account '{}'", cfg.phone());
        } catch (DataIntegrityViolationException anotherInstanceCreatedItFirst) {
            log.info("Admin account '{}' was created by another instance", cfg.phone());
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
