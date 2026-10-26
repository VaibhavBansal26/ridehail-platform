package com.ridehail.push;

import com.ridehail.security.CurrentUser;
import com.ridehail.security.Role;
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
 *  - CONNECT must carry "Authorization: Bearer <access token>", otherwise the connection is refused;
 *  - SUBSCRIBE is limited to the caller's own user queues, by role;
 *  - SEND is refused: the socket is server-to-client only, all actions go through the REST API.
 */
class StompAuthInterceptor implements ChannelInterceptor {

    static final String OFFERS = "/user/queue/offers";
    static final String TRIPS = "/user/queue/trips";

    private final JwtDecoder accessDecoder;

    StompAuthInterceptor(JwtDecoder accessDecoder) {
        this.accessDecoder = accessDecoder;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) return message;

        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT) {
            accessor.setUser(authenticate(accessor.getFirstNativeHeader("Authorization")));
        } else if (command == StompCommand.SUBSCRIBE) {
            if (!(accessor.getUser() instanceof WsPrincipal user) || !allowed(user.role(), accessor.getDestination())) {
                throw new MessageDeliveryException("Subscription not permitted");
            }
        } else if (command == StompCommand.SEND) {
            throw new MessageDeliveryException("Clients cannot send messages; use the REST API");
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
        return false;
    }
}
