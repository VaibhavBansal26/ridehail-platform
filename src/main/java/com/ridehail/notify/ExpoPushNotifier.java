package com.ridehail.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends through Expo's push service (https://docs.expo.dev/push-notifications/sending-notifications/), which forwards to Apple and
 * Google. One request carries up to 100 messages; the answer lists a "ticket" per message in the same order, and a ticket whose
 * error is DeviceNotRegistered means the app was uninstalled, so we forget that token.
 */
class ExpoPushNotifier implements PushNotifier {
    private static final Logger log = LoggerFactory.getLogger(ExpoPushNotifier.class);
    private static final int BATCH = 100;

    private final NotificationProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    ExpoPushNotifier(NotificationProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    @Override
    public List<String> send(List<String> tokens, Message m) {
        List<String> dead = new ArrayList<>();
        for (int from = 0; from < tokens.size(); from += BATCH) {
            List<String> batch = tokens.subList(from, Math.min(from + BATCH, tokens.size()));
            try {
                dead.addAll(sendBatch(batch, m));
            } catch (IOException | RuntimeException e) {
                log.warn("Could not send a push notification batch of {}: {}", batch.size(), e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return dead;
            }
        }
        return dead;
    }

    private List<String> sendBatch(List<String> tokens, Message m) throws IOException, InterruptedException {
        List<Map<String, Object>> body = new ArrayList<>();
        for (String token : tokens) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("to", token);
            msg.put("title", m.title());
            msg.put("body", m.body());
            msg.put("data", m.data());
            msg.put("sound", "default");
            msg.put("priority", "high");          // wake the phone: an offer is worthless a minute later
            msg.put("channelId", "default");
            if (m.ttlSeconds() > 0) msg.put("ttl", m.ttlSeconds());
            body.add(msg);
        }
        var request = HttpRequest.newBuilder(URI.create(props.expoUrl()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (props.expoAccessToken() != null && !props.expoAccessToken().isBlank()) {
            request.header("Authorization", "Bearer " + props.expoAccessToken());
        }
        HttpResponse<String> res = http.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) throw new IOException("Expo answered HTTP " + res.statusCode());

        List<String> dead = new ArrayList<>();
        JsonNode tickets = json.readTree(res.body()).path("data");
        for (int i = 0; i < tickets.size() && i < tokens.size(); i++) {
            JsonNode t = tickets.get(i);
            if ("error".equals(t.path("status").asText())) {
                String error = t.path("details").path("error").asText("");
                if ("DeviceNotRegistered".equals(error)) dead.add(tokens.get(i));
                else log.warn("Expo refused a push notification: {} {}", error, t.path("message").asText(""));
            }
        }
        return dead;
    }
}
