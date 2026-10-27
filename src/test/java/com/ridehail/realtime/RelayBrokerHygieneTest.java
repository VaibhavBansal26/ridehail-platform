package com.ridehail.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ridehail.push.Destinations;
import com.ridehail.testsupport.ApiClient;
import com.ridehail.testsupport.ApiClient.Account;
import com.ridehail.testsupport.AppInstance;
import com.ridehail.testsupport.Containers;
import com.ridehail.testsupport.RabbitAdmin;
import com.ridehail.testsupport.RawStomp;
import com.ridehail.testsupport.StompTestClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaders;

/**
 * What the relay does to the real broker: queue lifecycle, how many connections clients can cause, and that hostile STOMP
 * input never gets that far. One application instance against a real RabbitMQ.
 */
@EnabledIf("com.ridehail.testsupport.Containers#dockerAvailable")
class RelayBrokerHygieneTest {

    static AppInstance app;
    static ApiClient api;

    @BeforeAll
    static void start() {
        Containers.rabbit();
        var p = new LinkedHashMap<>(AppInstance.quiet());
        p.put("spring.datasource.url", "jdbc:h2:mem:relay-hygiene;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        p.put("ridehail.realtime.broker", "relay");
        p.put("ridehail.realtime.relay.host", Containers.rabbitHost());
        p.put("ridehail.realtime.relay.port", String.valueOf(Containers.rabbitStompPort()));
        p.put("ridehail.realtime.relay.system-login", Containers.RABBIT_USER);
        p.put("ridehail.realtime.relay.system-passcode", Containers.RABBIT_PASSWORD);
        p.put("ridehail.realtime.relay.client-login", Containers.RABBIT_USER);
        p.put("ridehail.realtime.relay.client-passcode", Containers.RABBIT_PASSWORD);
        app = AppInstance.start("hygiene", p);
        api = new ApiClient(app);
        await().atMost(Duration.ofSeconds(10)).until(() -> RabbitAdmin.connections() >= 1);   // our system connection
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
    }

    // ---------------------------------------------------------------- queues

    @Test
    void everySessionGetsATransientBoundedQueueAndNothingIsLeftBehindWhenItEnds() throws Exception {
        Account customer = api.registerCustomer("customer");
        var before = queueNames();

        try (var socket = StompTestClient.connect(app.ws(), customer.token())) {
            // a client trying to dictate how the broker builds its queue
            var hostile = new StompHeaders();
            hostile.setDestination(Destinations.USER_TRIPS);
            hostile.add("durable", "true");
            hostile.add("auto-delete", "false");
            hostile.add("x-queue-name", "hijacked");
            hostile.add("x-max-length", "999999");
            socket.subscribe(hostile);
            socket.subscribe(Destinations.USER_LOCATION);

            var mine = RabbitAdmin.queues().stream().filter(q -> !before.contains(q.name())).toList();
            assertThat(mine).hasSize(2);
            assertThat(mine).allSatisfy(q -> {
                assertThat(q.durable()).as("%s durable", q.name()).isFalse();           // not written to the broker's disk metadata
                assertThat(q.autoDelete()).as("%s auto-delete", q.name()).isTrue();     // goes when the connection does
                assertThat(q.arguments()).contains("x-message-ttl").contains("60000");  // stale pushes are dropped
                assertThat(q.arguments()).contains("x-max-length").contains("100");     // a stalled client cannot hoard messages
                assertThat(q.arguments()).doesNotContain("999999");                     // the client's own settings were ignored
            });
            assertThat(RabbitAdmin.queues()).noneMatch(q -> q.name().contains("hijacked"));
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(queueNames()).isEqualTo(before));
    }

    @Test
    void aClientThatVanishesWithoutSayingGoodbyeLeavesNothingBehindEither() throws Exception {
        Account customer = api.registerCustomer("customer");
        var before = queueNames();

        var raw = RawStomp.open(app.ws());
        raw.frame("CONNECT", "accept-version:1.2\nhost:/\nAuthorization:Bearer " + customer.token(), "");
        await().atMost(Duration.ofSeconds(5)).until(raw::sawConnected);
        raw.frame("SUBSCRIBE", "id:s1\ndestination:" + Destinations.USER_TRIPS + "\nreceipt:r1", "");
        await().atMost(Duration.ofSeconds(5)).until(() -> queueNames().size() == before.size() + 1);

        raw.close();      // the phone loses signal: no DISCONNECT, the TCP connection just dies

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(queueNames()).isEqualTo(before));
    }

