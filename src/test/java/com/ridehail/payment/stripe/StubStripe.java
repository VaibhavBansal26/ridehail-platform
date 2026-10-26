package com.ridehail.payment.stripe;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A tiny local HTTP server that speaks just enough of Stripe's wire format for the real stripe-java SDK to talk
 * to it. Tests script responses per route ("POST /v1/payment_intents") and then inspect exactly what was sent.
 */
public final class StubStripe {

    public record Call(String method, String path, Map<String, String> headers, Map<String, String> form) {
        public String header(String name) { return headers.get(name.toLowerCase()); }
        public String form(String name) { return form.get(name); }
    }

    private record Reply(int status, String body) {}

    private final HttpServer server;
    private final Map<String, Deque<Reply>> once = new ConcurrentHashMap<>();
    private final Map<String, Reply> sticky = new ConcurrentHashMap<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    public StubStripe() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    public void stop() { server.stop(0); }

    /** Every request to this route gets this reply (unless a one-shot reply is queued first). */
    public void reply(String route, int status, String body) { sticky.put(route, new Reply(status, body)); }

    /** The next request to this route gets this reply, once. */
    public void replyOnce(String route, int status, String body) {
        once.computeIfAbsent(route, r -> new ArrayDeque<>()).add(new Reply(status, body));
    }

    public void reset() {
        once.clear();
        sticky.clear();
        calls.clear();
    }

    public List<Call> calls(String route) {
        return calls.stream().filter(c -> (c.method() + " " + c.path()).equals(route)).toList();
    }

    public Call only(String route) {
        List<Call> matching = calls(route);
        if (matching.size() != 1) throw new AssertionError("Expected exactly one " + route + " but saw " + matching.size());
        return matching.get(0);
    }

    public int totalCalls() { return calls.size(); }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
        String route = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
        calls.add(new Call(ex.getRequestMethod(), ex.getRequestURI().getPath(), headers, parseForm(body)));

        Deque<Reply> queue = once.get(route);
        Reply reply = queue != null && !queue.isEmpty() ? queue.poll() : sticky.get(route);
        if (reply == null) {
            reply = new Reply(500, error("api_error", null, "No stub for " + route));
        }
        byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.getResponseHeaders().add("Request-Id", "req_stub");
        ex.sendResponseHeaders(reply.status(), out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        if (body.isBlank()) return form;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            form.put(k, v);
        }
        return form;
    }

    // ---- canned bodies, in Stripe's real shapes ----

    public static String error(String type, String code, String message) {
        return "{\"error\":{\"type\":\"" + type + "\"" + (code == null ? "" : ",\"code\":\"" + code + "\"")
                + ",\"message\":\"" + message + "\"}}";
    }

    public static String cardError(String code, String declineCode) {
        return "{\"error\":{\"type\":\"card_error\",\"code\":\"" + code + "\""
                + (declineCode == null ? "" : ",\"decline_code\":\"" + declineCode + "\"")
                + ",\"message\":\"Your card was declined.\"}}";
    }

    public static String paymentIntent(String id, String status) {
        return "{\"id\":\"" + id + "\",\"object\":\"payment_intent\",\"status\":\"" + status
                + "\",\"currency\":\"usd\",\"amount\":999}";
    }

    public static List<String> routes() { return new ArrayList<>(); }
}
