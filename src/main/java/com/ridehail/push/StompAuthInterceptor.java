package com.ridehail.push;

import com.ridehail.security.CurrentUser;
import com.ridehail.security.Role;
import java.util.ArrayList;
import java.util.Set;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Gatekeeper for the STOMP channel:
 *  - CONNECT (and its alias STOMP) must carry "Authorization: Bearer <access token>", otherwise the connection is refused;
 *  - SUBSCRIBE is limited to the caller's own user queues, by role;
 *  - everything that could push data in or change broker state (SEND, ACK, NACK, BEGIN, COMMIT, ABORT) is refused: the
 *    socket is server-to-client only, all actions go through the REST API;
 *  - client-supplied frame headers are reduced to a short allow-list. With a broker relay, whatever headers a client sends
 *    are forwarded to RabbitMQ, where some of them (durable, x-queue-name, x-max-length...) change how queues are created.
 *    The client's bearer token is dropped too, so it never reaches the broker.
 */
class StompAuthInterceptor implements ChannelInterceptor {

    static final String OFFERS = Destinations.USER_OFFERS;
    static final String TRIPS = Destinations.USER_TRIPS;
    /** Live rider position for the customer of the rider's active trip. Customers only. */
    static final String LOCATION = Destinations.USER_LOCATION;

    private static final Set<String> CONNECT_HEADERS = Set.of("accept-version", "host", "heart-beat");
    private static final Set<String> SUBSCRIBE_HEADERS = Set.of("id", "destination", "receipt");
    private static final Set<String> UNSUBSCRIBE_HEADERS = Set.of("id", "receipt");
    private static final Set<String> DISCONNECT_HEADERS = Set.of("receipt");

    /** A push nobody consumed within a minute is stale (positions, offers), so the broker drops it. */
    static final String MESSAGE_TTL_MS = "60000";
    /** A client that stops reading can hold at most this many pending pushes at the broker (oldest dropped first). */
    static final String MAX_PENDING = "100";

    private final JwtDecoder accessDecoder;
    private final boolean relay;

    StompAuthInterceptor(JwtDecoder accessDecoder, boolean relay) {
        this.accessDecoder = accessDecoder;
        this.relay = relay;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) return message;   // heartbeats

        switch (accessor.getCommand()) {
            case CONNECT, STOMP -> {
                accessor.setUser(authenticate(accessor.getFirstNativeHeader("Authorization")));
                keepOnly(accessor, CONNECT_HEADERS);           // also removes Authorization, login, passcode
            }
            case SUBSCRIBE -> {
                if (!(accessor.getUser() instanceof WsPrincipal user) || !allowed(user.role(), accessor.getDestination())) {
                    throw new MessageDeliveryException("Subscription not permitted");
                }
                keepOnly(accessor, SUBSCRIBE_HEADERS);
                if (relay) {
                    // RabbitMQ builds this session's transient queue from these (see Destinations): bound its size and age
                    accessor.setNativeHeader("x-message-ttl", MESSAGE_TTL_MS);
                    accessor.setNativeHeader("x-max-length", MAX_PENDING);
                }
            }
            case UNSUBSCRIBE -> keepOnly(accessor, UNSUBSCRIBE_HEADERS);
            case DISCONNECT -> keepOnly(accessor, DISCONNECT_HEADERS);
            default -> throw new MessageDeliveryException("Clients cannot send " + accessor.getCommand() + "; use the REST API");
        }
        return message;
    }

    private WsPrincipal authenticate(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new MessageDeliveryException("Missing bearer token");
        }
        try {
            Jwt jwt = accessDecoder.decode(header.substring(7).trim());
            return new WsPrincipal(CurrentUser.profileId(jwt).toString(), CurrentUser.role(jwt));
        } catch (JwtException | IllegalArgumentException e) {
            throw new MessageDeliveryException("Invalid token");
        }
    }

    private static boolean allowed(Role role, String destination) {
        if (OFFERS.equals(destination)) return role == Role.RIDER;
        if (TRIPS.equals(destination)) return role == Role.CUSTOMER || role == Role.RIDER;
        if (LOCATION.equals(destination)) return role == Role.CUSTOMER;
        return false;
    }

    private static void keepOnly(StompHeaderAccessor accessor, Set<String> allowedHeaders) {
        for (String name : new ArrayList<>(accessor.toNativeHeaderMap().keySet())) {
            if (!allowedHeaders.contains(name)) accessor.removeNativeHeader(name);
        }
    }
}
