package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ridehail.notify.PushNotifier;
import com.ridehail.verification.DocumentType;
import com.ridehail.verification.VerificationService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.ResultActions;

/** Who may drive: documents in, an admin's decision, and what a rider can and cannot do at each step. */
@SpringBootTest(properties = {"ridehail.verification.required=true", "ridehail.verification.max-document-bytes=4096"})
class VerificationApiTest extends ApiTestBase {

    static class Recorder implements PushNotifier {
        final List<Message> sent = Collections.synchronizedList(new ArrayList<>());
        @Override public List<String> send(List<String> tokens, Message message) { sent.add(message); return List.of(); }
    }

    @TestConfiguration
    static class Config {
        @Bean @Primary Recorder recorder() { return new Recorder(); }
    }

    @Autowired Recorder recorder;
    @Autowired VerificationService verification;
    @Autowired com.ridehail.notify.DeviceTokenRepository devices;

    private static final byte[] JPEG = jpeg("licence photo");
    private static byte[] jpeg(String marker) {
        byte[] body = marker.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[4 + body.length];
        out[0] = (byte) 0xFF; out[1] = (byte) 0xD8; out[2] = (byte) 0xFF; out[3] = (byte) 0xE0;
        System.arraycopy(body, 0, out, 4, body.length);
        return out;
    }
    private static final byte[] PDF = "%PDF-1.4 insurance".getBytes(StandardCharsets.US_ASCII);

    @BeforeEach
    void reset() { recorder.sent.clear(); }

