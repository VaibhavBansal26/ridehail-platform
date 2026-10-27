package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ridehail.dispute.Dispute;
import com.ridehail.dispute.DisputeNotice;
import com.ridehail.dispute.DisputeNotice.State;
import com.ridehail.dispute.DisputeRepository;
import com.ridehail.dispute.DisputeService;
import com.ridehail.dispute.DisputeStatus;
import com.ridehail.dispute.FinanceIncidentRepository;
import com.ridehail.dispute.IncidentService;
import com.ridehail.dispute.IncidentType;
import com.ridehail.ledger.AccountType;
import com.ridehail.ledger.LedgerService;
import com.ridehail.payment.Payment;
import com.ridehail.payment.PaymentStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/**
 * Disputes (chargebacks) and the ledger: what moves, what is frozen, who bears a loss, and that none of it can be applied
 * twice or in the wrong order. Numbers in these tests: a 9.99 fare = 7.99 to the rider + 2.00 commission.
 */
class DisputeIntegrationTest extends ApiTestBase {

    @Autowired DisputeService disputeService;
    @Autowired DisputeRepository disputeRepo;
    @Autowired IncidentService incidentService;
    @Autowired FinanceIncidentRepository incidentRepo;
    @Autowired LedgerService ledger;

    record Setup(Actor rider, Actor customer, Payment payment) {}

    // ---------------------------------------------------------------- opening a chargeback

    @Test
    void aChargebackTakesTheContestedMoneyOutOfCashAndFreezesTheRidersShare() throws Exception {
        Setup s = captured();
        Payment p = s.payment();

        disputeService.apply(notice("dp_1", p, State.OPEN, p.getAmount()));

        Dispute d = disputeRepo.findByProviderId("dp_1").orElseThrow();
        assertThat(d.getStatus()).isEqualTo(DisputeStatus.OPEN);
        assertThat(d.isFrozen()).isTrue();
        assertThat(d.getRiderHeldAmount()).isEqualByComparingTo(p.getRiderAmount());
        assertThat(paymentStatus(p)).isEqualTo(PaymentStatus.DISPUTED);

        assertThat(cash()).isEqualByComparingTo("0");                         // Stripe pulled it, so cash no longer has it
        assertThat(bal(AccountType.DISPUTES_IN_PROGRESS)).isEqualByComparingTo(p.getAmount());   // but we may get it back
        assertThat(riderBalance(s.rider())).isEqualByComparingTo("0");       // the rider's share is no longer payable ...
        assertThat(held(s.rider())).isEqualByComparingTo(p.getRiderAmount());    // ... it is frozen
        assertThat(bal(AccountType.COMMISSION_REVENUE)).isEqualByComparingTo(p.getCommissionAmount());
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }

