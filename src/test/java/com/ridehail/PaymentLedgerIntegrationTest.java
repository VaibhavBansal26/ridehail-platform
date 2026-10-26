package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ridehail.ledger.AccountType;
import com.ridehail.ledger.LedgerService;
import com.ridehail.ledger.LedgerService.Line;
import com.ridehail.ledger.TxKind;
import com.ridehail.payment.Payment;
import com.ridehail.payment.PaymentService;
import com.ridehail.payment.PaymentStatus;
import com.ridehail.payment.Payout;
import com.ridehail.payment.PayoutService;
import com.ridehail.payment.PayoutStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentLedgerIntegrationTest extends ApiTestBase {

    @Autowired LedgerService ledger;
    @Autowired PaymentService paymentService;
    @Autowired PayoutService payoutService;
    @Autowired TransactionTemplate tx;

    // ---------- capture & ledger ----------

    @Test
    void completedTripIsChargedAndTheFareIsSplitBetweenRiderAndPlatformInBalancedEntries() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Actor customer = customer("Ravi");
        Ride ride = completeRide(customer, rider);
        Payment p = awaitStatus(ride.tripId(), PaymentStatus.CAPTURED);

        BigDecimal commission = ride.fare().multiply(new BigDecimal("0.20")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal riderShare = ride.fare().subtract(commission);
        assertThat(p.getAmount()).isEqualByComparingTo(ride.fare());   // charged exactly the quoted fare
        assertThat(p.getCommissionAmount()).isEqualByComparingTo(commission);
        assertThat(p.getRiderAmount()).isEqualByComparingTo(riderShare);

        assertThat(cash()).isEqualByComparingTo(ride.fare());
        assertThat(riderBalance(rider)).isEqualByComparingTo(riderShare);
        assertThat(balance(AccountType.COMMISSION_REVENUE)).isEqualByComparingTo(commission);

        var trial = ledger.trialBalance();
        assertThat(trial.balanced()).isTrue();
        assertThat(trial.totalDebits()).isEqualByComparingTo(ride.fare());

        JsonNode earnings = fetch("/api/riders/me/earnings", rider);
        assertThat(earnings.get("available").decimalValue()).isEqualByComparingTo(riderShare);
        assertThat(earnings.get("recent")).hasSize(1);
        assertThat(earnings.get("recent").get(0).get("kind").asText()).isEqualTo("PAYMENT_CAPTURE");
    }

    @Test
    void replayingTheCaptureNeverChargesOrPostsTwice() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Ride ride = completeRide(customer("Ravi"), rider);
        Payment p = awaitStatus(ride.tripId(), PaymentStatus.CAPTURED);

        for (int i = 0; i < 3; i++) paymentService.capture(p.getId());
        paymentService.retryDue();

        assertThat(gateway.succeeded("charge")).isEqualTo(1);
        assertThat(riderBalance(rider)).isEqualByComparingTo(p.getRiderAmount());
        assertThat(jdbc.queryForObject("select count(*) from ledger_transactions", Integer.class)).isEqualTo(1);
    }

    @Test
    void gatewayOutageIsRetriedWithBackoffAndTheCustomerIsChargedOnce() throws Exception {
        gateway.failNextCallsTransiently(1);
        Actor rider = onlineRider("Asha", 0.005);
        Ride ride = completeRide(customer("Ravi"), rider);

        Payment first = awaitPayment(ride.tripId(), p -> p.getAttempts() == 1);
        assertThat(first.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(riderBalance(rider)).isEqualByComparingTo("0");     // no earnings until the money is really in

        paymentService.retryDue();                                       // too early: backoff has not elapsed
        assertThat(paymentRepo.findById(first.getId()).orElseThrow().getAttempts()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(31));
        paymentService.retryDue();

        Payment done = awaitStatus(ride.tripId(), PaymentStatus.CAPTURED);
        assertThat(done.getAttempts()).isEqualTo(2);
        assertThat(gateway.succeeded("charge")).isEqualTo(1);
        assertThat(gateway.requests().stream().filter(r -> r.startsWith("charge:")).distinct().count()).isEqualTo(1); // same key both times
        assertThat(riderBalance(rider)).isEqualByComparingTo(done.getRiderAmount());
    }

    @Test
    void whenRetriesRunOutThePaymentNeedsAttentionAndNobodyIsCredited() throws Exception {
        gateway.failNextCallsTransiently(100);
        Actor rider = onlineRider("Asha", 0.005);
        Ride ride = completeRide(customer("Ravi"), rider);
        awaitPayment(ride.tripId(), p -> p.getAttempts() == 1);

        for (int i = 0; i < 6; i++) {
            clock.advance(Duration.ofMinutes(30));
            paymentService.retryDue();
        }

        Payment p = awaitStatus(ride.tripId(), PaymentStatus.REQUIRES_ATTENTION);
        assertThat(p.getAttempts()).isEqualTo(5);
        assertThat(riderBalance(rider)).isEqualByComparingTo("0");
        assertThat(cash()).isEqualByComparingTo("0");
    }

    @Test
    void declinedCardBlocksNewBookingsUntilAnAdminRetriesWithAWorkingCard() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Actor customer = customerWithCard("Ravi", "tok_decline");
        Ride ride = completeRide(customer, rider);
        Payment p = awaitStatus(ride.tripId(), PaymentStatus.REQUIRES_ATTENTION);
        assertThat(p.getFailureReason()).isEqualTo("Card declined");
        assertThat(riderBalance(rider)).isEqualByComparingTo("0");

        // can't ride again while a trip is unpaid
        JsonNode q = quote(customer);
        call(HttpMethod.POST, "/api/trips", customer, "{\"quoteId\":\"" + q.get("quoteId").asText() + "\"}")
                .andExpect(status().isConflict());

        // customer fixes the card, ops retries
        setCard(customer, "tok_ok");
        Actor admin = admin();
        JsonNode retried = body(call(HttpMethod.POST, "/api/admin/payments/" + p.getId() + "/retry", admin, null)
                .andExpect(status().isOk()));
        assertThat(retried.get("status").asText()).isEqualTo("CAPTURED");
        assertThat(riderBalance(rider)).isEqualByComparingTo(p.getRiderAmount());

        // and the customer is back in business
        call(HttpMethod.POST, "/api/trips", customer, "{\"quoteId\":\"" + q.get("quoteId").asText() + "\"}")
                .andExpect(status().isCreated());
    }

    @Test
    void bookingRequiresAPaymentMethodOnFile() throws Exception {
        onlineRider("Asha", 0.005);
        Actor customer = customerWithoutCard("Ravi");
        String quoteId = quote(customer).get("quoteId").asText();

        call(HttpMethod.POST, "/api/trips", customer, "{\"quoteId\":\"" + quoteId + "\"}").andExpect(status().isConflict());
        setCard(customer, "tok_ok");
        call(HttpMethod.POST, "/api/trips", customer, "{\"quoteId\":\"" + quoteId + "\"}").andExpect(status().isCreated());
    }

    @Test
    void refundReturnsTheMoneyAndReversesEarningsAndCommission() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Ride ride = completeRide(customer("Ravi"), rider);
        Payment p = awaitStatus(ride.tripId(), PaymentStatus.CAPTURED);

        JsonNode refunded = body(call(HttpMethod.POST, "/api/admin/payments/" + p.getId() + "/refund", admin(), null)
                .andExpect(status().isOk()));
        assertThat(refunded.get("status").asText()).isEqualTo("REFUNDED");

        assertThat(cash()).isEqualByComparingTo("0");
        assertThat(riderBalance(rider)).isEqualByComparingTo("0");
        assertThat(balance(AccountType.COMMISSION_REVENUE)).isEqualByComparingTo("0");
        assertThat(ledger.trialBalance().balanced()).isTrue();
        assertThat(gateway.succeeded("refund")).isEqualTo(1);

        call(HttpMethod.POST, "/api/admin/payments/" + p.getId() + "/refund", admin(), null).andExpect(status().isConflict());
    }

    // ---------- payouts ----------

    @Test
    void riderWithdrawsEarningsAndTheMoneyLeavesThePlatform() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Ride ride = completeRide(customer("Ravi"), rider);
        Payment p = awaitStatus(ride.tripId(), PaymentStatus.CAPTURED);
        setPayoutAccount(rider, "dest_ok");

        JsonNode payout = body(call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isCreated()));
        assertThat(payout.get("status").asText()).isEqualTo("PAID");
        assertThat(payout.get("amount").decimalValue()).isEqualByComparingTo(p.getRiderAmount());

        assertThat(riderBalance(rider)).isEqualByComparingTo("0");
        assertThat(balance(AccountType.PAYOUTS_IN_TRANSIT)).isEqualByComparingTo("0");
        assertThat(cash()).isEqualByComparingTo(p.getCommissionAmount());   // what is left is the platform's commission
        assertThat(ledger.trialBalance().balanced()).isTrue();

        JsonNode earnings = fetch("/api/riders/me/earnings", rider);
        assertThat(earnings.get("available").decimalValue()).isEqualByComparingTo("0");
        assertThat(fetch("/api/riders/me/payouts", rider)).hasSize(1);
    }

    @Test
    void payoutRulesAreEnforced() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        awaitStatus(completeRide(customer("Ravi"), rider).tripId(), PaymentStatus.CAPTURED);

        call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isConflict());               // no account yet
        setPayoutAccount(rider, "dest_ok");
        call(HttpMethod.POST, "/api/riders/me/payouts", rider, "{\"amount\":0.50}").andExpect(status().isConflict());   // below minimum
        call(HttpMethod.POST, "/api/riders/me/payouts", rider, "{\"amount\":1000.00}").andExpect(status().isConflict()); // more than owed
        call(HttpMethod.POST, "/api/riders/me/payouts", rider, "{\"amount\":-5}").andExpect(status().isBadRequest());
        assertThat(payoutRepo.count()).isZero();
    }

    @Test
    void twoSimultaneousWithdrawalsCannotSpendTheSameBalanceTwice() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        awaitStatus(completeRide(customer("Ravi"), rider).tripId(), PaymentStatus.CAPTURED);
        setPayoutAccount(rider, "dest_ok");

        var pool = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        List<Callable<Payout>> attempts = java.util.Collections.nCopies(4, () -> {
            start.await();
            return payoutService.request(rider.id(), null);
        });
        List<Future<Payout>> futures = attempts.stream().map(pool::submit).toList();
        start.countDown();

        int succeeded = 0;
        for (Future<Payout> f : futures) {
            try { f.get(); succeeded++; } catch (Exception expected) { /* rejected: balance already reserved or spent */ }
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(1);
        assertThat(payoutRepo.count()).isEqualTo(1);
        assertThat(gateway.succeeded("payout")).isEqualTo(1);
        assertThat(riderBalance(rider)).isEqualByComparingTo("0");
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }

    @Test
    void rejectedPayoutGivesTheMoneyBackToTheRider() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Payment p = awaitStatus(completeRide(customer("Ravi"), rider).tripId(), PaymentStatus.CAPTURED);
        setPayoutAccount(rider, "dest_decline_closed");

        JsonNode payout = body(call(HttpMethod.POST, "/api/riders/me/payouts", rider, null));
        assertThat(payout.get("status").asText()).isEqualTo("FAILED");

        assertThat(riderBalance(rider)).isEqualByComparingTo(p.getRiderAmount());
        assertThat(balance(AccountType.PAYOUTS_IN_TRANSIT)).isEqualByComparingTo("0");
        assertThat(cash()).isEqualByComparingTo(p.getAmount());              // nothing left the platform
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }

    @Test
    void payoutHitByAnOutageStaysReservedThenCompletesOnRetry() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Payment p = awaitStatus(completeRide(customer("Ravi"), rider).tripId(), PaymentStatus.CAPTURED);
        setPayoutAccount(rider, "dest_ok");

        gateway.failNextCallsTransiently(1);
        JsonNode pending = body(call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isCreated()));
        assertThat(pending.get("status").asText()).isEqualTo("PENDING");

        // money is parked in transit: not spendable twice, not lost
        assertThat(riderBalance(rider)).isEqualByComparingTo("0");
        assertThat(balance(AccountType.PAYOUTS_IN_TRANSIT)).isEqualByComparingTo(p.getRiderAmount());
        assertThat(fetch("/api/riders/me/earnings", rider).get("payoutsInProgress").decimalValue())
                .isEqualByComparingTo(p.getRiderAmount());
        call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isConflict());

        clock.advance(Duration.ofSeconds(31));
        payoutService.retryDue();

        assertThat(payoutRepo.findAll().get(0).getStatus()).isEqualTo(PayoutStatus.PAID);
        assertThat(balance(AccountType.PAYOUTS_IN_TRANSIT)).isEqualByComparingTo("0");
        assertThat(gateway.succeeded("payout")).isEqualTo(1);
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }

    @Test
    void payoutWithUnknownOutcomeKeepsTheMoneyReservedForAHumanToResolve() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Payment p = awaitStatus(completeRide(customer("Ravi"), rider).tripId(), PaymentStatus.CAPTURED);
        setPayoutAccount(rider, "dest_ok");

        gateway.failNextCallsTransiently(100);
        call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isCreated());
        for (int i = 0; i < 6; i++) {
            clock.advance(Duration.ofMinutes(30));
            payoutService.retryDue();
        }

        assertThat(payoutRepo.findAll().get(0).getStatus()).isEqualTo(PayoutStatus.REQUIRES_ATTENTION);
        // the provider may or may not have paid, so the money must not flow back to the rider
        assertThat(riderBalance(rider)).isEqualByComparingTo("0");
        assertThat(balance(AccountType.PAYOUTS_IN_TRANSIT)).isEqualByComparingTo(p.getRiderAmount());
    }

    // ---------- ledger invariants ----------

    @Test
    void ledgerRefusesUnbalancedMalformedAndOutOfTransactionPostings() {
        var cash = ledger.platform(AccountType.CASH);
        var revenue = ledger.platform(AccountType.COMMISSION_REVENUE);
        BigDecimal ten = new BigDecimal("10.00");

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledger.post("t-unbalanced", TxKind.PAYMENT_CAPTURE, "x",
                List.of(Line.debit(cash, ten), Line.credit(revenue, new BigDecimal("9.99"))))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unbalanced");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledger.post("t-one-line", TxKind.PAYMENT_CAPTURE, "x",
                List.of(Line.debit(cash, ten)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledger.post("t-negative", TxKind.PAYMENT_CAPTURE, "x",
                List.of(Line.debit(cash, new BigDecimal("-10.00")), Line.credit(revenue, new BigDecimal("-10.00"))))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledger.post("t-fraction", TxKind.PAYMENT_CAPTURE, "x",
                List.of(Line.debit(cash, new BigDecimal("1.005")), Line.credit(revenue, new BigDecimal("1.005"))))))
                .isInstanceOf(IllegalArgumentException.class);
        // must be part of the caller's transaction, never auto-committed on its own
        assertThatThrownBy(() -> ledger.post("t-no-tx", TxKind.PAYMENT_CAPTURE, "x",
                List.of(Line.debit(cash, ten), Line.credit(revenue, ten))))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);

        assertThat(jdbc.queryForObject("select count(*) from ledger_entries", Integer.class)).isZero();
    }

    @Test
    void postingTheSameReferenceTwiceIsANoOp() {
        var cash = ledger.platform(AccountType.CASH);
        var revenue = ledger.platform(AccountType.COMMISSION_REVENUE);
        BigDecimal ten = new BigDecimal("10.00");

        for (int i = 0; i < 3; i++) {
            tx.executeWithoutResult(s -> ledger.post("same-ref", TxKind.PAYMENT_CAPTURE, "x",
                    List.of(Line.debit(cash, ten), Line.credit(revenue, ten))));
        }
        assertThat(cash()).isEqualByComparingTo("10.00");
        assertThat(jdbc.queryForObject("select count(*) from ledger_entries", Integer.class)).isEqualTo(2);
    }

    // ---------- access control ----------

    @Test
    void financeEndpointsAreRoleProtected() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Actor customer = customer("Ravi");
        Actor admin = admin();

        call(HttpMethod.GET, "/api/admin/payments", rider, null).andExpect(status().isForbidden());
        call(HttpMethod.GET, "/api/admin/ledger/trial-balance", customer, null).andExpect(status().isForbidden());
        call(HttpMethod.POST, "/api/admin/payments/" + UUID.randomUUID() + "/refund", customer, null).andExpect(status().isForbidden());
        call(HttpMethod.GET, "/api/riders/me/earnings", customer, null).andExpect(status().isForbidden());
        call(HttpMethod.POST, "/api/riders/me/payouts", customer, null).andExpect(status().isForbidden());
        call(HttpMethod.PUT, "/api/customers/me/payment-method", rider, "{\"token\":\"tok_ok\"}").andExpect(status().isForbidden());
        callWithToken(HttpMethod.GET, "/api/admin/ledger/trial-balance", null, null).andExpect(status().isUnauthorized());

        JsonNode trial = body(call(HttpMethod.GET, "/api/admin/ledger/trial-balance", admin, null).andExpect(status().isOk()));
        assertThat(trial.get("balanced").asBoolean()).isTrue();
        call(HttpMethod.GET, "/api/admin/payments?status=CAPTURED", admin, null).andExpect(status().isOk());
        call(HttpMethod.GET, "/api/admin/payouts", admin, null).andExpect(status().isOk());
    }

    // ---------- helpers ----------

    private BigDecimal cash() { return balance(AccountType.CASH); }

    private BigDecimal balance(AccountType type) { return ledger.balance(ledger.platform(type)); }

    private BigDecimal riderBalance(Actor rider) { return ledger.balance(ledger.riderAccount(rider.id())); }

    private Payment awaitStatus(UUID tripId, PaymentStatus status) {
        return awaitPayment(tripId, p -> p.getStatus() == status);
    }

    private Payment awaitPayment(UUID tripId, java.util.function.Predicate<Payment> condition) {
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                .until(() -> paymentRepo.findByTripId(tripId).filter(condition).isPresent());
        return paymentRepo.findByTripId(tripId).orElseThrow();
    }
}