    private LocalDate today() { return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private LocalDate later() { return today().plusDays(400); }

    private ResultActions upload(Actor rider, String type, byte[] bytes, LocalDate expires) throws Exception {
        var req = put("/api/riders/me/verification/documents/" + type + (expires == null ? "" : "?expiresOn=" + expires))
                .header("Authorization", "Bearer " + rider.token()).contentType("application/octet-stream").content(bytes);
        return mvc.perform(req);
    }

    private void uploadAll(Actor rider) throws Exception {
        upload(rider, "DRIVING_LICENCE", JPEG, later()).andExpect(status().isOk());
        upload(rider, "VEHICLE_REGISTRATION", PDF, later()).andExpect(status().isOk());
        upload(rider, "INSURANCE", PDF, later()).andExpect(status().isOk());
        upload(rider, "SELFIE", jpeg("my face"), null).andExpect(status().isOk());
    }

    private JsonNode verificationOf(Actor rider) throws Exception { return fetch("/api/riders/me/verification", rider); }

    /** A new rider with a phone registered, so the notifications sent to them are visible to the test. */
    private Actor newRider(String name) throws Exception {
        Actor rider = riderOffline(name);
        call(HttpMethod.PUT, "/api/devices", rider, "{\"token\":\"ExponentPushToken[" + UUID.randomUUID().toString().replace("-", "") + "]\",\"platform\":\"ios\"}")
                .andExpect(status().isNoContent());
        return rider;
    }

    private Actor approvedRider(String name, double latOffset) throws Exception {
        Actor rider = newRider(name);
        uploadAll(rider);
        call(HttpMethod.POST, "/api/riders/me/verification/submit", rider, null).andExpect(status().isOk());
        call(HttpMethod.POST, "/api/admin/verifications/" + rider.id() + "/approve", admin(), null).andExpect(status().isOk());
        call(HttpMethod.POST, "/api/riders/me/online", rider, "{\"lat\":" + (PICKUP_LAT + latOffset) + ",\"lon\":" + PICKUP_LON + "}").andExpect(status().isOk());
        return rider;
    }

    private long countSent(String type) {
        synchronized (recorder.sent) { return recorder.sent.stream().filter(m -> type.equals(m.data().get("type"))).count(); }
    }

    private void awaitSent(String type) {
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50)).until(() -> countSent(type) > 0);
    }

    private String goOnlineBody() { return "{\"lat\":" + PICKUP_LAT + ",\"lon\":" + PICKUP_LON + "}"; }

    // ---------------------------------------------------------------- a new rider

    @Test
    void aNewRiderCannotGoOnlineUntilApproved() throws Exception {
        Actor rider = newRider("Asha");
        JsonNode v = verificationOf(rider);
        assertThat(v.get("required").asBoolean()).isTrue();
        assertThat(v.get("status").asText()).isEqualTo("NOT_SUBMITTED");
        assertThat(v.get("missing")).hasSize(4);
        assertThat(v.get("canSubmit").asBoolean()).isFalse();

        call(HttpMethod.POST, "/api/riders/me/online", rider, goOnlineBody()).andExpect(status().isForbidden());
        assertThat(fetch("/api/riders/me", rider).get("status").asText()).isEqualTo("OFFLINE");
    }

    @Test
    void anUnverifiedRiderIsNeverOfferedATrip() throws Exception {
        Actor ravi = customer("Ravi");
        newRider("Nearby but unverified");
        UUID trip = book(ravi, quote(ravi).get("quoteId").asText());
        assertThat(fetch("/api/trips/" + trip, ravi).get("status").asText()).isIn("NO_RIDERS_AVAILABLE", "REQUESTED");
        assertThat(offerRepo.findByTripIdOrderByCreatedAtAsc(trip)).isEmpty();
    }

    @Test
    void dispatchSkipsAnUnverifiedRiderEvenIfTheyAreStillMarkedAvailable() throws Exception {
        // the going-online gate normally prevents this state; the dispatch query must not trust that alone (a race, or old data)
        Actor asha = approvedRider("Asha", 0.005);
        jdbc.update("update riders set verified = false where id = ?", asha.id());
        assertThat(fetch("/api/riders/me", asha).get("status").asText()).isEqualTo("AVAILABLE");

        Actor ravi = customer("Ravi");
        UUID trip = book(ravi, quote(ravi).get("quoteId").asText());
        assertThat(offerRepo.findByTripIdOrderByCreatedAtAsc(trip)).isEmpty();
    }

    // ---------------------------------------------------------------- uploading

    @Test
    void onlyJpegPngAndPdfAreAcceptedWhateverTheyAreCalled() throws Exception {
        Actor rider = newRider("Asha");
        upload(rider, "DRIVING_LICENCE", "#!/bin/sh\nrm -rf /".getBytes(StandardCharsets.UTF_8), later()).andExpect(status().isUnsupportedMediaType());
        upload(rider, "DRIVING_LICENCE", "<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8), later()).andExpect(status().isUnsupportedMediaType());
        // a client claiming "image/jpeg" does not make it one
        mvc.perform(put("/api/riders/me/verification/documents/DRIVING_LICENCE?expiresOn=" + later())
                .header("Authorization", "Bearer " + rider.token()).contentType("image/jpeg").content("not a picture".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnsupportedMediaType());
        upload(rider, "SELFIE", PDF, null).andExpect(status().isUnsupportedMediaType());      // a selfie must be a picture
        upload(rider, "DRIVING_LICENCE", new byte[0], later()).andExpect(status().isBadRequest());
        assertThat(verificationOf(rider).get("documents")).isEmpty();
    }

    @Test
    void aFileOverTheLimitIsRefused() throws Exception {
        Actor rider = newRider("Asha");
        byte[] big = new byte[5000];
        System.arraycopy(JPEG, 0, big, 0, JPEG.length);
        upload(rider, "DRIVING_LICENCE", big, later()).andExpect(status().isPayloadTooLarge());
        assertThat(verificationOf(rider).get("documents")).isEmpty();
    }

    @Test
    void expiryDatesAreRequiredWhereTheyMatterAndMustBeSensible() throws Exception {
        Actor rider = newRider("Asha");
        upload(rider, "DRIVING_LICENCE", JPEG, null).andExpect(status().isBadRequest());                       // needs a date
        upload(rider, "INSURANCE", PDF, today().minusDays(1)).andExpect(status().isBadRequest());              // already expired
        upload(rider, "INSURANCE", PDF, today()).andExpect(status().isBadRequest());                           // expires today = not valid
        upload(rider, "INSURANCE", PDF, today().plusYears(40)).andExpect(status().isBadRequest());             // not real
        upload(rider, "INSURANCE", PDF, later()).andExpect(status().isOk());
        upload(rider, "SELFIE", jpeg("face"), today().minusYears(5)).andExpect(status().isOk());               // a selfie has no expiry: ignored
        assertThat(verificationOf(rider).get("documents")).hasSize(2);
    }

    @Test
    void uploadingAgainReplacesTheEarlierDocument() throws Exception {
        Actor rider = newRider("Asha");
        upload(rider, "DRIVING_LICENCE", JPEG, later()).andExpect(status().isOk());
        upload(rider, "DRIVING_LICENCE", jpeg("a better photo"), later().plusDays(5)).andExpect(status().isOk());
        JsonNode docs = verificationOf(rider).get("documents");
        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).get("expiresOn").asText()).isEqualTo(later().plusDays(5).toString());
        assertThat(jdbc.queryForObject("select count(*) from rider_document_blobs", Integer.class)).isEqualTo(
                jdbc.queryForObject("select count(*) from rider_documents", Integer.class));       // no orphaned blob
    }

    @Test
    void submittingNeedsEverything() throws Exception {
        Actor rider = newRider("Asha");
        upload(rider, "DRIVING_LICENCE", JPEG, later()).andExpect(status().isOk());
        var res = call(HttpMethod.POST, "/api/riders/me/verification/submit", rider, null).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(res).contains("vehicle registration").contains("insurance certificate").contains("selfie");

        uploadAll(rider);
        JsonNode v = call(HttpMethod.POST, "/api/riders/me/verification/submit", rider, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString().isEmpty()
                ? null : verificationOf(rider);
        assertThat(v.get("status").asText()).isEqualTo("PENDING_REVIEW");
        // nothing can change while a person is reading it
        upload(rider, "INSURANCE", PDF, later()).andExpect(status().isConflict());
        call(HttpMethod.POST, "/api/riders/me/verification/submit", rider, null).andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------- the admin's review

    @Test
    void anAdminSeesTheQueueAndOpensDocumentsAndEveryOpeningIsLogged() throws Exception {
        Actor rider = newRider("Asha");
        uploadAll(rider);
        call(HttpMethod.POST, "/api/riders/me/verification/submit", rider, null).andExpect(status().isOk());
        Actor ops = admin();

        JsonNode queue = fetch("/api/admin/verifications", ops);
        assertThat(queue.get("items").findValuesAsText("riderId")).contains(rider.id().toString());
        JsonNode detail = fetch("/api/admin/verifications/" + rider.id(), ops);
        assertThat(detail.get("name").asText()).isEqualTo("Asha");
        assertThat(detail.get("verification").get("documents")).hasSize(4);

        var res = call(HttpMethod.GET, "/api/admin/verifications/" + rider.id() + "/documents/DRIVING_LICENCE", ops, null)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
                .andReturn().getResponse();
        assertThat(res.getContentAsByteArray()).isEqualTo(JPEG);

        JsonNode audit = fetch("/api/admin/audit?action=VERIFICATION_DOCUMENT_VIEWED", ops);
        assertThat(audit.get("items")).isNotEmpty();
        assertThat(audit.get("items").get(0).get("targetId").asText()).isEqualTo(rider.id().toString());
        assertThat(audit.get("items").get(0).get("detail").asText()).isEqualTo("Driving licence");
    }

    @Test
    void onlyAdminsTouchVerificationOrDocuments() throws Exception {
        Actor rider = newRider("Asha");
        uploadAll(rider);
        Actor ravi = customer("Ravi");
        String base = "/api/admin/verifications/" + rider.id();
        for (Actor outsider : new Actor[] {rider, ravi}) {
            call(HttpMethod.GET, "/api/admin/verifications", outsider, null).andExpect(status().isForbidden());
            call(HttpMethod.GET, base, outsider, null).andExpect(status().isForbidden());
            call(HttpMethod.GET, base + "/documents/DRIVING_LICENCE", outsider, null).andExpect(status().isForbidden());
            call(HttpMethod.POST, base + "/approve", outsider, null).andExpect(status().isForbidden());
        }
        call(HttpMethod.GET, base + "/documents/DRIVING_LICENCE", null, null).andExpect(status().isUnauthorized());
        // and a customer has no verification of their own to read
        call(HttpMethod.GET, "/api/riders/me/verification", ravi, null).andExpect(status().isForbidden());
    }

    @Test
    void approvingLetsTheRiderDriveAndTellsThem() throws Exception {
        Actor ravi = customer("Ravi");
        Actor asha = approvedRider("Asha", 0.005);
        assertThat(verificationOf(asha).get("status").asText()).isEqualTo("APPROVED");
        assertThat(fetch("/api/riders/me", asha).get("status").asText()).isEqualTo("AVAILABLE");
        awaitSent("VERIFICATION_APPROVED");

        UUID trip = book(ravi, quote(ravi).get("quoteId").asText());
        assertThat(fetch("/api/riders/me/offers", asha)).hasSize(1);       // offered like any rider
        assertThat(trip).isNotNull();

        call(HttpMethod.POST, "/api/admin/verifications/" + asha.id() + "/approve", admin(), null).andExpect(status().isConflict());   // twice
    }

    @Test
    void rejectingNeedsAReasonAndTheRiderCanFixAndResubmit() throws Exception {
        Actor rider = newRider("Asha");
        uploadAll(rider);
        call(HttpMethod.POST, "/api/riders/me/verification/submit", rider, null).andExpect(status().isOk());
        Actor ops = admin();
        String base = "/api/admin/verifications/" + rider.id();

        call(HttpMethod.POST, base + "/reject", ops, "{}").andExpect(status().isBadRequest());
        call(HttpMethod.POST, base + "/reject", ops, "{\"reason\":\"  \"}").andExpect(status().isBadRequest());
        call(HttpMethod.POST, base + "/reject", ops, "{\"reason\":\"The licence photo is blurry\"}").andExpect(status().isOk());

        JsonNode v = verificationOf(rider);
        assertThat(v.get("status").asText()).isEqualTo("REJECTED");
        assertThat(v.get("note").asText()).isEqualTo("The licence photo is blurry");
        assertThat(v.get("canUpload").asBoolean()).isTrue();
        call(HttpMethod.POST, "/api/riders/me/online", rider, goOnlineBody()).andExpect(status().isForbidden());
        awaitSent("VERIFICATION_REJECTED");

        upload(rider, "DRIVING_LICENCE", jpeg("sharp photo"), later()).andExpect(status().isOk());
        call(HttpMethod.POST, "/api/riders/me/verification/submit", rider, null).andExpect(status().isOk());
        assertThat(verificationOf(rider).get("note").isNull()).isTrue();         // the old reason is gone
        call(HttpMethod.POST, base + "/approve", ops, null).andExpect(status().isOk());
        call(HttpMethod.POST, "/api/riders/me/online", rider, goOnlineBody()).andExpect(status().isOk());
    }

    @Test
    void youCannotApproveOrRejectWhatWasNeverSubmitted() throws Exception {
        Actor rider = newRider("Asha");
        uploadAll(rider);
        Actor ops = admin();
        call(HttpMethod.POST, "/api/admin/verifications/" + rider.id() + "/approve", ops, null).andExpect(status().isConflict());
        call(HttpMethod.POST, "/api/admin/verifications/" + rider.id() + "/reject", ops, "{\"reason\":\"x\"}").andExpect(status().isConflict());
        call(HttpMethod.GET, "/api/admin/verifications/" + UUID.randomUUID(), ops, null).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- suspension

    @Test
    void suspendingTakesARiderOffAndKeepsThemOff() throws Exception {
        Actor asha = approvedRider("Asha", 0.005);
        Actor ops = admin();
        String base = "/api/admin/verifications/" + asha.id();

        call(HttpMethod.POST, base + "/suspend", ops, "{}").andExpect(status().isBadRequest());                   // a reason is required
        call(HttpMethod.POST, base + "/suspend", ops, "{\"reason\":\"Complaint under investigation\"}").andExpect(status().isOk());
        assertThat(fetch("/api/riders/me", asha).get("status").asText()).isEqualTo("OFFLINE");
        assertThat(verificationOf(asha).get("status").asText()).isEqualTo("SUSPENDED");
        call(HttpMethod.POST, "/api/riders/me/online", asha, goOnlineBody()).andExpect(status().isForbidden());
        upload(asha, "INSURANCE", PDF, later()).andExpect(status().isConflict());   // nothing to fix: only an admin lifts it

        call(HttpMethod.POST, base + "/reinstate", ops, null).andExpect(status().isOk());
        call(HttpMethod.POST, "/api/riders/me/online", asha, goOnlineBody()).andExpect(status().isOk());
    }

    @Test
    void aRiderSuspendedMidTripFinishesItAndThenStaysOffTheRoad() throws Exception {
        Actor ravi = customer("Ravi");
        Actor asha = approvedRider("Asha", 0.005);
        UUID trip = book(ravi, quote(ravi).get("quoteId").asText());
        String offer = fetch("/api/riders/me/offers", asha).get(0).get("offerId").asText();
        call(HttpMethod.POST, "/api/riders/me/offers/" + offer + "/accept", asha, null);
        assertThat(fetch("/api/riders/me", asha).get("status").asText()).isEqualTo("ON_TRIP");

        call(HttpMethod.POST, "/api/admin/verifications/" + asha.id() + "/suspend", admin(), "{\"reason\":\"Safety report\"}").andExpect(status().isOk());
        assertThat(fetch("/api/riders/me", asha).get("status").asText()).isEqualTo("ON_TRIP");        // the customer is not stranded

        for (String step : new String[] {"arrive", "start", "complete"}) call(HttpMethod.POST, "/api/trips/" + trip + "/" + step, asha, null).andExpect(status().isOk());
        assertThat(fetch("/api/riders/me", asha).get("status").asText()).isEqualTo("OFFLINE");          // and now they are off
        // nobody else is offered to them either: a second request finds no rider
        UUID second = book(ravi, quote(ravi).get("quoteId").asText());
        assertThat(offerRepo.findByTripIdOrderByCreatedAtAsc(second)).isEmpty();
    }

    // ---------------------------------------------------------------- expiry

    @Test
    void anExpiredDocumentTakesARiderOffUntilTheySendACurrentOne() throws Exception {
        Actor asha = approvedRider("Asha", 0.005);
        recorder.sent.clear();
        clock.advance(Duration.ofDays(401));            // past the 400 days every document was valid for
        assertThat(verification.expireAndWarn()).isEqualTo(1);

        assertThat(verificationOf(asha).get("status").asText()).isEqualTo("EXPIRED");
        assertThat(fetch("/api/riders/me", asha).get("status").asText()).isEqualTo("OFFLINE");
        call(HttpMethod.POST, "/api/riders/me/online", asha, goOnlineBody()).andExpect(status().isForbidden());
        awaitSent("VERIFICATION_EXPIRED");
        assertThat(verification.expireAndWarn()).isZero();            // already dealt with: not again

        // renew all three, submit, get approved again
        LocalDate fresh = today().plusDays(300);
        upload(asha, "DRIVING_LICENCE", JPEG, fresh).andExpect(status().isOk());
        upload(asha, "VEHICLE_REGISTRATION", PDF, fresh).andExpect(status().isOk());
        upload(asha, "INSURANCE", PDF, fresh).andExpect(status().isOk());
        call(HttpMethod.POST, "/api/riders/me/verification/submit", asha, null).andExpect(status().isOk());
        call(HttpMethod.POST, "/api/admin/verifications/" + asha.id() + "/approve", admin(), null).andExpect(status().isOk());
        call(HttpMethod.POST, "/api/riders/me/online", asha, goOnlineBody()).andExpect(status().isOk());
    }

    @Test
    void aDocumentAboutToExpireWarnsOnceAndLeavesTheRiderDriving() throws Exception {
        Actor asha = approvedRider("Asha", 0.005);
        recorder.sent.clear();
        clock.advance(Duration.ofDays(390));            // 10 days before the 400
        verification.expireAndWarn();
        verification.expireAndWarn();
        await().atMost(Duration.ofSeconds(5)).until(() -> countSent("VERIFICATION_EXPIRING") >= 3);
        Thread.sleep(300);
        assertThat(countSent("VERIFICATION_EXPIRING")).isEqualTo(3);   // licence, registration, insurance: once each, however often it runs
        assertThat(fetch("/api/riders/me", asha).get("status").asText()).isEqualTo("AVAILABLE");
    }

    @Test
    void reinstatingCannotLiftALapsedDocument() throws Exception {
        Actor asha = approvedRider("Asha", 0.005);
        Actor ops = admin();
        call(HttpMethod.POST, "/api/admin/verifications/" + asha.id() + "/suspend", ops, "{\"reason\":\"x\"}").andExpect(status().isOk());
        clock.advance(Duration.ofDays(401));
        call(HttpMethod.POST, "/api/admin/verifications/" + asha.id() + "/reinstate", ops, null).andExpect(status().isOk());
        assertThat(verificationOf(asha).get("status").asText()).isEqualTo("EXPIRED");
        call(HttpMethod.POST, "/api/riders/me/online", asha, goOnlineBody()).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- what is stored

    @Test
    void documentsAreEncryptedAtRestAndTamperingIsDetected() throws Exception {
        Actor a = newRider("Asha"), b = newRider("Bina");
        upload(a, "DRIVING_LICENCE", jpeg("PLAIN-MARKER-AAA"), later()).andExpect(status().isOk());
        upload(b, "DRIVING_LICENCE", jpeg("PLAIN-MARKER-BBB"), later()).andExpect(status().isOk());

        String mine = "select b.document_id, b.data from rider_document_blobs b join rider_documents d on d.id = b.document_id where d.rider_id in (?, ?) order by d.rider_id";
        var rows = jdbc.query(mine, (rs, i) -> new Object[] {rs.getObject(1), rs.getBytes(2)}, a.id(), b.id());
        assertThat(rows).hasSize(2);
        for (Object[] row : rows) assertThat(new String((byte[]) row[1], StandardCharsets.ISO_8859_1)).doesNotContain("PLAIN-MARKER");

        // swap the two stored blobs in the database: each document now holds the other's bytes, and neither can be read
        jdbc.update("update rider_document_blobs set data = ? where document_id = ?", rows.get(1)[1], rows.get(0)[0]);
        jdbc.update("update rider_document_blobs set data = ? where document_id = ?", rows.get(0)[1], rows.get(1)[0]);
        Actor ops = admin();
        assertThatThrownBy(() -> call(HttpMethod.GET, "/api/admin/verifications/" + a.id() + "/documents/DRIVING_LICENCE", ops, null).andReturn())
                .hasRootCauseInstanceOf(javax.crypto.AEADBadTagException.class);   // authentication failed: the blob is not this document's
    }

    @Test
    void ariderRegisteredBeforeVerificationExistedCountsAsApproved() throws Exception {
        Actor old = newRider("Old timer");
        jdbc.update("update riders set verified = true where id = ?", old.id());      // what the migration left them as
        jdbc.update("delete from rider_verifications where rider_id = ?", old.id());
        assertThat(verificationOf(old).get("status").asText()).isEqualTo("APPROVED");
        call(HttpMethod.POST, "/api/riders/me/online", old, goOnlineBody()).andExpect(status().isOk());
    }

    @Test
    void adminResponseShapesMatchTheContractFixtures() throws Exception {
        Actor rider = newRider("Asha");
        uploadAll(rider);
        call(HttpMethod.POST, "/api/riders/me/verification/submit", rider, null).andExpect(status().isOk());
        Actor ops = admin();
        ContractFixtures.check(json, "verification-queue.json", fetch("/api/admin/verifications?status=PENDING_REVIEW&size=5", ops));
        ContractFixtures.check(json, "verification-detail.json", fetch("/api/admin/verifications/" + rider.id(), ops));
    }

    @Test
    void theReviewQueueIsFilterableAndOldestFirst() throws Exception {
        Actor first = newRider("First"), second = newRider("Second");
        for (Actor r : new Actor[] {first, second}) {
            uploadAll(r);
            call(HttpMethod.POST, "/api/riders/me/verification/submit", r, null).andExpect(status().isOk());
            clock.advance(Duration.ofMinutes(5));
        }
        Actor ops = admin();
        List<String> ids = fetch("/api/admin/verifications?status=PENDING_REVIEW&size=100", ops).get("items").findValuesAsText("riderId");
        assertThat(ids.indexOf(first.id().toString())).isLessThan(ids.indexOf(second.id().toString()));
        call(HttpMethod.POST, "/api/admin/verifications/" + first.id() + "/approve", ops, null).andExpect(status().isOk());
        assertThat(fetch("/api/admin/verifications?status=PENDING_REVIEW&size=100", ops).get("items").findValuesAsText("riderId")).doesNotContain(first.id().toString());
        assertThat(fetch("/api/admin/verifications?status=APPROVED&size=100", ops).get("items").findValuesAsText("riderId")).contains(first.id().toString());
    }
}
