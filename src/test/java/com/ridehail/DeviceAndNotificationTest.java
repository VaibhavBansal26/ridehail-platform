package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ridehail.notify.DeviceTokenRepository;
import com.ridehail.notify.PushNotifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;

/** Phones register where to be notified, and the moments a person must not miss reach them: briefly, privately, and never at the ride's expense. */
class DeviceAndNotificationTest extends ApiTestBase {

    /** Stands in for the provider and remembers what it was asked to send. */
    static class Recorder implements PushNotifier {
        record Sent(List<String> tokens, Message message) {}
        final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
        volatile Supplier<List<String>> dead = List::of;
        volatile boolean failing;

        @Override
        public List<String> send(List<String> tokens, Message message) {
            if (failing) throw new IllegalStateException("provider is down");
            sent.add(new Sent(List.copyOf(tokens), message));
            return dead.get();
        }

        List<Sent> to(String token) { synchronized (sent) { return sent.stream().filter(s -> s.tokens().contains(token)).toList(); } }
    }

    @TestConfiguration
    static class Config {
        @Bean @Primary Recorder recorder() { return new Recorder(); }
    }

    @Autowired Recorder recorder;
    @Autowired DeviceTokenRepository devices;

    @BeforeEach
    void reset() {
        recorder.sent.clear();
        recorder.failing = false;
        recorder.dead = List::of;
    }

    private static String token() { return "ExponentPushToken[" + UUID.randomUUID().toString().replace("-", "") + "]"; }

    private void register(Actor who, String token) throws Exception {
        call(HttpMethod.PUT, "/api/devices", who, "{\"token\":\"" + token + "\",\"platform\":\"ios\"}").andExpect(status().isNoContent());
    }

    private Recorder.Sent firstTo(String token, String title) {
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                .until(() -> recorder.to(token).stream().anyMatch(s -> s.message().title().equals(title)));
        return recorder.to(token).stream().filter(s -> s.message().title().equals(title)).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- registering a phone

    @Test
    void customersAndRidersRegisterAPhoneAndNobodyElseCan() throws Exception {
        Actor ravi = customer("Ravi");
        Actor asha = onlineRider("Asha", 0.005);
        String t1 = token(), t2 = token();
        register(ravi, t1);
        register(asha, t2);
        register(ravi, t1);   // the app registers on every launch: idempotent
        assertThat(devices.findByProfileIdOrderByLastSeenAtDesc(ravi.id())).extracting("token").containsExactly(t1);
        assertThat(devices.findByProfileIdOrderByLastSeenAtDesc(asha.id())).extracting("token").containsExactly(t2);

        String body = "{\"token\":\"" + token() + "\",\"platform\":\"ios\"}";
        call(HttpMethod.PUT, "/api/devices", null, body).andExpect(status().isUnauthorized());
        call(HttpMethod.PUT, "/api/devices", admin(), body).andExpect(status().isForbidden());
    }

    @Test
    void onlyARealPushTokenOnARealPlatformIsAccepted() throws Exception {
        Actor ravi = customer("Ravi");
        for (String bad : new String[] {
                "{\"token\":\"not-a-token\",\"platform\":\"ios\"}",
                "{\"token\":\"ExponentPushToken[]\",\"platform\":\"ios\"}",
                "{\"token\":\"ExponentPushToken[abcdefgh\",\"platform\":\"ios\"}",
                "{\"token\":\"https://evil.example/x\",\"platform\":\"ios\"}",
                "{\"token\":\"" + token() + "\",\"platform\":\"windows\"}",
                "{\"platform\":\"ios\"}",
                "{\"token\":\"" + "x".repeat(300) + "\",\"platform\":\"ios\"}"}) {
            call(HttpMethod.PUT, "/api/devices", ravi, bad).andExpect(status().isBadRequest());
        }
        assertThat(devices.findByProfileIdOrderByLastSeenAtDesc(ravi.id())).isEmpty();
    }

    @Test
    void aPhoneFollowsTheAccountThatSignedInLast() throws Exception {
        Actor ravi = customer("Ravi"), meera = customer("Meera");
        String shared = token();
        register(ravi, shared);
        register(meera, shared);   // same phone, another person
        assertThat(devices.findByProfileIdOrderByLastSeenAtDesc(ravi.id())).isEmpty();
        assertThat(devices.findByProfileIdOrderByLastSeenAtDesc(meera.id())).extracting("token").containsExactly(shared);
    }

    @Test
    void anAccountKeepsOnlyItsMostRecentPhones() throws Exception {
        Actor ravi = customer("Ravi");
        List<String> all = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String t = token();
            all.add(t);
            register(ravi, t);
            clock.advance(Duration.ofSeconds(1));   // the test clock is frozen: give each phone its own "last seen" time
        }
        var kept = devices.findByProfileIdOrderByLastSeenAtDesc(ravi.id()).stream().map(d -> d.getToken()).toList();
        assertThat(kept).hasSize(10).containsExactlyInAnyOrderElementsOf(all.subList(2, 12));
    }

