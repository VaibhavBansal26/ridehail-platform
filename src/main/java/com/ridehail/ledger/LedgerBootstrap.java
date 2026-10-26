package com.ridehail.ledger;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Opens the platform's own accounts on first start (idempotent). */
@Component
class LedgerBootstrap implements ApplicationRunner {
    private final LedgerService ledger;

    LedgerBootstrap(LedgerService ledger) {
        this.ledger = ledger;
    }

    @Override
    public void run(ApplicationArguments args) {
        ledger.openPlatformAccounts();
    }
}
