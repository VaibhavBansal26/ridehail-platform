package com.ridehail.push;

import com.ridehail.security.JwtService;
import com.ridehail.security.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket at /ws.
 *
 * <h3>One instance (ridehail.realtime.broker=simple)</h3>
 * An in-memory broker. Fine for development and a single server; with several servers a push only reaches users who happen
 * to be connected to the server that sent it.
 *
 * <h3>Several instances (ridehail.realtime.broker=relay)</h3>
 * Every instance relays through RabbitMQ (its STOMP plugin), so any instance can reach a user connected to any other:
 * <ul>
 *   <li>a message for {@code /user/topic/x} is addressed to the user's WebSocket session(s); the broker delivers it to the
 *       instance holding that session;</li>
 *   <li>instances periodically share which users are connected where ({@code userRegistryBroadcast}), so a user with
 *       sessions on two instances (two devices) receives on both;</li>
 *   <li>a user who connected after the last such exchange is unknown to the sender for up to ~10 seconds; the message is then
 *       broadcast as "unresolved" and the instance that has the user delivers it ({@code userDestinationBroadcast}).</li>
 * </ul>
 * Delivery is best-effort, as before: a client that was disconnected must refetch state over REST when it reconnects.
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtService jwt;
    private final SecurityProperties security;
    private final RealtimeProperties realtime;

    WebSocketConfig(JwtService jwt, SecurityProperties security, RealtimeProperties realtime) {
        this.jwt = jwt;
        this.security = security;
        this.realtime = realtime;
        realtime.validate();
    }

    /** Scheduler for STOMP heartbeats (simple broker) and the @Scheduled sweeps, so dead mobile connections get noticed. */
    @Bean
    TaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("ridehail-sched-");
        return scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(security.allowedOrigins().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setUserDestinationPrefix("/user");
        if (!realtime.isRelay()) {
            registry.enableSimpleBroker("/topic")
                    .setHeartbeatValue(new long[] {10_000, 10_000})
                    .setTaskScheduler(taskScheduler());
            return;
        }
        var r = realtime.relay();
        registry.enableStompBrokerRelay("/topic")
                .setRelayHost(r.host())
                .setRelayPort(r.port())
                .setVirtualHost(r.virtualHost())
                .setSystemLogin(r.systemLogin()).setSystemPasscode(r.systemPasscode())
                .setClientLogin(r.clientLogin()).setClientPasscode(r.clientPasscode())
                .setSystemHeartbeatSendInterval(10_000).setSystemHeartbeatReceiveInterval(10_000)
                .setUserRegistryBroadcast("/topic/simp-user-registry")
                .setUserDestinationBroadcast("/topic/unresolved-user-destination");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new StompAuthInterceptor(jwt.accessDecoder(), realtime.isRelay()));
    }
}