    @Test
    void whileFrozenTheRiderSeesItAsOnHoldCannotWithdrawItAndTheCustomerCannotBeRefundedOrRebook() throws Exception {
        Setup s = captured();
        disputeService.apply(notice("dp_1", s.payment(), State.OPEN, s.payment().getAmount()));

        JsonNode earnings = fetch("/api/riders/me/earnings", s.rider());
        assertThat(earnings.get("available").decimalValue()).isEqualByComparingTo("0");
        assertThat(earnings.get("onHold").decimalValue()).isEqualByComparingTo(s.payment().getRiderAmount());

        setPayoutAccount(s.rider(), "dest_ok");
        call(HttpMethod.POST, "/api/riders/me/payouts", s.rider(), null).andExpect(status().isConflict());   // nothing payable

        // a disputed charge cannot also be refunded: that would lose the money twice
        call(HttpMethod.POST, "/api/admin/payments/" + s.payment().getId() + "/refund", admin(), null).andExpect(status().isConflict());

        // and the customer who disputed it is paused while it is open
        String quoteId = quote(s.customer()).get("quoteId").asText();
        call(HttpMethod.POST, "/api/trips", s.customer(), "{\"quoteId\":\"" + quoteId + "\"}").andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------- outcomes

    @Test
    void winningRestoresTheMoneyTheRidersEarningsAndTheCustomersAccess() throws Exception {
        Setup s = captured();
        Payment p = s.payment();
        disputeService.apply(notice("dp_1", p, State.OPEN, p.getAmount()));

        disputeService.apply(notice("dp_1", p, State.WON, p.getAmount()));

        assertThat(disputeRepo.findByProviderId("dp_1").orElseThrow().getStatus()).isEqualTo(DisputeStatus.WON);
        assertThat(paymentStatus(p)).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(cash()).isEqualByComparingTo(p.getAmount());
        assertThat(bal(AccountType.DISPUTES_IN_PROGRESS)).isEqualByComparingTo("0");
        assertThat(riderBalance(s.rider())).isEqualByComparingTo(p.getRiderAmount());
        assertThat(held(s.rider())).isEqualByComparingTo("0");
        assertThat(bal(AccountType.DISPUTE_LOSSES)).isEqualByComparingTo("0");
        assertThat(ledger.trialBalance().balanced()).isTrue();

        setPayoutAccount(s.rider(), "dest_ok");
        call(HttpMethod.POST, "/api/riders/me/payouts", s.rider(), null).andExpect(status().isCreated());   // payable again
        String quoteId = quote(s.customer()).get("quoteId").asText();
        call(HttpMethod.POST, "/api/trips", s.customer(), "{\"quoteId\":\"" + quoteId + "\"}").andExpect(status().isCreated());
    }

    @Test
    void losingMakesThePlatformPayTheLossByDefaultAndTheRiderKeepsTheirEarnings() throws Exception {
        Setup s = captured();
        Payment p = s.payment();
        disputeService.apply(notice("dp_1", p, State.OPEN, p.getAmount()));

        disputeService.apply(notice("dp_1", p, State.LOST, p.getAmount()));

        assertThat(disputeRepo.findByProviderId("dp_1").orElseThrow().getStatus()).isEqualTo(DisputeStatus.LOST);
        assertThat(paymentStatus(p)).isEqualTo(PaymentStatus.CHARGED_BACK);
        assertThat(cash()).isEqualByComparingTo("0");                                            // the money is gone
        assertThat(bal(AccountType.DISPUTES_IN_PROGRESS)).isEqualByComparingTo("0");
        assertThat(bal(AccountType.DISPUTE_LOSSES)).isEqualByComparingTo(p.getAmount());          // the platform took the whole hit
        assertThat(riderBalance(s.rider())).isEqualByComparingTo(p.getRiderAmount());           // the rider provided the ride: they keep it
        assertThat(held(s.rider())).isEqualByComparingTo("0");
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }

    @Test
    void aRiderWhoAlreadyWithdrewHasADebtWhileFrozenThatDisappearsOnAWin() throws Exception {
        Setup s = captured();
        Payment p = s.payment();
        setPayoutAccount(s.rider(), "dest_ok");
        call(HttpMethod.POST, "/api/riders/me/payouts", s.rider(), null).andExpect(status().isCreated());   // earnings already paid out
        assertThat(riderBalance(s.rider())).isEqualByComparingTo("0");

        disputeService.apply(notice("dp_1", p, State.OPEN, p.getAmount()));
        assertThat(riderBalance(s.rider())).isEqualByComparingTo(p.getRiderAmount().negate());   // they now owe the frozen amount
        assertThat(held(s.rider())).isEqualByComparingTo(p.getRiderAmount());                    // which nets to nothing against the hold

        disputeService.apply(notice("dp_1", p, State.WON, p.getAmount()));
        assertThat(riderBalance(s.rider())).isEqualByComparingTo("0");                            // back to square one
        assertThat(held(s.rider())).isEqualByComparingTo("0");
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }

    @Test
    void aLostDisputeAfterTheRiderWithdrewIsAbsorbedByThePlatformUnderTheDefaultPolicy() throws Exception {
        Setup s = captured();
        Payment p = s.payment();
        setPayoutAccount(s.rider(), "dest_ok");
        call(HttpMethod.POST, "/api/riders/me/payouts", s.rider(), null).andExpect(status().isCreated());

        disputeService.apply(notice("dp_1", p, State.OPEN, p.getAmount()));
        disputeService.apply(notice("dp_1", p, State.LOST, p.getAmount()));

        assertThat(riderBalance(s.rider())).isEqualByComparingTo("0");                // no debt: the platform pays
        assertThat(bal(AccountType.DISPUTE_LOSSES)).isEqualByComparingTo(p.getAmount());
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }

    // ---------------------------------------------------------------- partial disputes and fees

    @Test
    void aPartialDisputeFreezesOnlyAProportionalShareOfTheRidersEarnings() throws Exception {
        Setup s = captured();
        Payment p = s.payment();                                                       // 9.99 total, rider 7.99
        BigDecimal disputed = new BigDecimal("5.00");

        disputeService.apply(notice("dp_1", p, State.OPEN, disputed));

        assertThat(disputeRepo.findByProviderId("dp_1").orElseThrow().getRiderHeldAmount()).isEqualByComparingTo("4.00");   // 5.00 x 7.99 / 9.99
        assertThat(bal(AccountType.DISPUTES_IN_PROGRESS)).isEqualByComparingTo("5.00");
        assertThat(cash()).isEqualByComparingTo(p.getAmount().subtract(disputed));
        assertThat(riderBalance(s.rider())).isEqualByComparingTo(p.getRiderAmount().subtract(new BigDecimal("4.00")));
        assertThat(held(s.rider())).isEqualByComparingTo("4.00");

        disputeService.apply(notice("dp_1", p, State.WON, disputed));
        assertThat(cash()).isEqualByComparingTo(p.getAmount());
        assertThat(riderBalance(s.rider())).isEqualByComparingTo(p.getRiderAmount());
        assertThat(held(s.rider())).isEqualByComparingTo("0");
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }

    @Test
    void theProvidersDisputeFeeIsBookedAndReversedIfTheProviderRefundsIt() throws Exception {
        Setup s = captured();
        Payment p = s.payment();
        BigDecimal fee = new BigDecimal("15.00");

        disputeService.apply(notice("dp_1", p, State.OPEN, p.getAmount(), fee));
        assertThat(bal(AccountType.DISPUTE_FEES)).isEqualByComparingTo("15.00");
        assertThat(cash()).isEqualByComparingTo("-15.00");                              // Stripe took the fee from our balance too

        disputeService.apply(notice("dp_1", p, State.OPEN, p.getAmount(), fee));         // the same news again: not charged twice
        assertThat(bal(AccountType.DISPUTE_FEES)).isEqualByComparingTo("15.00");

        disputeService.apply(notice("dp_1", p, State.WON, p.getAmount(), BigDecimal.ZERO));   // won, and Stripe returned the fee
        assertThat(bal(AccountType.DISPUTE_FEES)).isEqualByComparingTo("0");
        assertThat(cash()).isEqualByComparingTo(p.getAmount());
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }

    @Test
    void aLostDisputeKeepsItsFee() throws Exception {
        Setup s = captured();
        Payment p = s.payment();
        disputeService.apply(notice("dp_1", p, State.OPEN, p.getAmount(), new BigDecimal("15.00")));
        disputeService.apply(notice("dp_1", p, State.LOST, p.getAmount(), new BigDecimal("15.00")));

        assertThat(bal(AccountType.DISPUTE_FEES)).isEqualByComparingTo("15.00");
        assertThat(cash()).isEqualByComparingTo("-15.00");
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }

    // ---------------------------------------------------------------- events: duplicates and disorder

    @Test
    void theSameNewsDeliveredTwiceChangesNothingTheSecondTime() throws Exception {
        Setup s = captured();
        Payment p = s.payment();

        for (int i = 0; i < 3; i++) disputeService.apply(notice("dp_1", p, State.OPEN, p.getAmount()));
        for (int i = 0; i < 3; i++) disputeService.apply(notice("dp_1", p, State.WON, p.getAmount()));

        assertThat(jdbc.queryForObject("select count(*) from ledger_transactions where ref_key like 'DISPUTE_%'", Integer.class)).isEqualTo(2);
        assertThat(disputeRepo.count()).isEqualTo(1);
        assertThat(cash()).isEqualByComparingTo(p.getAmount());
        assertThat(riderBalance(s.rider())).isEqualByComparingTo(p.getRiderAmount());
    }

    @Test
    void aDecisionThatArrivesBeforeTheOpeningEventStillEndsConsistent() throws Exception {
        Setup s = captured();
        Payment p = s.payment();

        disputeService.apply(notice("dp_1", p, State.WON, p.getAmount()));              // "created" was lost or late

        assertThat(disputeRepo.findByProviderId("dp_1").orElseThrow().getStatus()).isEqualTo(DisputeStatus.WON);
        assertThat(cash()).isEqualByComparingTo(p.getAmount());
        assertThat(bal(AccountType.DISPUTES_IN_PROGRESS)).isEqualByComparingTo("0");
        assertThat(riderBalance(s.rider())).isEqualByComparingTo(p.getRiderAmount());
        assertThat(held(s.rider())).isEqualByComparingTo("0");
        assertThat(paymentStatus(p)).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }

    @Test
    void aLateOlderEventNeverUndoesADecidedDispute() throws Exception {
        Setup s = captured();
        Payment p = s.payment();
        disputeService.apply(notice("dp_1", p, State.OPEN, p.getAmount()));
        disputeService.apply(notice("dp_1", p, State.WON, p.getAmount()));

        disputeService.apply(notice("dp_1", p, State.OPEN, p.getAmount()));              // stale "updated" arriving late
        disputeService.apply(notice("dp_1", p, State.LOST, p.getAmount()));              // and a contradiction

        assertThat(disputeRepo.findByProviderId("dp_1").orElseThrow().getStatus()).isEqualTo(DisputeStatus.WON);
        assertThat(paymentStatus(p)).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(cash()).isEqualByComparingTo(p.getAmount());
        assertThat(bal(AccountType.DISPUTE_LOSSES)).isEqualByComparingTo("0");
    }

    // ---------------------------------------------------------------- inquiries and things we cannot account for

    @Test
    void anInquiryIsRecordedButMovesNoMoneyUntilItBecomesAChargeback() throws Exception {
        Setup s = captured();
        Payment p = s.payment();

        disputeService.apply(notice("dp_1", p, State.INQUIRY, p.getAmount()));
        assertThat(disputeRepo.findByProviderId("dp_1").orElseThrow().getStatus()).isEqualTo(DisputeStatus.INQUIRY);
        assertThat(paymentStatus(p)).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(cash()).isEqualByComparingTo(p.getAmount());
        assertThat(held(s.rider())).isEqualByComparingTo("0");
        String quoteId = quote(s.customer()).get("quoteId").asText();
        call(HttpMethod.POST, "/api/trips", s.customer(), "{\"quoteId\":\"" + quoteId + "\"}").andExpect(status().isCreated());   // not paused

        disputeService.apply(notice("dp_1", p, State.OPEN, p.getAmount()));              // it escalated
        assertThat(held(s.rider())).isEqualByComparingTo(p.getRiderAmount());
        assertThat(cash()).isEqualByComparingTo("0");
    }

    @Test
    void anInquiryThatIsClosedLeavesNoTraceInTheLedger() throws Exception {
        Setup s = captured();
        Payment p = s.payment();
        disputeService.apply(notice("dp_1", p, State.INQUIRY, p.getAmount()));
        disputeService.apply(notice("dp_1", p, State.CLOSED, p.getAmount()));

        assertThat(disputeRepo.findByProviderId("dp_1").orElseThrow().getStatus()).isEqualTo(DisputeStatus.CLOSED);
        assertThat(jdbc.queryForObject("select count(*) from ledger_transactions where ref_key like 'DISPUTE_%'", Integer.class)).isZero();
        assertThat(cash()).isEqualByComparingTo(p.getAmount());
    }

    @Test
    void aDisputeForAChargeWeDoNotRecognizeIsKeptForAPersonWithNoLedgerEffect() throws Exception {
        captured();
        var notice = new DisputeNotice("dp_x", "pi_unknown", new BigDecimal("12.00"), "USD", State.OPEN, "needs_response",
                "fraudulent", Instant.now(clock).plus(Duration.ofDays(7)), BigDecimal.ZERO, "https://example.test/dp_x");

        Dispute d = disputeService.apply(notice);

        assertThat(d.isMatched()).isFalse();
        assertThat(d.getStatus()).isEqualTo(DisputeStatus.OPEN);                         // visible, with its deadline
        assertThat(d.getLedgerNote()).contains("pi_unknown");
        assertThat(jdbc.queryForObject("select count(*) from ledger_transactions where ref_key like 'DISPUTE_%'", Integer.class)).isZero();
        assertThat(ledger.trialBalance().balanced()).isTrue();
        JsonNode attention = fetch("/api/admin/finance/attention", admin());
        assertThat(attention.get("needsAction").asBoolean()).isTrue();
        assertThat(attention.get("disputes").get("open").asInt()).isEqualTo(1);
    }

    @Test
    void aDisputeOnAPaymentThatNeverSettledIsNotAccountedForBecauseThereIsNoMoneyToFreeze() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Actor customer = customerWithCard("Ravi", "tok_decline");
        Ride ride = completeRide(customer, rider);
        await().atMost(Duration.ofSeconds(5)).until(() ->
                paymentRepo.findByTripId(ride.tripId()).map(Payment::getStatus).orElse(null) == PaymentStatus.REQUIRES_ATTENTION);
        Payment p = paymentRepo.findByTripId(ride.tripId()).orElseThrow();

        Dispute d = disputeService.apply(new DisputeNotice("dp_y", "ch_" + p.getChargeKey(), p.getAmount(), "USD", State.OPEN,
                "needs_response", "fraudulent", null, BigDecimal.ZERO, null));

        assertThat(d.isMatched()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from ledger_transactions where ref_key like 'DISPUTE_%'", Integer.class)).isZero();
    }

    @Test
    void aDisputeOnAnAlreadyRefundedPaymentFreezesNothingOfTheRidersButStillMovesTheMoney() throws Exception {
        Setup s = captured();
        Payment p = s.payment();
        call(HttpMethod.POST, "/api/admin/payments/" + p.getId() + "/refund", admin(), null).andExpect(status().isOk());
        assertThat(cash()).isEqualByComparingTo("0");                                     // the refund already returned the money

        disputeService.apply(notice("dp_1", p, State.OPEN, p.getAmount()));               // the customer disputed it anyway

        assertThat(disputeRepo.findByProviderId("dp_1").orElseThrow().getRiderHeldAmount()).isEqualByComparingTo("0");
        assertThat(held(s.rider())).isEqualByComparingTo("0");                            // their earnings were already reversed
        assertThat(paymentStatus(p)).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(cash()).isEqualByComparingTo(p.getAmount().negate());                  // Stripe took it a second time

        disputeService.apply(notice("dp_1", p, State.LOST, p.getAmount()));
        assertThat(bal(AccountType.DISPUTE_LOSSES)).isEqualByComparingTo(p.getAmount());
        assertThat(riderBalance(s.rider())).isEqualByComparingTo("0");
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }

    // ---------------------------------------------------------------- what admins see

    @Test
    void adminsSeeDisputesWithDeadlinesAndAnAtAGlanceSummaryOfWhatNeedsAction() throws Exception {
        Setup s = captured();
        Payment p = s.payment();
        Instant overdueBy = Instant.now(clock).minus(Duration.ofDays(1));
        disputeService.apply(new DisputeNotice("dp_1", p.getGatewayReference(), p.getAmount(), "USD", State.OPEN, "needs_response",
                "fraudulent", overdueBy, BigDecimal.ZERO, "https://dashboard.stripe.com/test/disputes/dp_1"));
        Actor admin = admin();

        JsonNode list = fetch("/api/admin/disputes?status=OPEN", admin);
        assertThat(list.get("total").asInt()).isEqualTo(1);
        JsonNode view = list.get("items").get(0);
        assertThat(view.get("providerId").asText()).isEqualTo("dp_1");
        assertThat(view.get("matched").asBoolean()).isTrue();
        assertThat(view.get("riderHeldAmount").decimalValue()).isEqualByComparingTo(p.getRiderAmount());
        assertThat(view.get("evidenceOverdue").asBoolean()).isTrue();
        assertThat(view.get("providerUrl").asText()).endsWith("/disputes/dp_1");
        assertThat(fetch("/api/admin/disputes?status=WON", admin).get("total").asInt()).isZero();
        assertThat(fetch("/api/admin/disputes/" + view.get("id").asText(), admin).get("tripId").asText()).isEqualTo(p.getTripId().toString());

        JsonNode attention = fetch("/api/admin/finance/attention", admin);
        assertThat(attention.get("needsAction").asBoolean()).isTrue();
        assertThat(attention.get("disputes").get("open").asInt()).isEqualTo(1);
        assertThat(attention.get("disputes").get("openAmount").decimalValue()).isEqualByComparingTo(p.getAmount());
        assertThat(attention.get("disputes").get("overdue").asInt()).isEqualTo(1);
        assertThat(attention.get("riderFundsOnHold").decimalValue()).isEqualByComparingTo(p.getRiderAmount());

        disputeService.apply(notice("dp_1", p, State.WON, p.getAmount()));              // decided: nothing left to do
        JsonNode calm = fetch("/api/admin/finance/attention", admin);
        assertThat(calm.get("needsAction").asBoolean()).isFalse();
        assertThat(calm.get("riderFundsOnHold").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void onlyAdminsCanSeeDisputesAndIncidents() throws Exception {
        Setup s = captured();
        for (String url : new String[] {"/api/admin/disputes", "/api/admin/incidents", "/api/admin/finance/attention"}) {
            call(HttpMethod.GET, url, s.rider(), null).andExpect(status().isForbidden());
            call(HttpMethod.GET, url, s.customer(), null).andExpect(status().isForbidden());
            callWithToken(HttpMethod.GET, url, null, null).andExpect(status().isUnauthorized());
        }
        call(HttpMethod.POST, "/api/admin/incidents/" + UUID.randomUUID() + "/resolve", s.rider(), "{}").andExpect(status().isForbidden());
        call(HttpMethod.GET, "/api/admin/disputes/" + UUID.randomUUID(), admin(), null).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- incidents (failed payouts and friends)

    @Test
    void aFailedBankPayoutPausesThatRidersWithdrawalsUntilTheirBankDetailsAreUpdated() throws Exception {
        Setup s = captured();
        setPayoutAccount(s.rider(), "dest_ok");
        incidentService.record(IncidentType.CONNECT_PAYOUT_FAILED, "po_1", s.rider().id(), null, null,
                new BigDecimal("7.99"), "USD", "Bank payout po_1 failed: account_closed");

        call(HttpMethod.POST, "/api/riders/me/payouts", s.rider(), null).andExpect(status().isConflict());
        assertThat(fetch("/api/riders/me/earnings", s.rider()).get("payoutBlockedReason").asText()).contains("bank");
        assertThat(fetch("/api/admin/finance/attention", admin()).get("incidents").get("byType").get("CONNECT_PAYOUT_FAILED").asInt()).isEqualTo(1);

        assertThat(incidentService.resolveBankIssues(s.rider().id(), "Bank details were updated")).isEqualTo(1);

        assertThat(fetch("/api/riders/me/earnings", s.rider()).get("payoutBlockedReason").isNull()).isTrue();
        call(HttpMethod.POST, "/api/riders/me/payouts", s.rider(), null).andExpect(status().isCreated());
    }

    @Test
    void theSameProviderProblemReportedAgainIsOneIncidentAndAdminsCanResolveIt() throws Exception {
        Setup s = captured();
        var first = incidentService.record(IncidentType.TRANSFER_REVERSED, "tr_1", s.rider().id(), null, null,
                new BigDecimal("7.99"), "USD", "Transfer tr_1 was reversed");
        var again = incidentService.record(IncidentType.TRANSFER_REVERSED, "tr_1", s.rider().id(), null, null,
                new BigDecimal("7.99"), "USD", "Transfer tr_1 was reversed");

        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(incidentRepo.count()).isEqualTo(1);
        Actor admin = admin();
        assertThat(fetch("/api/admin/incidents?status=OPEN&type=TRANSFER_REVERSED", admin).get("total").asInt()).isEqualTo(1);

        JsonNode resolved = body(call(HttpMethod.POST, "/api/admin/incidents/" + first.getId() + "/resolve", admin,
                "{\"note\":\"Reconciled with Stripe; rider re-paid by hand\"}").andExpect(status().isOk()));
        assertThat(resolved.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(resolved.get("resolutionNote").asText()).contains("Reconciled");
        call(HttpMethod.POST, "/api/admin/incidents/" + first.getId() + "/resolve", admin, "{}").andExpect(status().isConflict());
        assertThat(fetch("/api/admin/incidents?status=OPEN", admin).get("total").asInt()).isZero();
        assertThat(fetch("/api/admin/finance/attention", admin).get("needsAction").asBoolean()).isFalse();
    }

    // ---------------------------------------------------------------- helpers

    private Setup captured() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Actor customer = customer("Ravi");
        Ride ride = completeRide(customer, rider);
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50)).until(() ->
                paymentRepo.findByTripId(ride.tripId()).map(Payment::getStatus).orElse(null) == PaymentStatus.CAPTURED);
        return new Setup(rider, customer, paymentRepo.findByTripId(ride.tripId()).orElseThrow());
    }

    private DisputeNotice notice(String id, Payment p, State state, BigDecimal amount) {
        return notice(id, p, state, amount, BigDecimal.ZERO);
    }

    private DisputeNotice notice(String id, Payment p, State state, BigDecimal amount, BigDecimal fee) {
        return new DisputeNotice(id, p.getGatewayReference(), amount, "USD", state, state.name().toLowerCase(), "fraudulent",
                null, fee, "https://dashboard.stripe.com/test/disputes/" + id);
    }

    private PaymentStatus paymentStatus(Payment p) {
        return paymentRepo.findById(p.getId()).orElseThrow().getStatus();
    }

    private BigDecimal cash() { return bal(AccountType.CASH); }

    private BigDecimal bal(AccountType type) { return ledger.balance(ledger.platform(type)); }

    private BigDecimal riderBalance(Actor rider) { return ledger.balance(ledger.riderAccount(rider.id())); }

    private BigDecimal held(Actor rider) { return ledger.riderHeldBalance(rider.id()); }
}
