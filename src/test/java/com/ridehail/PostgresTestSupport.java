package com.ridehail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Wiring for running the integration tests against a REAL Postgres, with the Flyway migrations and Hibernate in
 * "validate" mode. Enabled by setting RIDEHAIL_TEST_PG_URL (CI does; locally point it at any scratch database):
 *
 *   RIDEHAIL_TEST_PG_URL=jdbc:postgresql://localhost:5455/ridehail mvn test
 *
 * Without it these tests are skipped and the suite runs on H2 as usual.
 */
final class PostgresTestSupport {
    static final String URL_ENV = "RIDEHAIL_TEST_PG_URL";

    private PostgresTestSupport() {}

    static void connect(DynamicPropertyRegistry registry) {
        String url = System.getenv(URL_ENV);
        if (url == null || url.isBlank()) return; // tests are skipped by @EnabledIfEnvironmentVariable
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> env("RIDEHAIL_TEST_PG_USER", "ridehail"));
        registry.add("spring.datasource.password", () -> env("RIDEHAIL_TEST_PG_PASSWORD", "ridehail"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    /** Tests need to reset the ledger, which the database (rightly) forbids; lift the guards for the wipe only. */
    static void wipeLedger(JdbcTemplate jdbc) {
        jdbc.execute("ALTER TABLE ledger_entries DISABLE TRIGGER USER");
        jdbc.execute("ALTER TABLE ledger_transactions DISABLE TRIGGER USER");
        try {
            jdbc.update("delete from ledger_entries");
            jdbc.update("delete from ledger_transactions");
            jdbc.update("delete from ledger_accounts where type = 'RIDER_PAYABLE'");
        } finally {
            jdbc.execute("ALTER TABLE ledger_entries ENABLE TRIGGER USER");
            jdbc.execute("ALTER TABLE ledger_transactions ENABLE TRIGGER USER");
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
