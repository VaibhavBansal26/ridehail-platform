package com.ridehail.testsupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Speaks STOMP by hand over a WebSocket, to send frames the Spring client would never send. */
public final class RawStomp implements AutoCloseable {
    private final WebSocket socket;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final CountDownLatch closed = new CountDownLatch(1);

    private RawStomp(String url) {
        this.socket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create(url), new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                received.add(data.toString());
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                closed.countDown();
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                closed.countDown();
            }

            @Override
            public void onOpen(WebSocket ws) {
                ws.request(1);
            }
        }).join();
    }

    public static RawStomp open(String url) { return new RawStomp(url); }

    public void frame(String command, String headers, String body) {
        socket.sendText(command + "\n" + headers + "\n\n" + body + "\u0000", true).join();
    }

    public boolean closedWithin(long millis) throws InterruptedException {
        return closed.await(millis, TimeUnit.MILLISECONDS);
    }

    /** Everything the server sent so far, one entry per frame. */
    public List<String> frames() { return List.copyOf(received); }

    public boolean sawConnected() {
        return received.stream().anyMatch(f -> f.startsWith("CONNECTED"));
    }

    @Override
    public void close() {
        socket.abort();
    }
}
