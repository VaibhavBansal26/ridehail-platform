package com.ridehail;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** PaymentLedger: the same scenarios as the H2 suite, run on real Postgres with migrations + schema validation. */
@EnabledIfEnvironmentVariable(named = PostgresTestSupport.URL_ENV, matches = ".+")
@SpringBootTest(properties = {"ridehail.dispatch.expiry-scan-ms=3600000", "ridehail.payments.retry-scan-ms=3600000"})
class PostgresPaymentLedgerTest extends PaymentLedgerIntegrationTest {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresTestSupport.connect(registry);
    }

    @Override
    protected void wipeLedger() {
        PostgresTestSupport.wipeLedger(jdbc);
    }
}
