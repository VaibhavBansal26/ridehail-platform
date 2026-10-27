package com.ridehail.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** A plain HTTP client for talking to a running instance, the way a mobile app would. */
public final class ApiClient {
    public record Reply(int status, JsonNode body, java.net.http.HttpHeaders headers) {
        public String text(String field) { return body.get(field).asText(); }
    }

    public record Account(UUID id, String token) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final String base;

    public ApiClient(AppInstance instance) {
        this.base = instance.http();
    }

    public Reply send(String method, String path, String token, Object body) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
            if (token != null) builder.header("Authorization", "Bearer " + token);
            if (body != null) builder.header("Content-Type", "application/json");
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            HttpResponse<String> r = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode parsed = r.body() == null || r.body().isBlank() ? JSON.nullNode() : JSON.readTree(r.body());
            return new Reply(r.statusCode(), parsed, r.headers());
        } catch (Exception e) {
            throw new IllegalStateException(method + " " + path + " failed", e);
        }
    }

    public Reply post(String path, String token, Object body) { return send("POST", path, token, body); }

    public Reply put(String path, String token, Object body) { return send("PUT", path, token, body); }

    public Reply get(String path, String token) { return send("GET", path, token, null); }

    // ---- common flows ----

    public Account registerCustomer(String name) {
        Reply r = post("/api/auth/register/customer", null,
                Map.of("name", name, "phone", UUID.randomUUID().toString(), "password", "password123"));
        require(r, 201);
        Account customer = new Account(UUID.fromString(r.text("profileId")), r.text("accessToken"));
        require(put("/api/customers/me/payment-method", customer.token(), Map.of("token", "tok_ok")), 204);   // a card, so rides can be booked
        return customer;
    }

    public Account registerRider(String name) {
        Reply r = post("/api/auth/register/rider", null,
                Map.of("name", name, "phone", UUID.randomUUID().toString(), "password", "password123", "vehicleType", "SEDAN"));
        require(r, 201);
        return new Account(UUID.fromString(r.text("profileId")), r.text("accessToken"));
    }

    public Reply requireOk(Reply r) { return require(r, 200); }

    public static Reply require(Reply r, int expectedStatus) {
        if (r.status() != expectedStatus) {
            throw new AssertionError("Expected HTTP " + expectedStatus + " but got " + r.status() + ": " + r.body());
        }
        return r;
    }
}