    @Test
    void signingOutForgetsThePhoneButOnlyForItsOwner() throws Exception {
        Actor ravi = customer("Ravi"), meera = customer("Meera");
        String t = token();
        register(ravi, t);
        call(HttpMethod.POST, "/api/devices/unregister", meera, "{\"token\":\"" + t + "\"}").andExpect(status().isNoContent());
        assertThat(devices.findById(t)).isPresent();                     // someone else cannot remove it
        call(HttpMethod.POST, "/api/devices/unregister", ravi, "{\"token\":\"" + t + "\"}").andExpect(status().isNoContent());
        assertThat(devices.findById(t)).isEmpty();
    }

    // ---------------------------------------------------------------- what gets sent

    @Test
    void theMomentsOfARideReachTheRightPhonesWithLittleInThem() throws Exception {
        Actor ravi = customer("Ravi");
        Actor asha = onlineRider("Asha", 0.005);
        String customerPhone = token(), riderPhone = token();
        register(ravi, customerPhone);
        register(asha, riderPhone);

        UUID trip = book(ravi, quote(ravi).get("quoteId").asText());

        // the rider is offered the ride: a short question, so it must not be delivered late
        var offer = firstTo(riderPhone, "New ride request");
        assertThat(offer.message().body()).matches("USD \\d+\\.\\d\\d · .+ to pickup");
        assertThat(offer.message().data()).containsEntry("type", "OFFER_RECEIVED").containsEntry("tripId", trip.toString()).containsKey("offerId");
        assertThat(offer.message().ttlSeconds()).isBetween(1, 30);
        assertThat(offer.tokens()).containsExactly(riderPhone);              // not the customer's phone

        String offerId = fetch("/api/riders/me/offers", asha).get(0).get("offerId").asText();
        call(HttpMethod.POST, "/api/riders/me/offers/" + offerId + "/accept", asha, null);
        var accepted = firstTo(customerPhone, "Your rider is on the way");
        assertThat(accepted.message().body()).isEqualTo("Asha · Sedan");

        call(HttpMethod.POST, "/api/trips/" + trip + "/arrive", asha, null);
        assertThat(firstTo(customerPhone, "Your rider has arrived").message().data()).containsEntry("tripId", trip.toString());

        // nothing that identifies or locates anyone, in any notification sent
        for (var sent : recorder.sent) {
            String all = sent.message().title() + " " + sent.message().body() + " " + sent.message().data();
            assertThat(all).doesNotContainIgnoringCase("lat").doesNotContainIgnoringCase("phone").doesNotContain(PICKUP_LAT + "").doesNotContain(ravi.id().toString());
        }
    }

    @Test
    void cancellingAndFailedPaymentsAreTold() throws Exception {
        Actor ravi = customer("Ravi");
        Actor asha = onlineRider("Asha", 0.005);
        String customerPhone = token(), riderPhone = token();
        register(ravi, customerPhone);
        register(asha, riderPhone);

        UUID trip = book(ravi, quote(ravi).get("quoteId").asText());
        String offerId = fetch("/api/riders/me/offers", asha).get(0).get("offerId").asText();
        call(HttpMethod.POST, "/api/riders/me/offers/" + offerId + "/accept", asha, null);
        call(HttpMethod.POST, "/api/trips/" + trip + "/cancel", ravi, null).andExpect(status().isOk());

        assertThat(firstTo(riderPhone, "Trip cancelled").message().data()).containsEntry("type", "TRIP_CANCELLED");
        assertThat(firstTo(customerPhone, "Ride cancelled")).isNotNull();
    }

    @Test
    void aPersonWithNoPhoneRegisteredCostsNothing() throws Exception {
        Actor ravi = customer("Ravi");
        Actor asha = onlineRider("Asha", 0.005);
        completeRide(ravi, asha);
        Thread.sleep(300);
        assertThat(recorder.sent).isEmpty();      // no tokens, so the provider was never called
    }

    @Test
    void aProviderOutageNeverTouchesTheRide() throws Exception {
        recorder.failing = true;
        Actor ravi = customer("Ravi");
        Actor asha = onlineRider("Asha", 0.005);
        register(ravi, token());
        register(asha, token());
        var ride = completeRide(ravi, asha);       // would throw if booking, accepting or completing were affected
        await().atMost(Duration.ofSeconds(5)).until(() -> "CAPTURED".equals(fetch("/api/trips/" + ride.tripId(), ravi).get("paymentStatus").asText()));
    }

    @Test
    void phonesTheProviderSaysAreGoneAreForgotten() throws Exception {
        Actor ravi = customer("Ravi");
        Actor asha = onlineRider("Asha", 0.005);
        String live = token(), gone = token();
        register(ravi, live);
        register(ravi, gone);
        recorder.dead = () -> List.of(gone);

        book(ravi, quote(ravi).get("quoteId").asText());
        register(asha, token());
        String offerId = fetch("/api/riders/me/offers", asha).get(0).get("offerId").asText();
        call(HttpMethod.POST, "/api/riders/me/offers/" + offerId + "/accept", asha, null);

        await().atMost(Duration.ofSeconds(5)).until(() -> devices.findById(gone).isEmpty());
        assertThat(devices.findById(live)).isPresent();
    }
}
