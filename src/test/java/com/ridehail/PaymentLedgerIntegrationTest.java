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

        for (int i = 0; i < 10; i++) {
            clock.advance(Duration.ofMinutes(30));
            paymentService.retryDue();
        }

        Payment p = awaitStatus(ride.tripId(), PaymentStatus.REQUIRES_ATTENTION);
        assertThat(p.getAttempts()).isEqualTo(8);
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
        for (int i = 0; i < 10; i++) {
            clock.advance(Duration.ofMinutes(30));
            payoutService.retryDue();
        }

        assertThat(payoutRepo.findAll().get(0).getStatus()).isEqualTo(PayoutStatus.REQUIRES_ATTENTION);
        // the provider may or may not have paid, so the money must not flow back to the rider
        assertThat(riderBalance(rider)).isEqualByComparingTo("0");
        assertThat(balance(AccountType.PAYOUTS_IN_TRANSIT)).isEqualByComparingTo(p.getRiderAmount());
    }

    // ---------- payouts that have to wait for the provider's funds ----------

    @Test
    void aPayoutWaitsForSettledProviderFundsInsteadOfBeingAttemptedAndRefused() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Payment p = awaitStatus(completeRide(customer("Ravi"), rider).tripId(), PaymentStatus.CAPTURED);
        setPayoutAccount(rider, "dest_ok");
        gateway.setAvailableBalance(BigDecimal.ZERO);          // the card money is still pending at the provider

        JsonNode payout = body(call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isCreated()));

        assertThat(payout.get("status").asText()).isEqualTo("AWAITING_FUNDS");
        assertThat(payout.get("waitingSince").isNull()).isFalse();
        assertThat(gateway.requests().stream().filter(r -> r.startsWith("payout:"))).isEmpty();   // nothing was even sent
        assertThat(payoutRepo.findAll().get(0).getAttempts()).isZero();                             // and no error retry was used
        // the money is parked, not lost and not spendable twice
        assertThat(riderBalance(rider)).isEqualByComparingTo("0");
        assertThat(balance(AccountType.PAYOUTS_IN_TRANSIT)).isEqualByComparingTo(p.getRiderAmount());
        assertThat(fetch("/api/riders/me/earnings", rider).get("payoutsInProgress").decimalValue()).isEqualByComparingTo(p.getRiderAmount());
        call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isConflict());

        // polling before the poll interval does nothing; at the interval it looks again and keeps waiting
        clock.advance(Duration.ofMinutes(10));
        payoutService.retryDue();
        assertThat(payoutRepo.findAll().get(0).getNextAttemptAt()).isAfter(java.time.Instant.now(clock));
        clock.advance(Duration.ofMinutes(6));
        payoutService.retryDue();
        assertThat(payoutRepo.findAll().get(0).getStatus()).isEqualTo(PayoutStatus.AWAITING_FUNDS);

        // the funds settle: it goes out at the next look
        gateway.setAvailableBalance(new BigDecimal("100.00"));
        clock.advance(Duration.ofMinutes(16));
        payoutService.retryDue();

        Payout done = payoutRepo.findAll().get(0);
        assertThat(done.getStatus()).isEqualTo(PayoutStatus.PAID);
        assertThat(done.getAttempts()).isEqualTo(1);
        assertThat(balance(AccountType.PAYOUTS_IN_TRANSIT)).isEqualByComparingTo("0");
        assertThat(cash()).isEqualByComparingTo(p.getCommissionAmount());
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }

    @Test
    void aPayoutKeepsWaitingForDaysNotMinutes() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Payment p = awaitStatus(completeRide(customer("Ravi"), rider).tripId(), PaymentStatus.CAPTURED);
        setPayoutAccount(rider, "dest_ok");
        gateway.setAvailableBalance(BigDecimal.ZERO);
        call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isCreated());

        for (int hour = 0; hour < 48; hour++) {                  // two days of polling
            clock.advance(Duration.ofHours(1));
            payoutService.retryDue();
        }

        Payout waiting = payoutRepo.findAll().get(0);
        assertThat(waiting.getStatus()).isEqualTo(PayoutStatus.AWAITING_FUNDS);   // the old behaviour parked it after ~8 minutes
        assertThat(waiting.getAttempts()).isZero();
        assertThat(balance(AccountType.PAYOUTS_IN_TRANSIT)).isEqualByComparingTo(p.getRiderAmount());

        gateway.setAvailableBalance(new BigDecimal("50.00"));
        clock.advance(Duration.ofHours(1));
        payoutService.retryDue();
        assertThat(payoutRepo.findAll().get(0).getStatus()).isEqualTo(PayoutStatus.PAID);
    }

    @Test
    void ifTheProviderRefusesForLackOfFundsDespiteAGoodBalanceReadItJustWaits() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        awaitStatus(completeRide(customer("Ravi"), rider).tripId(), PaymentStatus.CAPTURED);
        setPayoutAccount(rider, "dest_ok");
        gateway.refuseNextPayoutsForFunds(1);                   // another payout took the money between our check and the transfer

        JsonNode payout = body(call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isCreated()));
        assertThat(payout.get("status").asText()).isEqualTo("AWAITING_FUNDS");
        assertThat(payoutRepo.findAll().get(0).getAttempts()).isZero();   // a refusal for funds is not a failed attempt

        clock.advance(Duration.ofMinutes(16));
        payoutService.retryDue();
        assertThat(payoutRepo.findAll().get(0).getStatus()).isEqualTo(PayoutStatus.PAID);
        assertThat(gateway.succeeded("payout")).isEqualTo(1);
    }

    @Test
    void waitingPayoutsAreServedOldestFirstAsFundsArrive() throws Exception {
        Actor first = onlineRider("First", 0.005);
        Payment p1 = awaitStatus(completeRide(customer("C1"), first).tripId(), PaymentStatus.CAPTURED);
        Actor second = onlineRider("Second", 0.001);              // closer, so it gets the next ride
        Payment p2 = awaitStatus(completeRide(customer("C2"), second).tripId(), PaymentStatus.CAPTURED);
        setPayoutAccount(first, "dest_ok");
        setPayoutAccount(second, "dest_ok");
        gateway.setAvailableBalance(BigDecimal.ZERO);

        call(HttpMethod.POST, "/api/riders/me/payouts", first, null).andExpect(status().isCreated());
        clock.advance(Duration.ofSeconds(5));
        call(HttpMethod.POST, "/api/riders/me/payouts", second, null).andExpect(status().isCreated());

        gateway.setAvailableBalance(p1.getRiderAmount().add(new BigDecimal("0.01")));   // enough for exactly one
        clock.advance(Duration.ofMinutes(16));
        payoutService.retryDue();

        assertThat(payoutFor(first).getStatus()).isEqualTo(PayoutStatus.PAID);               // waited longest
        assertThat(payoutFor(second).getStatus()).isEqualTo(PayoutStatus.AWAITING_FUNDS);

        gateway.setAvailableBalance(p2.getRiderAmount());
        clock.advance(Duration.ofMinutes(16));
        payoutService.retryDue();
        assertThat(payoutFor(second).getStatus()).isEqualTo(PayoutStatus.PAID);
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }

    @Test
    void whenFundsNeverComeAndNothingWasEverSentTheMoneyGoesSafelyBackToTheRider() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Payment p = awaitStatus(completeRide(customer("Ravi"), rider).tripId(), PaymentStatus.CAPTURED);
        setPayoutAccount(rider, "dest_ok");
        gateway.setAvailableBalance(BigDecimal.ZERO);
        call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isCreated());

        for (int day = 0; day < 12; day++) {                      // the waiting limit is 10 days
            clock.advance(Duration.ofDays(1));
            payoutService.retryDue();
        }

        Payout gaveUp = payoutRepo.findAll().get(0);
        assertThat(gaveUp.getStatus()).isEqualTo(PayoutStatus.FAILED);
        assertThat(gaveUp.getFailureReason()).contains("returned to your balance");
        assertThat(gateway.requests().stream().filter(r -> r.startsWith("payout:"))).isEmpty();   // so giving the money back is safe
        assertThat(riderBalance(rider)).isEqualByComparingTo(p.getRiderAmount());
        assertThat(balance(AccountType.PAYOUTS_IN_TRANSIT)).isEqualByComparingTo("0");
        assertThat(cash()).isEqualByComparingTo(p.getAmount());
        assertThat(ledger.trialBalance().balanced()).isTrue();

        // and the rider can ask again once there is money
        gateway.setAvailableBalance(new BigDecimal("100.00"));
        call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isCreated());
        assertThat(gateway.succeeded("payout")).isEqualTo(1);
    }

    @Test
    void whenAnEarlierCallHadAnUnknownOutcomeTheMoneyStaysReservedEvenAfterTheWaitingLimit() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Payment p = awaitStatus(completeRide(customer("Ravi"), rider).tripId(), PaymentStatus.CAPTURED);
        setPayoutAccount(rider, "dest_ok");
        gateway.failNextCallsTransiently(1);                      // a timeout: did the transfer go out or not? unknown
        call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isCreated());
        assertThat(payoutRepo.findAll().get(0).getAttempts()).isEqualTo(1);

        gateway.setAvailableBalance(BigDecimal.ZERO);
        clock.advance(Duration.ofSeconds(31));
        payoutService.retryDue();
        assertThat(payoutRepo.findAll().get(0).getStatus()).isEqualTo(PayoutStatus.AWAITING_FUNDS);
        for (int day = 0; day < 12; day++) {
            clock.advance(Duration.ofDays(1));
            payoutService.retryDue();
        }

        assertThat(payoutRepo.findAll().get(0).getStatus()).isEqualTo(PayoutStatus.REQUIRES_ATTENTION);
        assertThat(riderBalance(rider)).isEqualByComparingTo("0");                                   // NOT returned: it may have been sent
        assertThat(balance(AccountType.PAYOUTS_IN_TRANSIT)).isEqualByComparingTo(p.getRiderAmount());
    }

    @Test
    void opsCanSeeSettledFundsAgainstWhatRidersAreWaitingFor() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Payment p = awaitStatus(completeRide(customer("Ravi"), rider).tripId(), PaymentStatus.CAPTURED);
        setPayoutAccount(rider, "dest_ok");
        gateway.setAvailableBalance(new BigDecimal("3.00"));
        call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isCreated());

        JsonNode funding = fetch("/api/admin/payouts/funding", admin());
        assertThat(funding.get("providerAvailable").decimalValue()).isEqualByComparingTo("3.00");
        assertThat(funding.get("awaitingTotal").decimalValue()).isEqualByComparingTo(p.getRiderAmount());
        assertThat(funding.get("awaitingCount").asInt()).isEqualTo(1);
        call(HttpMethod.GET, "/api/admin/payouts/funding", rider, null).andExpect(status().isForbidden());
        assertThat(fetch("/api/admin/payouts?status=AWAITING_FUNDS", admin()).get("total").asInt()).isEqualTo(1);
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

    private Payout payoutFor(Actor rider) {
        return payoutRepo.findTop20ByRiderIdOrderByCreatedAtDesc(rider.id()).get(0);
    }

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
