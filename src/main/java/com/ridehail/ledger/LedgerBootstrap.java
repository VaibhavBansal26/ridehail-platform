package com.ridehail.ledger;

import org.springframework.boot.ApplicationArguments;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Opens the platform's own accounts on first start (idempotent).
 *
 * When several instances start together against a new database, each sees "no accounts yet" and tries to create them; the
 * database allows only one. The loser's transaction is rolled back, so it simply asks again: the accounts now exist.
 */
@Component
class LedgerBootstrap implements ApplicationRunner {
    private final LedgerService ledger;

    LedgerBootstrap(LedgerService ledger) {
        this.ledger = ledger;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ledger.openPlatformAccounts();
        } catch (DataIntegrityViolationException lostTheRace) {
            ledger.openPlatformAccounts();
        }
    }
}
