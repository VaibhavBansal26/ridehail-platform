package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the database itself guarantees, independent of application code. (That this context starts at all proves the
 * Flyway migrations produce exactly the schema the entities expect: Hibernate runs in "validate" mode.)
 */
@EnabledIfEnvironmentVariable(named = PostgresTestSupport.URL_ENV, matches = ".+")
@SpringBootTest(properties = {"ridehail.dispatch.expiry-scan-ms=3600000", "ridehail.payments.retry-scan-ms=3600000"})
class PostgresSchemaTest {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresTestSupport.connect(registry);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    UUID cash, revenue;

    @BeforeEach
    void accounts() {
        cash = jdbc.queryForObject("select id from ledger_accounts where type = 'CASH'", UUID.class);
        revenue = jdbc.queryForObject("select id from ledger_accounts where type = 'COMMISSION_REVENUE'", UUID.class);
    }

    @Test
    void flywayAppliedEveryMigration() {
        assertThat(jdbc.queryForList("select version from flyway_schema_history where success order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    void theAdminAuditLogRefusesEditsAndDeletes() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into admin_actions (id, actor_account_id, actor_phone, action, created_at) values (?, ?, 'tester', 'TEST', now())",
                id, UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update("update admin_actions set actor_phone = 'someone else' where id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from admin_actions where id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void aBalancedPostingCommits() {
        String ref = "pg-test-balanced-" + UUID.randomUUID();
        tx.executeWithoutResult(s -> post(ref, "10.00", "10.00"));
        assertThat(jdbc.queryForObject("select count(*) from ledger_entries e join ledger_transactions t on t.id = e.transaction_id where t.ref_key = ?",
                Integer.class, ref)).isEqualTo(2);
    }

    @Test
    void anUnbalancedPostingIsRejectedByTheDatabaseAtCommit() {
        String ref = "pg-test-unbalanced-" + UUID.randomUUID();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> post(ref, "10.00", "9.99")))
                .hasStackTraceContaining("unbalanced ledger transaction");
        assertThat(jdbc.queryForObject("select count(*) from ledger_transactions where ref_key = ?", Integer.class, ref)).isZero();
    }

    @Test
    void ledgerRowsCanNeverBeChangedOrRemoved() {
        String ref = "pg-test-immutable-" + UUID.randomUUID();
        tx.executeWithoutResult(s -> post(ref, "5.00", "5.00"));
        UUID entry = jdbc.queryForObject("select e.id from ledger_entries e join ledger_transactions t on t.id = e.transaction_id where t.ref_key = ? limit 1",
                UUID.class, ref);

        assertThatThrownBy(() -> jdbc.update("update ledger_entries set amount = 1.00 where id = ?", entry))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from ledger_entries where id = ?", entry))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("update ledger_transactions set kind = 'PAYOUT_SETTLE' where ref_key = ?", ref))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("truncate ledger_entries"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void theSameBusinessEventCannotBePostedTwice() {
        String ref = "pg-test-dup-" + UUID.randomUUID();
        tx.executeWithoutResult(s -> post(ref, "1.00", "1.00"));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> post(ref, "1.00", "1.00")))
                .hasMessageContaining("ledger_transactions_ref_key_key");
    }

    @Test
    void moneyColumnsRejectNonsense() {
        UUID tx1 = UUID.randomUUID();
        jdbc.update("insert into ledger_transactions (id, ref_key, kind, created_at) values (?, ?, 'PAYMENT_CAPTURE', now())", tx1, "pg-test-nonsense-" + tx1);
        assertThatThrownBy(() -> jdbc.update("insert into ledger_entries (id, transaction_id, account_id, direction, amount, created_at) values (?, ?, ?, 'DEBIT', -5.00, now())",
                UUID.randomUUID(), tx1, cash)).hasMessageContaining("ledger_entries_amount_check");
        assertThatThrownBy(() -> jdbc.update("insert into ledger_entries (id, transaction_id, account_id, direction, amount, created_at) values (?, ?, ?, 'SIDEWAYS', 5.00, now())",
                UUID.randomUUID(), tx1, cash)).hasMessageContaining("ledger_entries_direction_check");
        assertThatThrownBy(() -> jdbc.update("insert into ledger_entries (id, transaction_id, account_id, direction, amount, created_at) values (?, ?, ?, 'DEBIT', 5.00, now())",
                UUID.randomUUID(), tx1, UUID.randomUUID())).hasMessageContaining("violates foreign key");
        // the orphan transaction row has no entries, so it is balanced (0 = 0) and is cleaned up with the guards lifted
        PostgresTestSupport.wipeLedger(jdbc);
    }

    @Test
    void aRiderCanHaveAtMostOneOpenPayoutEvenIfTheApplicationForgetsToCheck() {
        UUID rider = UUID.randomUUID();
        insertPayout(rider, "PENDING");
        assertThatThrownBy(() -> insertPayout(rider, "REQUIRES_ATTENTION")).hasMessageContaining("uq_payouts_one_open_per_rider");
        assertThatThrownBy(() -> insertPayout(rider, "AWAITING_FUNDS")).hasMessageContaining("uq_payouts_one_open_per_rider");
        insertPayout(rider, "PAID");     // finished payouts do not count
        insertPayout(rider, "PAID");
        jdbc.update("delete from payouts where rider_id = ?", rider);
    }

    @Test
    void aWaitingPayoutIsAnOpenPayoutAndTheStatusIsAccepted() {
        UUID rider = UUID.randomUUID();
        insertPayout(rider, "AWAITING_FUNDS");
        assertThatThrownBy(() -> insertPayout(rider, "PENDING")).hasMessageContaining("uq_payouts_one_open_per_rider");
        assertThatThrownBy(() -> insertPayout(UUID.randomUUID(), "WAITING")).hasMessageContaining("payouts_status_check");
        jdbc.update("delete from payouts where rider_id = ?", rider);
    }

    @Test
    void theDisputeLedgerAccountsAndKindsAreAcceptedAndBogusOnesAreNot() {
        for (String type : new String[] {"RIDER_FUNDS_HELD", "DISPUTES_IN_PROGRESS", "DISPUTE_LOSSES", "DISPUTE_FEES"}) {
            assertThat(jdbc.queryForObject("select count(*) from ledger_accounts where type = ?", Integer.class, type)).isGreaterThanOrEqualTo(0);
        }
        assertThatThrownBy(() -> jdbc.update("insert into ledger_accounts (id, type, owner_id, currency, created_at) values (?, 'NONSENSE', ?, 'USD', now())",
                UUID.randomUUID(), UUID.randomUUID())).hasMessageContaining("ledger_accounts_type_check");
        UUID ok = UUID.randomUUID();
        jdbc.update("insert into ledger_accounts (id, type, owner_id, currency, created_at) values (?, 'RIDER_FUNDS_HELD', ?, 'USD', now())", ok, UUID.randomUUID());
        jdbc.update("delete from ledger_accounts where id = ?", ok);
        UUID txId = UUID.randomUUID();
        jdbc.update("insert into ledger_transactions (id, ref_key, kind, created_at) values (?, ?, 'DISPUTE_OPEN', now())", txId, "pg-test-kind-" + txId);
        PostgresTestSupport.wipeLedger(jdbc);
    }

    @Test
    void paymentsCanBeDisputedOrChargedBackButNotMadeUp() {
        for (String status : new String[] {"DISPUTED", "CHARGED_BACK"}) {
            UUID id = UUID.randomUUID();
            insertPayment(id, status);
            jdbc.update("delete from payments where id = ?", id);
        }
        assertThatThrownBy(() -> insertPayment(UUID.randomUUID(), "HALF_PAID")).hasMessageContaining("payments_status_check");
    }

    @Test
    void aDisputeIsUniquePerProviderIdAndNeverHoldsMoreThanItsAmount() {
        UUID id = UUID.randomUUID();
        insertDispute(id, "dp_pg_" + id, "10.00", "4.00");
        assertThatThrownBy(() -> insertDispute(UUID.randomUUID(), "dp_pg_" + id, "10.00", "4.00")).hasMessageContaining("disputes_provider_id_key");
        assertThatThrownBy(() -> insertDispute(UUID.randomUUID(), "dp_pg_over_" + id, "10.00", "10.01")).hasMessageContaining("disputes_amounts_check");
        assertThatThrownBy(() -> insertDispute(UUID.randomUUID(), "dp_pg_zero_" + id, "0.00", "0.00")).hasMessageContaining("disputes_amounts_check");
        jdbc.update("delete from disputes where provider_id like ?", "dp_pg_%");
    }

    @Test
    void theSameProviderProblemCannotBeRaisedTwice() {
        String ref = "po_pg_" + UUID.randomUUID();
        insertIncident("CONNECT_PAYOUT_FAILED", ref, "OPEN");
        assertThatThrownBy(() -> insertIncident("CONNECT_PAYOUT_FAILED", ref, "OPEN")).hasMessageContaining("finance_incidents_ref_key");
        insertIncident("TRANSFER_REVERSED", ref, "OPEN");        // same provider id under a different type is a different problem
        assertThatThrownBy(() -> insertIncident("MYSTERY", "x_" + ref, "OPEN")).hasMessageContaining("finance_incidents_type_check");
        assertThatThrownBy(() -> insertIncident("REFUND_FAILED", "y_" + ref, "LATER")).hasMessageContaining("finance_incidents_status_check");
        jdbc.update("delete from finance_incidents where provider_ref like ?", "%" + ref);
    }

    @Test
    void aPaymentsAmountMustEqualCommissionPlusRiderShare() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into payments (id, trip_id, customer_id, rider_id, amount, commission_amount, rider_amount, currency,
                                      status, attempts, next_attempt_at, charge_key, created_at)
                values (?, ?, ?, ?, 10.00, 2.00, 7.00, 'USD', 'PENDING', 0, now(), ?, now())""",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .hasMessageContaining("payments_amounts_check");
    }

    // ---------- helpers ----------

    private void post(String ref, String debit, String credit) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ledger_transactions (id, ref_key, kind, created_at) values (?, ?, 'PAYMENT_CAPTURE', now())", id, ref);
        jdbc.update("insert into ledger_entries (id, transaction_id, account_id, direction, amount, created_at) values (?, ?, ?, 'DEBIT', ?::numeric, now())",
                UUID.randomUUID(), id, cash, debit);
        jdbc.update("insert into ledger_entries (id, transaction_id, account_id, direction, amount, created_at) values (?, ?, ?, 'CREDIT', ?::numeric, now())",
                UUID.randomUUID(), id, revenue, credit);
    }

    private void insertPayment(UUID id, String status) {
        jdbc.update("""
                insert into payments (id, trip_id, customer_id, rider_id, amount, commission_amount, rider_amount, currency,
                                      status, attempts, next_attempt_at, charge_key, created_at)
                values (?, ?, ?, ?, 10.00, 2.00, 8.00, 'USD', ?, 0, now(), ?, now())""",
                id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), status, UUID.randomUUID());
    }

    private void insertDispute(UUID id, String providerId, String amount, String held) {
        jdbc.update("""
                insert into disputes (id, provider_id, matched, amount, currency, status, frozen, rider_held_amount, fee_booked, created_at, updated_at)
                values (?, ?, false, ?::numeric, 'USD', 'OPEN', false, ?::numeric, 0, now(), now())""", id, providerId, amount, held);
    }

    private void insertIncident(String type, String ref, String status) {
        jdbc.update("insert into finance_incidents (id, type, provider_ref, status, created_at) values (?, ?, ?, ?, now())",
                UUID.randomUUID(), type, ref, status);
    }

    private void insertPayout(UUID rider, String status) {
        jdbc.update("insert into payouts (id, rider_id, amount, currency, status, attempts, next_attempt_at, created_at) values (?, ?, 5.00, 'USD', ?, 0, now(), now())",
                UUID.randomUUID(), rider, status);
    }
}
