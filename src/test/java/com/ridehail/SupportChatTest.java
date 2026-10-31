package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ridehail.notify.PushNotifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;

/** Support chat: a person writes, an admin answers, and nobody sees anybody else's conversation. */
class SupportChatTest extends ApiTestBase {

    static class Recorder implements PushNotifier {
        record Sent(List<String> tokens, Message message) {}
        final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());

        @Override
        public List<String> send(List<String> tokens, Message message) {
            sent.add(new Sent(List.copyOf(tokens), message));
            return List.of();
        }
    }

    @TestConfiguration
    static class Config {
        @Bean @Primary Recorder supportRecorder() { return new Recorder(); }
    }

    @Autowired Recorder recorder;

    @BeforeEach
    void reset() { recorder.sent.clear(); }

    private static String body(String text) { return "{\"body\":\"" + text + "\"}"; }

    private JsonNode say(Actor who, String text) throws Exception {
        return body(call(HttpMethod.POST, "/api/support/messages", who, body(text)).andExpect(status().isCreated()));
    }

    private JsonNode waiting(Actor admin) throws Exception {
        return fetch("/api/admin/support?filter=awaiting&size=100", admin).get("items");
    }

    private JsonNode itemFor(JsonNode items, Actor user) {
        return StreamSupport.stream(items.spliterator(), false).filter(i -> i.get("userId").asText().equals(user.id().toString())).findFirst().orElse(null);
    }

    @Test
    void aPersonWithNoConversationYetSeesAnEmptyOne() throws Exception {
        JsonNode v = fetch("/api/support", customer("Ravi"));
        assertThat(v.get("messages")).isEmpty();
        assertThat(v.get("unread").asInt()).isZero();
    }

    @Test
    void aCustomerWritesAndAnAdminSeesThemWaiting() throws Exception {
        Actor ravi = customer("Ravi");
        Actor admin = admin();

        JsonNode v = say(ravi, "My driver took a long route.");

        assertThat(v.get("messages")).hasSize(1);
        assertThat(v.get("messages").get(0).get("from").asText()).isEqualTo("USER");
        JsonNode item = itemFor(waiting(admin), ravi);
        assertThat(item).isNotNull();
        assertThat(item.get("name").asText()).isEqualTo("Ravi");
        assertThat(item.get("role").asText()).isEqualTo("CUSTOMER");
        assertThat(item.get("lastPreview").asText()).startsWith("My driver");
        assertThat(fetch("/api/admin/support/summary", admin).get("awaiting").asInt()).isPositive();
    }

    @Test
    void anAdminRepliesThePersonIsToldAndTheirUnreadCountClears() throws Exception {
        Actor ravi = customer("Ravi");
        Actor admin = admin();
        String phone = "ExponentPushToken[" + UUID.randomUUID().toString().replace("-", "") + "]";
        call(HttpMethod.PUT, "/api/devices", ravi, "{\"token\":\"" + phone + "\",\"platform\":\"ios\"}").andExpect(status().isNoContent());
        say(ravi, "Where is my refund?");
        String id = itemFor(waiting(admin), ravi).get("id").asText();

        call(HttpMethod.POST, "/api/admin/support/" + id + "/reply", admin, body("It was issued today.")).andExpect(status().isCreated());

        assertThat(itemFor(waiting(admin), ravi)).isNull();                                   // answered: no longer waiting
        JsonNode mine = fetch("/api/support", ravi);
        assertThat(mine.get("messages")).hasSize(2);
        assertThat(mine.get("messages").get(1).get("from").asText()).isEqualTo("STAFF");
        assertThat(mine.get("unread").asInt()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(5)).until(() -> recorder.sent.stream().anyMatch(s -> s.tokens().contains(phone)));
        var push = recorder.sent.stream().filter(s -> s.tokens().contains(phone)).findFirst().orElseThrow();
        assertThat(push.message().data()).containsEntry("type", "SUPPORT_REPLY");
        assertThat(push.message().body()).doesNotContain("refund");                           // what staff wrote stays off the lock screen

        call(HttpMethod.POST, "/api/support/read", ravi, null).andExpect(status().isNoContent());
        assertThat(fetch("/api/support", ravi).get("unread").asInt()).isZero();
    }

    @Test
    void aMessageAfterResolvingReopensTheConversation() throws Exception {
        Actor ravi = customer("Ravi");
        Actor admin = admin();
        say(ravi, "Hello?");
        String id = itemFor(waiting(admin), ravi).get("id").asText();

        call(HttpMethod.POST, "/api/admin/support/" + id + "/resolve", admin, null).andExpect(status().isNoContent());
        assertThat(itemFor(waiting(admin), ravi)).isNull();
        assertThat(fetch("/api/support", ravi).get("status").asText()).isEqualTo("RESOLVED");

        say(ravi, "One more thing.");
        assertThat(itemFor(waiting(admin), ravi)).isNotNull();
        assertThat(fetch("/api/support", ravi).get("status").asText()).isEqualTo("OPEN");
    }

    @Test
    void theLongestWaitComesFirstAndFurtherMessagesDoNotResetTheClock() throws Exception {
        Actor first = customer("Asha C"), second = customer("Meera C");
        Actor admin = admin();
        say(first, "first");
        clock.advance(Duration.ofMinutes(10));
        say(second, "second");
        clock.advance(Duration.ofMinutes(10));
        say(first, "first again");                                   // still the same wait

        JsonNode items = waiting(admin);
        int a = indexOf(items, first), b = indexOf(items, second);
        assertThat(a).isLessThan(b);
        assertThat(fetch("/api/admin/support/summary", admin).get("oldestWaitingSeconds").asLong()).isGreaterThanOrEqualTo(1200);
    }

    private int indexOf(JsonNode items, Actor u) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).get("userId").asText().equals(u.id().toString())) return i;
        return -1;
    }

    @Test
    void ridersUseTheSameChatAndAreMarkedAsRiders() throws Exception {
        Actor asha = onlineRider("Asha", 0.005);
        say(asha, "My payout is late.");
        JsonNode item = itemFor(waiting(admin()), asha);
        assertThat(item.get("role").asText()).isEqualTo("RIDER");
        assertThat(item.get("name").asText()).isEqualTo("Asha");
    }

    @Test
    void nobodyReadsAnotherPersonsConversationAndPeopleCannotUseTheStaffSide() throws Exception {
        Actor ravi = customer("Ravi"), meera = customer("Meera");
        say(ravi, "private matter");

        assertThat(fetch("/api/support", meera).get("messages")).isEmpty();           // mine means mine: there is no id to guess
        String id = itemFor(waiting(admin()), ravi).get("id").asText();
        call(HttpMethod.GET, "/api/admin/support", ravi, null).andExpect(status().isForbidden());
        call(HttpMethod.GET, "/api/admin/support/" + id, meera, null).andExpect(status().isForbidden());
        call(HttpMethod.POST, "/api/admin/support/" + id + "/reply", ravi, body("I am staff")).andExpect(status().isForbidden());
        call(HttpMethod.GET, "/api/support", null, null).andExpect(status().isUnauthorized());
        call(HttpMethod.GET, "/api/support", admin(), null).andExpect(status().isForbidden());   // admins answer; they do not have a chat of their own
    }

    @Test
    void emptyOversizedAndOddMessagesAreHandled() throws Exception {
        Actor ravi = customer("Ravi");
        call(HttpMethod.POST, "/api/support/messages", ravi, body("   ")).andExpect(status().isBadRequest());
        call(HttpMethod.POST, "/api/support/messages", ravi, "{}").andExpect(status().isBadRequest());
        call(HttpMethod.POST, "/api/support/messages", ravi, body("x".repeat(2001))).andExpect(status().isBadRequest());
        JsonNode v = say(ravi, "line one\\nline two\\u0007");
        assertThat(v.get("messages").get(0).get("body").asText()).isEqualTo("line one\nline two");   // newlines kept, control characters dropped
        say(ravi, "<script>alert(1)</script>");                                                     // stored as text; clients render it as text
        assertThat(fetch("/api/support", ravi).get("messages").get(1).get("body").asText()).isEqualTo("<script>alert(1)</script>");
    }

    @Test
    void aTripCanBeAttachedOnlyIfItIsTheSendersOwn() throws Exception {
        Actor ravi = customer("Ravi"), meera = customer("Meera");
        Actor asha = onlineRider("Asha", 0.005);
        Ride ride = completeRide(ravi, asha);

        call(HttpMethod.POST, "/api/support/messages", ravi, "{\"body\":\"About this ride\",\"tripId\":\"" + ride.tripId() + "\"}").andExpect(status().isCreated());
        call(HttpMethod.POST, "/api/support/messages", meera, "{\"body\":\"About their ride\",\"tripId\":\"" + ride.tripId() + "\"}").andExpect(status().isBadRequest());
        assertThat(fetch("/api/support", ravi).get("messages").get(0).get("tripId").asText()).isEqualTo(ride.tripId().toString());
    }

    @Test
    void aFloodIsSlowedDownButAHelpfulPaceIsNot() throws Exception {
        Actor ravi = customer("Ravi");
        for (int i = 0; i < 20; i++) say(ravi, "message " + i);
        call(HttpMethod.POST, "/api/support/messages", ravi, body("one too many")).andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofHours(1).plusMinutes(1));
        say(ravi, "fine again");
        assertThat(fetch("/api/support", ravi).get("messages")).hasSize(21);
    }

    @Test
    void staffActionsAreAuditedWithoutCopyingTheMessage() throws Exception {
        Actor ravi = customer("Ravi");
        Actor admin = admin();
        say(ravi, "secret details");
        String id = itemFor(waiting(admin), ravi).get("id").asText();
        call(HttpMethod.POST, "/api/admin/support/" + id + "/reply", admin, body("Noted.")).andExpect(status().isCreated());
        call(HttpMethod.POST, "/api/admin/support/" + id + "/resolve", admin, null).andExpect(status().isNoContent());

        for (String action : new String[] {"SUPPORT_REPLIED", "SUPPORT_RESOLVED"}) {
            JsonNode rows = fetch("/api/admin/audit?action=" + action, admin).get("items");
            assertThat(StreamSupport.stream(rows.spliterator(), false).filter(r -> r.get("targetId").asText().equals(id)).toList()).hasSize(1);
            assertThat(rows.toString()).doesNotContain("secret details").doesNotContain("Noted.");
        }
    }

    @Test
    void responseShapesMatchTheContractFixtures() throws Exception {
        Actor ravi = customer("Ravi");
        Actor admin = admin();
        say(ravi, "Hello support.");
        String id = itemFor(waiting(admin), ravi).get("id").asText();
        call(HttpMethod.POST, "/api/admin/support/" + id + "/reply", admin, body("Hi Ravi.")).andExpect(status().isCreated());
        ContractFixtures.check(json, "support-thread.json", fetch("/api/admin/support/" + id, admin));
        ContractFixtures.check(json, "support-inbox.json", fetch("/api/admin/support?size=1", admin));
    }
}
