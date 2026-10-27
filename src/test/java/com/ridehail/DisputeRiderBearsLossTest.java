package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ridehail.dispute.DisputeNotice;
import com.ridehail.dispute.DisputeNotice.State;
import com.ridehail.dispute.DisputeService;
import com.ridehail.ledger.AccountType;
import com.ridehail.ledger.LedgerService;
import com.ridehail.payment.Payment;
import com.ridehail.payment.PaymentStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;

/** The other loss policy: a lost dispute is taken back from the rider (netted against future earnings if already paid out). */
@SpringBootTest(properties = {
        "ridehail.dispatch.expiry-scan-ms=3600000",
        "ridehail.payments.retry-scan-ms=3600000",
        "ridehail.disputes.loss-policy=rider",
        "spring.datasource.url=jdbc:h2:mem:dispute-rider-bears;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
class DisputeRiderBearsLossTest extends ApiTestBase {

    @Autowired DisputeService disputeService;
    @Autowired LedgerService ledger;

    @Test
    void theRidersShareOfALostDisputeIsForfeitedAndThePlatformAbsorbsTheRest() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Payment p = captured(rider);

        disputeService.apply(notice(p, State.OPEN));
        disputeService.apply(notice(p, State.LOST));

        assertThat(riderBalance(rider)).isEqualByComparingTo("0");                                   // they do NOT get the 7.99
        assertThat(ledger.riderHeldBalance(rider.id())).isEqualByComparingTo("0");                   // and nothing stays frozen
        assertThat(bal(AccountType.DISPUTE_LOSSES)).isEqualByComparingTo(p.getCommissionAmount());   // the platform loses only its commission
        assertThat(bal(AccountType.DISPUTES_IN_PROGRESS)).isEqualByComparingTo("0");
        assertThat(bal(AccountType.CASH)).isEqualByComparingTo("0");
        assertThat(ledger.trialBalance().balanced()).isTrue();
        assertThat(fetch("/api/riders/me/earnings", rider).get("onHold").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void winningStillChangesNothingForAnyoneUnderThisPolicy() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Payment p = captured(rider);

        disputeService.apply(notice(p, State.OPEN));
        disputeService.apply(notice(p, State.WON));

        assertThat(riderBalance(rider)).isEqualByComparingTo(p.getRiderAmount());
        assertThat(bal(AccountType.CASH)).isEqualByComparingTo(p.getAmount());
        assertThat(bal(AccountType.DISPUTE_LOSSES)).isEqualByComparingTo("0");
    }

    @Test
    void aRiderWhoAlreadyWithdrewOwesTheLossAndItIsRecoveredFromTheirNextEarnings() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Payment first = captured(rider);
        setPayoutAccount(rider, "dest_ok");
        call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isCreated());     // paid out in full

        disputeService.apply(notice(first, State.OPEN));
        disputeService.apply(notice(first, State.LOST));
        assertThat(riderBalance(rider)).isEqualByComparingTo(first.getRiderAmount().negate());          // they now owe 7.99

        // their next ride only pays off the debt: nothing to withdraw yet
        captured(rider);
        assertThat(riderBalance(rider)).isEqualByComparingTo("0");
        call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isConflict());

        // the ride after that is theirs again
        Payment third = captured(rider);
        assertThat(riderBalance(rider)).isEqualByComparingTo(third.getRiderAmount());
        call(HttpMethod.POST, "/api/riders/me/payouts", rider, null).andExpect(status().isCreated());
        assertThat(ledger.trialBalance().balanced()).isTrue();
    }

    // ---------------------------------------------------------------- helpers

    private Payment captured(Actor rider) throws Exception {
        clock.advance(Duration.ofMinutes(11));        // past the 10-minute surge window, so every ride costs the same 9.99
        Actor customer = customer("Customer " + UUID.randomUUID());
        Ride ride = completeRide(customer, rider);
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50)).until(() ->
                paymentRepo.findByTripId(ride.tripId()).map(Payment::getStatus).orElse(null) == PaymentStatus.CAPTURED);
        return paymentRepo.findByTripId(ride.tripId()).orElseThrow();
    }

    private DisputeNotice notice(Payment p, State state) {
        return new DisputeNotice("dp_" + p.getId(), p.getGatewayReference(), p.getAmount(), "USD", state, state.name().toLowerCase(),
                "fraudulent", null, BigDecimal.ZERO, null);
    }

    private BigDecimal bal(AccountType type) { return ledger.balance(ledger.platform(type)); }

    private BigDecimal riderBalance(Actor rider) { return ledger.balance(ledger.riderAccount(rider.id())); }
}
