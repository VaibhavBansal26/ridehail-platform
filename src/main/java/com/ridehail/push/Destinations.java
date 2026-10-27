package com.ridehail.push;

/**
 * The per-user realtime streams. The server addresses them with {@code convertAndSendToUser(user, OFFERS, ...)}; a client
 * subscribes to the {@code /user/...} form.
 *
 * They live under {@code /topic} on purpose. Spring maps a user destination to one destination per WebSocket session, and
 * RabbitMQ treats {@code /queue/...} destinations as durable named queues that are never deleted and that publishers
 * re-create: one orphan queue per session, forever. {@code /topic/...} subscriptions get a transient queue owned by the
 * subscriber's connection, removed when it goes away, and a publish to a departed session leaves nothing behind.
 * (The same names are used with the in-memory broker so behaviour never differs between development and production.)
 */
public final class Destinations {
    private Destinations() {}

    public static final String OFFERS = "/topic/offers";
    public static final String TRIPS = "/topic/trips";
    public static final String LOCATION = "/topic/location";

    public static final String USER_OFFERS = "/user" + OFFERS;
    public static final String USER_TRIPS = "/user" + TRIPS;
    public static final String USER_LOCATION = "/user" + LOCATION;
}
