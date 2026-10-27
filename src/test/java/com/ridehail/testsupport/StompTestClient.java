package com.ridehail.testsupport;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** A mobile app's WebSocket connection. Subscribing waits for the broker's receipt, so it is truly active when it returns. */
public final class StompTestClient implements AutoCloseable {
    private static final long TIMEOUT_MS = 8_000;
    private static final org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler SCHEDULER = newScheduler();

    private final StompSession session;

    private static org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler newScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setDaemon(true);
        scheduler.setThreadNamePrefix("stomp-test-client-");
        scheduler.initialize();
        return scheduler;
    }

    private StompTestClient(StompSession session) {
        this.session = session;
    }

    /** @throws Exception if the server refuses the connection (e.g. no or invalid token) */
    public static StompTestClient connect(String url, String token) throws Exception {
        var client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        client.setTaskScheduler(SCHEDULER);               // needed to time out receipts
        var headers = new StompHeaders();
        if (token != null) headers.add("Authorization", "Bearer " + token);
        StompSession session = client.connectAsync(url, new WebSocketHttpHeaders(), headers,
                new StompSessionHandlerAdapter() {}).get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        session.setAutoReceipt(true);       // every subscribe carries a receipt id, so we can wait for the broker to confirm it
        return new StompTestClient(session);
    }

    public BlockingQueue<Map<String, Object>> subscribe(String destination) throws Exception {
        var headers = new StompHeaders();
        headers.setDestination(destination);
        return subscribe(headers);
    }

    /** For tests that deliberately send unusual SUBSCRIBE headers. */
    public BlockingQueue<Map<String, Object>> subscribe(StompHeaders headers) throws Exception {
        BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
        var receipt = new CompletableFuture<Void>();
        var subscription = session.subscribe(headers, new StompFrameHandler() {
            @Override public Type getPayloadType(StompHeaders h) { return Map.class; }

            @Override @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders h, Object payload) { queue.add((Map<String, Object>) payload); }
        });
        subscription.addReceiptTask(() -> receipt.complete(null));
        subscription.addReceiptLostTask(() -> receipt.completeExceptionally(new IllegalStateException("receipt lost")));
        receipt.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        return queue;
    }

    public boolean isConnected() { return session.isConnected(); }

    public void send(String destination, String payload) { session.send(destination, payload); }

    /** Waits for a message of the given type, skipping other types that arrive first. */
    public static Map<String, Object> await(BlockingQueue<Map<String, Object>> queue, String type) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> m = queue.poll(250, TimeUnit.MILLISECONDS);
            if (m != null && type.equals(m.get("type"))) return m;
        }
        throw new AssertionError("No " + type + " message received within " + TIMEOUT_MS + " ms");
    }

    /** Asserts nothing of the given type arrives within the window. */
    public static void expectNone(BlockingQueue<Map<String, Object>> queue, String type, long windowMs) throws Exception {
        long deadline = System.currentTimeMillis() + windowMs;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> m = queue.poll(100, TimeUnit.MILLISECONDS);
            if (m != null && type.equals(m.get("type"))) throw new AssertionError("Unexpected " + type + ": " + m);
        }
    }

    @Override
    public void close() {
        if (session.isConnected()) session.disconnect();
    }
}
