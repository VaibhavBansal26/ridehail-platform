package com.ridehail.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** How the WebSocket layer delivers messages between application instances. */
@ConfigurationProperties(prefix = "ridehail.realtime")
public record RealtimeProperties(Broker broker, Relay relay) {

    public enum Broker {
        /** In-process broker. A message only reaches users connected to the instance that sent it: single instance only. */
        SIMPLE,
        /** An external STOMP broker (RabbitMQ) carries every message, so any instance can reach any connected user. */
        RELAY
    }

    /**
     * Connection to the external broker. The application is the broker's only client: mobile apps connect to the
     * application over WebSocket, never to the broker, so these credentials never leave the server side.
     */
    public record Relay(String host, int port, String virtualHost, String systemLogin, String systemPasscode,
                        String clientLogin, String clientPasscode) {}

    public boolean isRelay() {
        return broker == Broker.RELAY;
    }

    /** @throws IllegalStateException naming exactly what is missing, so a bad deployment fails at startup, not at the first push */
    public void validate() {
        if (!isRelay()) return;
        var missing = new java.util.ArrayList<String>();
        if (relay == null || blank(relay.host())) missing.add("ridehail.realtime.relay.host (RABBITMQ_HOST)");
        if (relay != null && relay.port() <= 0) missing.add("ridehail.realtime.relay.port (RABBITMQ_STOMP_PORT)");
        if (relay == null || blank(relay.systemLogin()) || blank(relay.clientLogin())) missing.add("the relay user (RABBITMQ_USER)");
        if (relay == null || blank(relay.systemPasscode()) || blank(relay.clientPasscode())) missing.add("the relay password (RABBITMQ_PASSWORD)");
        if (!missing.isEmpty()) {
            throw new IllegalStateException("ridehail.realtime.broker=relay needs a RabbitMQ connection, but these are not set: "
                    + String.join(", ", missing));
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
