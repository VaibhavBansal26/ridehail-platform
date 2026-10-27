package com.ridehail.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

/** The STOMP gatekeeper in isolation: who may connect, what they may ask for, and what reaches the broker. */
class StompAuthInterceptorTest {

    final UUID riderId = UUID.randomUUID();
    final UUID customerId = UUID.randomUUID();

    final StompAuthInterceptor relay = new StompAuthInterceptor(this::decode, true);
    final StompAuthInterceptor simple = new StompAuthInterceptor(this::decode, false);

    Jwt decode(String token) {
        return switch (token) {
            case "rider-token" -> jwt("RIDER", riderId);
            case "customer-token" -> jwt("CUSTOMER", customerId);
            default -> throw new JwtException("bad token");
        };
    }

    static Jwt jwt(String role, UUID profile) {
        return new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "HS256"), Map.of("role", role, "pid", profile.toString()));
    }

    /** A frame as it would arrive from a client; the accessor stays mutable like on the real channel. */
    record Frame(StompHeaderAccessor accessor, Message<?> message) {}

    static Frame frame(StompCommand command, Map<String, String> nativeHeaders, String destination, String userToken, StompAuthInterceptorTest t) {
        var accessor = StompHeaderAccessor.create(command);
        nativeHeaders.forEach(accessor::addNativeHeader);
        if (destination != null) accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        if (userToken != null) accessor.setUser(new WsPrincipal(userToken.equals("rider") ? t.riderId.toString() : t.customerId.toString(),
                userToken.equals("rider") ? com.ridehail.security.Role.RIDER : com.ridehail.security.Role.CUSTOMER));
        return new Frame(accessor, MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()));
    }

    // ---------------------------------------------------------------- connecting

    @Test
    void connectNeedsAValidBearerTokenAndTheSameRuleCoversTheStompAliasOfConnect() {
        for (StompCommand connect : List.of(StompCommand.CONNECT, StompCommand.STOMP)) {
            assertThatThrownBy(() -> relay.preSend(frame(connect, Map.of(), null, null, this).message(), null))
                    .as("%s without a token", connect).isInstanceOf(MessageDeliveryException.class);
            assertThatThrownBy(() -> relay.preSend(frame(connect, Map.of("Authorization", "Bearer nope"), null, null, this).message(), null))
                    .as("%s with a bad token", connect).isInstanceOf(MessageDeliveryException.class);
            assertThatThrownBy(() -> relay.preSend(frame(connect, Map.of("Authorization", "Basic abc"), null, null, this).message(), null))
                    .as("%s with the wrong scheme", connect).isInstanceOf(MessageDeliveryException.class);

            Frame ok = frame(connect, Map.of("Authorization", "Bearer rider-token"), null, null, this);
            relay.preSend(ok.message(), null);
            assertThat(ok.accessor().getUser()).isInstanceOf(WsPrincipal.class);
            assertThat(ok.accessor().getUser().getName()).isEqualTo(riderId.toString());
        }
    }

    @Test
    void theBearerTokenAndAnyOtherUnexpectedHeaderNeverLeaveForTheBroker() {
        Frame connect = frame(StompCommand.CONNECT, Map.of("Authorization", "Bearer customer-token", "accept-version", "1.2",
                "heart-beat", "10000,10000", "host", "x", "login", "admin", "passcode", "secret", "x-evil", "1"), null, null, this);

        relay.preSend(connect.message(), null);

        assertThat(connect.accessor().toNativeHeaderMap().keySet()).containsExactlyInAnyOrder("accept-version", "heart-beat", "host");
    }

    // ---------------------------------------------------------------- subscribing

    @Test
    void ridersAndCustomersMaySubscribeOnlyToTheirOwnStreams() {
        assertSubscribe("rider", Destinations.USER_OFFERS, true);
        assertSubscribe("rider", Destinations.USER_TRIPS, true);
        assertSubscribe("rider", Destinations.USER_LOCATION, false);        // the customers' live feed
        assertSubscribe("customer", Destinations.USER_TRIPS, true);
        assertSubscribe("customer", Destinations.USER_LOCATION, true);
        assertSubscribe("customer", Destinations.USER_OFFERS, false);       // riders' offers
    }

    @Test
    void noOneMaySubscribeToTheBrokersInternalDestinationsOrToAnotherUsersSession() {
        for (String forbidden : List.of("/topic/simp-user-registry", "/topic/unresolved-user-destination",
                "/topic/offers-userSOMEONE-ELSES-SESSION", "/topic/offers", "/topic/#", "/topic/*", "/queue/offers",
                "/exchange/amq.topic/#", "/amq/queue/anything", "/temp-queue/x", "/user/topic/other", "")) {
            assertSubscribe("rider", forbidden, false);
            assertSubscribe("customer", forbidden, false);
        }
    }

    @Test
    void anUnauthenticatedSessionCannotSubscribeToAnything() {
        assertThatThrownBy(() -> relay.preSend(frame(StompCommand.SUBSCRIBE, Map.of("id", "1"), Destinations.USER_TRIPS, null, this).message(), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void clientSuppliedSubscribeHeadersCannotChangeHowTheBrokerBuildsTheirQueue() {
        Frame sub = frame(StompCommand.SUBSCRIBE, Map.of("id", "sub-1", "receipt", "r-1",
                "durable", "true", "auto-delete", "false", "exclusive", "false", "x-queue-name", "hijack",
                "x-max-length", "999999", "x-message-ttl", "0", "x-dead-letter-exchange", "amq.topic", "ack", "client-individual"),
                Destinations.USER_TRIPS, "customer", this);

        relay.preSend(sub.message(), null);

        Map<String, List<String>> sent = sub.accessor().toNativeHeaderMap();
        assertThat(sent.keySet()).containsExactlyInAnyOrder("id", "receipt", "destination", "x-message-ttl", "x-max-length");
        assertThat(sent.get("x-message-ttl")).containsExactly(StompAuthInterceptor.MESSAGE_TTL_MS);   // ours, not theirs
        assertThat(sent.get("x-max-length")).containsExactly(StompAuthInterceptor.MAX_PENDING);
    }

    @Test
    void withTheInMemoryBrokerNoBrokerSettingsAreAdded() {
        Frame sub = frame(StompCommand.SUBSCRIBE, Map.of("id", "sub-1", "x-queue-name", "hijack"), Destinations.USER_TRIPS, "customer", this);

        simple.preSend(sub.message(), null);

        assertThat(sub.accessor().toNativeHeaderMap().keySet()).containsExactlyInAnyOrder("id", "destination");
    }

    // ---------------------------------------------------------------- everything else a client might send

    @Test
    void clientsCannotPublishAcknowledgeOrOpenTransactions() {
        for (StompCommand forbidden : List.of(StompCommand.SEND, StompCommand.ACK, StompCommand.NACK,
                StompCommand.BEGIN, StompCommand.COMMIT, StompCommand.ABORT)) {
            assertThatThrownBy(() -> relay.preSend(frame(forbidden, Map.of(), "/topic/x", "rider", this).message(), null))
                    .as(forbidden.name()).isInstanceOf(MessageDeliveryException.class);
        }
    }

    @Test
    void unsubscribingAndDisconnectingStillWorkAndStayClean() {
        Frame unsub = frame(StompCommand.UNSUBSCRIBE, Map.of("id", "sub-1", "x-evil", "1"), null, "rider", this);
        relay.preSend(unsub.message(), null);
        assertThat(unsub.accessor().toNativeHeaderMap().keySet()).containsExactly("id");

        Frame bye = frame(StompCommand.DISCONNECT, Map.of("receipt", "bye", "x-evil", "1"), null, "rider", this);
        relay.preSend(bye.message(), null);
        assertThat(bye.accessor().toNativeHeaderMap().keySet()).containsExactly("receipt");
    }

    @Test
    void heartbeatsPassThrough() {
        var heartbeat = StompHeaderAccessor.createForHeartbeat();
        heartbeat.setLeaveMutable(true);
        Message<?> message = MessageBuilder.createMessage(new byte[0], heartbeat.getMessageHeaders());
        assertThat(relay.preSend(message, null)).isSameAs(message);
    }

    // ---------------------------------------------------------------- helpers

    private void assertSubscribe(String who, String destination, boolean allowed) {
        Frame sub = frame(StompCommand.SUBSCRIBE, Map.of("id", "1"), destination, who, this);
        if (allowed) {
            relay.preSend(sub.message(), null);
        } else {
            assertThatThrownBy(() -> relay.preSend(sub.message(), null)).as("%s subscribing to '%s'", who, destination)
                    .isInstanceOf(MessageDeliveryException.class);
        }
    }
}
