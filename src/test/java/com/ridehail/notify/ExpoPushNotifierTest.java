package com.ridehail.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Expo adapter against a local stand-in for Expo's push service (not against Expo itself). */
class ExpoPushNotifierTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private volatile Function<JsonNode, String> answer;
    private volatile int status = 200;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/send", ex -> {
            JsonNode body = json.readTree(ex.getRequestBody());
            requests.add(body);
            authHeaders.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            byte[] out = (status == 200 ? answer.apply(body) : "{\"errors\":[]}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        answer = body -> tickets(body.size(), Map.of());
    }

    @AfterEach
    void stop() { server.stop(0); }

    private ExpoPushNotifier notifier(String accessToken) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/send";
        return new ExpoPushNotifier(new NotificationProperties("expo", url, accessToken, 10), json);
    }

    private static String tickets(int n, Map<Integer, String> errors) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String err = errors.get(i);
            parts.add(err == null ? "{\"status\":\"ok\",\"id\":\"t" + i + "\"}"
                    : "{\"status\":\"error\",\"message\":\"x\",\"details\":{\"error\":\"" + err + "\"}}");
        }
        return "{\"data\":[" + String.join(",", parts) + "]}";
    }

    private static final PushNotifier.Message MESSAGE = new PushNotifier.Message("New ride request", "USD 10.15 · 162 m to pickup", Map.of("type", "OFFER_RECEIVED"), 12);

    @Test
    void sendsOneMessagePerPhoneWithTheFieldsExpoNeeds() {
        assertThat(notifier(null).send(List.of("ExponentPushToken[aaaaaaaa]", "ExponentPushToken[bbbbbbbb]"), MESSAGE)).isEmpty();

        assertThat(requests).hasSize(1);
        JsonNode body = requests.get(0);
        assertThat(body).hasSize(2);
        JsonNode first = body.get(0);
        assertThat(first.get("to").asText()).isEqualTo("ExponentPushToken[aaaaaaaa]");
        assertThat(first.get("title").asText()).isEqualTo("New ride request");
        assertThat(first.get("body").asText()).isEqualTo("USD 10.15 · 162 m to pickup");
        assertThat(first.get("data").get("type").asText()).isEqualTo("OFFER_RECEIVED");
        assertThat(first.get("priority").asText()).isEqualTo("high");
        assertThat(first.get("ttl").asInt()).isEqualTo(12);
        assertThat(authHeaders.get(0)).isEqualTo("null");            // no access token configured: no Authorization header
    }

    @Test
    void omitsTtlWhenThereIsNoDeadlineAndSendsTheAccessTokenWhenConfigured() {
        notifier("secret-expo-token").send(List.of("ExponentPushToken[aaaaaaaa]"), new PushNotifier.Message("t", "b", Map.of(), 0));
        assertThat(requests.get(0).get(0).has("ttl")).isFalse();
        assertThat(authHeaders.get(0)).isEqualTo("Bearer secret-expo-token");
    }

    @Test
    void splitsBigAudiencesIntoBatchesOfAHundred() {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 250; i++) tokens.add("ExponentPushToken[token" + String.format("%05d", i) + "]");
        notifier(null).send(tokens, MESSAGE);
        assertThat(requests).extracting(JsonNode::size).containsExactly(100, 100, 50);
    }

    @Test
    void reportsOnlyThePhonesExpoSaysAreGone() {
        answer = body -> tickets(body.size(), Map.of(1, "DeviceNotRegistered", 2, "MessageRateExceeded"));
        var dead = notifier(null).send(List.of("ExponentPushToken[aaaaaaaa]", "ExponentPushToken[bbbbbbbb]", "ExponentPushToken[cccccccc]"), MESSAGE);
        assertThat(dead).containsExactly("ExponentPushToken[bbbbbbbb]");   // a rate limit is not a dead phone
    }

    @Test
    void aProviderErrorIsSwallowedNotThrown() {
        status = 500;
        assertThat(notifier(null).send(List.of("ExponentPushToken[aaaaaaaa]"), MESSAGE)).isEmpty();
    }

    @Test
    void anUnreachableProviderIsSwallowedToo() {
        server.stop(0);
        assertThat(notifier(null).send(List.of("ExponentPushToken[aaaaaaaa]"), MESSAGE)).isEmpty();
    }

    @Test
    void aBadBatchDoesNotStopTheNextOne() {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 150; i++) tokens.add("ExponentPushToken[token" + String.format("%05d", i) + "]");
        answer = body -> body.size() == 100 ? "not json" : tickets(body.size(), Map.of(0, "DeviceNotRegistered"));
        var dead = notifier(null).send(tokens, MESSAGE);
        assertThat(requests).hasSize(2);
        assertThat(dead).containsExactly("ExponentPushToken[token00100]");
    }
}