    @Test
    void pushingToASessionThatHasEndedCreatesNothingAtTheBroker() throws Exception {
        Account customer = api.registerCustomer("customer");
        var before = queueNames();
        try (var socket = StompTestClient.connect(app.ws(), customer.token())) {
            socket.subscribe(Destinations.USER_TRIPS);
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(queueNames()).isEqualTo(before));

        // the application still has something to tell this user (it does not know they just left)
        app.bean(SimpMessagingTemplate.class).convertAndSendToUser(customer.id().toString(), Destinations.TRIPS, Map.of("type", "TRIP_ACCEPTED"));
        Thread.sleep(500);

        assertThat(queueNames()).isEqualTo(before);       // with /queue destinations this would have created a durable orphan queue
    }

    // ---------------------------------------------------------------- connections

    @Test
    void eachAuthenticatedClientCostsOneBrokerConnectionAndOnlyThat() throws Exception {
        Account a = api.registerCustomer("a");
        Account b = api.registerCustomer("b");
        int baseline = RabbitAdmin.connections();

        try (var first = StompTestClient.connect(app.ws(), a.token()); var second = StompTestClient.connect(app.ws(), b.token())) {
            first.subscribe(Destinations.USER_TRIPS);
            second.subscribe(Destinations.USER_TRIPS);
            assertThat(RabbitAdmin.connections()).isEqualTo(baseline + 2);
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(RabbitAdmin.connections()).isEqualTo(baseline));
    }

    @Test
    void unauthenticatedStompAndConnectFramesNeverReachTheBrokerNoMatterHowManyAreSent() throws Exception {
        int baseline = RabbitAdmin.connections();
        List<RawStomp> sockets = new java.util.ArrayList<>();

        for (int i = 0; i < 25; i++) {
            var raw = RawStomp.open(app.ws());
            sockets.add(raw);
            // STOMP is the alias of CONNECT; a check that only looked at CONNECT would let these through to the broker
            if (i % 3 == 0) raw.frame("STOMP", "accept-version:1.2\nhost:/", "");
            else if (i % 3 == 1) raw.frame("CONNECT", "accept-version:1.2\nhost:/", "");
            else raw.frame("STOMP", "accept-version:1.2\nhost:/\nAuthorization:Bearer not-a-real-token", "");
        }
        for (RawStomp raw : sockets) {
            assertThat(raw.closedWithin(5_000)).as("an unauthenticated session is dropped").isTrue();
            assertThat(raw.sawConnected()).isFalse();
        }

        assertThat(RabbitAdmin.connections()).isEqualTo(baseline);        // not one of them cost the broker a connection
        sockets.forEach(RawStomp::close);
    }

    @Test
    void ahAuthenticatedClientStillCannotPublishAcknowledgeOrOpenTransactionsOnTheWire() throws Exception {
        Account customer = api.registerCustomer("customer");
        for (String forbidden : List.of("SEND\ndestination:/topic/trips\n\nspoof", "BEGIN\ntransaction:t1\n\n", "ACK\nid:1\n\n",
                "NACK\nid:1\n\n", "COMMIT\ntransaction:t1\n\n", "ABORT\ntransaction:t1\n\n")) {
            var raw = RawStomp.open(app.ws());
            raw.frame("CONNECT", "accept-version:1.2\nhost:/\nAuthorization:Bearer " + customer.token(), "");
            await().atMost(Duration.ofSeconds(5)).until(raw::sawConnected);

            String command = forbidden.substring(0, forbidden.indexOf('\n'));
            String rest = forbidden.substring(forbidden.indexOf('\n') + 1);
            raw.frame(command, rest.contains("\n\n") ? rest.substring(0, rest.indexOf("\n\n")) : rest, rest.contains("\n\n") ? rest.substring(rest.indexOf("\n\n") + 2) : "");

            assertThat(raw.closedWithin(5_000)).as("%s is refused and ends the session", command).isTrue();
            raw.close();
        }
    }

    private static List<String> queueNames() {
        return RabbitAdmin.queues().stream().map(RabbitAdmin.Queue::name).sorted().toList();
    }
}
