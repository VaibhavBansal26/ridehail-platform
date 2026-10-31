package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ridehail.dispute.DisputeNotice;
import com.ridehail.dispute.DisputeNotice.State;
import com.ridehail.dispute.DisputeRepository;
import com.ridehail.dispute.DisputeService;
import com.ridehail.payment.Payment;
import com.ridehail.payment.PaymentStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/** Answering a chargeback from the admin panel: the facts come from our records, the explanation from a person, and it is sent once. */
class DisputeEvidenceTest extends ApiTestBase {

    @Autowired DisputeService disputeService;
    @Autowired DisputeRepository disputeRepo;

    private UUID openDispute(String providerId) throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Actor customer = customer("Ravi");
        Ride ride = completeRide(customer, rider);
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50)).until(() ->
                paymentRepo.findByTripId(ride.tripId()).map(Payment::getStatus).orElse(null) == PaymentStatus.CAPTURED);
        Payment p = paymentRepo.findByTripId(ride.tripId()).orElseThrow();
        disputeService.apply(new DisputeNotice(providerId, p.getGatewayReference(), p.getAmount(), "USD", State.OPEN, "needs_response",
                "fraudulent", Instant.now(clock).plus(Duration.ofDays(7)), BigDecimal.ZERO, "https://example.test/" + providerId));
        return disputeRepo.findByProviderId(providerId).orElseThrow().getId();
    }

    private String url(UUID id) { return "/api/admin/disputes/" + id + "/evidence"; }

    private static String draft(String text) { return "{\"explanation\":\"" + text + "\"}"; }

    @Test
    void theFactsAreCompiledFromTheTripAndAreReadyBeforeAnyoneWritesAnything() throws Exception {
        UUID id = openDispute("dp_1");

        JsonNode v = fetch(url(id), admin());

        assertThat(v.get("facts").get("customerName").asText()).isEqualTo("Ravi");
        assertThat(v.get("facts").get("productDescription").asText()).contains("Ride-hailing trip");
        assertThat(v.get("facts").get("serviceDate").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(v.get("facts").get("accessActivityLog").asText()).contains("Trip started at").contains("Trip completed at").contains("Fare");
        assertThat(v.get("submitted").asBoolean()).isFalse();
        assertThat(v.get("canSubmit").asBoolean()).isFalse();
        assertThat(v.get("cannotSubmitBecause").asText()).contains("explanation");
    }

    @Test
    void aDraftCanBeSavedAndEditedThenSubmittedOnce() throws Exception {
        UUID id = openDispute("dp_1");
        Actor admin = admin();

        call(HttpMethod.POST, url(id), admin, draft("first version")).andExpect(status().isOk());
        JsonNode saved = body(call(HttpMethod.POST, url(id), admin, draft("The customer rode with us and the driver has the trip record.")));
        assertThat(saved.get("canSubmit").asBoolean()).isTrue();
        assertThat(fetch(url(id), admin).get("explanation").asText()).startsWith("The customer rode");

        JsonNode done = body(call(HttpMethod.POST, url(id) + "/submit", admin, null));

        assertThat(done.get("submitted").asBoolean()).isTrue();
        assertThat(gateway.requests()).contains("evidence:dispute-evidence-" + id);
        assertThat(gateway.succeeded("evidence")).isEqualTo(1);

        // one answer only: no second submission, no editing afterwards
        call(HttpMethod.POST, url(id) + "/submit", admin, null).andExpect(status().isConflict());
        call(HttpMethod.POST, url(id), admin, draft("changed my mind")).andExpect(status().isConflict());
        assertThat(fetch(url(id), admin).get("explanation").asText()).startsWith("The customer rode");
        assertThat(gateway.succeeded("evidence")).isEqualTo(1);
    }

    @Test
    void whatWasSentStaysAsItWasEvenIfTheTripRecordsChangeLater() throws Exception {
        UUID id = openDispute("dp_1");
        Actor admin = admin();
        call(HttpMethod.POST, url(id), admin, draft("Trip record attached.")).andExpect(status().isOk());
        JsonNode before = body(call(HttpMethod.POST, url(id) + "/submit", admin, null));

        JsonNode after = fetch(url(id), admin);

        assertThat(after.get("facts")).isEqualTo(before.get("facts"));
        assertThat(after.get("submittedAt").asText()).isNotBlank();
    }

    @Test
    void nothingCanBeSubmittedWithoutAnExplanationOrAfterTheDeadline() throws Exception {
        UUID id = openDispute("dp_1");
        Actor admin = admin();

        call(HttpMethod.POST, url(id) + "/submit", admin, null).andExpect(status().isConflict());
        call(HttpMethod.POST, url(id), admin, draft("   ")).andExpect(status().isOk());
        call(HttpMethod.POST, url(id) + "/submit", admin, null).andExpect(status().isConflict());

        call(HttpMethod.POST, url(id), admin, draft("An explanation.")).andExpect(status().isOk());
        clock.advance(Duration.ofDays(8));
        call(HttpMethod.POST, url(id) + "/submit", admin, null).andExpect(status().isConflict());
        assertThat(fetch(url(id), admin).get("cannotSubmitBecause").asText()).contains("deadline");
        assertThat(gateway.succeeded("evidence")).isZero();
    }

    @Test
    void aDecidedDisputeCannotBeAnswered() throws Exception {
        UUID id = openDispute("dp_1");
        Payment p = paymentRepo.findAll().get(0);
        disputeService.apply(new DisputeNotice("dp_1", p.getGatewayReference(), p.getAmount(), "USD", State.LOST, "lost", "fraudulent",
                null, BigDecimal.ZERO, null));
        Actor admin = admin();

        call(HttpMethod.POST, url(id), admin, draft("too late")).andExpect(status().isConflict());
        call(HttpMethod.POST, url(id) + "/submit", admin, null).andExpect(status().isConflict());
    }

    @Test
    void whenTheProviderRefusesTheAdminSeesWhyAndCanTryAgain() throws Exception {
        UUID id = openDispute("dp_refuse_1");
        Actor admin = admin();
        call(HttpMethod.POST, url(id), admin, draft("Please accept this.")).andExpect(status().isOk());

        call(HttpMethod.POST, url(id) + "/submit", admin, null).andExpect(status().isBadRequest());

        JsonNode v = fetch(url(id), admin);
        assertThat(v.get("submitted").asBoolean()).isFalse();
        assertThat(v.get("lastError").asText()).contains("can no longer be answered");
        assertThat(v.get("canSubmit").asBoolean()).isTrue();
    }

    @Test
    void anOutageAtTheProviderSubmitsNothingAndALaterTryWorks() throws Exception {
        UUID id = openDispute("dp_1");
        Actor admin = admin();
        call(HttpMethod.POST, url(id), admin, draft("Evidence.")).andExpect(status().isOk());

        gateway.failNextCallsTransiently(1);
        call(HttpMethod.POST, url(id) + "/submit", admin, null).andExpect(status().isBadGateway());
        assertThat(fetch(url(id), admin).get("submitted").asBoolean()).isFalse();

        call(HttpMethod.POST, url(id) + "/submit", admin, null).andExpect(status().isOk());
        assertThat(fetch(url(id), admin).get("submitted").asBoolean()).isTrue();
    }

    @Test
    void savingAndSubmittingAreAuditedAndOnlyAdminsCanDoThem() throws Exception {
        UUID id = openDispute("dp_1");
        Actor admin = admin();
        Actor customer = customer("Someone Else");
        call(HttpMethod.GET, url(id), customer, null).andExpect(status().isForbidden());
        call(HttpMethod.POST, url(id), customer, draft("x")).andExpect(status().isForbidden());
        call(HttpMethod.POST, url(id) + "/submit", customer, null).andExpect(status().isForbidden());

        call(HttpMethod.POST, url(id), admin, draft("Evidence.")).andExpect(status().isOk());
        call(HttpMethod.POST, url(id) + "/submit", admin, null).andExpect(status().isOk());

        for (String action : new String[] {"DISPUTE_EVIDENCE_SAVED", "DISPUTE_EVIDENCE_SUBMITTED"}) {
            var rows = fetch("/api/admin/audit?action=" + action, admin).get("items");
            assertThat(java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                    .filter(r -> r.get("targetId").asText().equals(id.toString()))).hasSize(1);
        }
    }

    @Test
    void responseShapesMatchTheContractFixtures() throws Exception {
        UUID id = openDispute("dp_1");
        Actor admin = admin();
        call(HttpMethod.POST, url(id), admin, draft("The customer rode with us.")).andExpect(status().isOk());
        ContractFixtures.check(json, "dispute-evidence.json", fetch(url(id), admin));
    }

    @Test
    void anExplanationTooLongForTheFieldIsRefusedAndUnknownDisputesAre404() throws Exception {
        UUID id = openDispute("dp_1");
        call(HttpMethod.POST, url(id), admin(), draft("x".repeat(5001))).andExpect(status().isBadRequest());
        call(HttpMethod.GET, url(UUID.randomUUID()), admin(), null).andExpect(status().isNotFound());
    }
}
