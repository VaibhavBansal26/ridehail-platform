package com.ridehail.ops;

import java.util.Map;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health for load balancers, the gateway and container health checks, on the serving port. In production the Actuator
 * endpoints (metrics, and health with it) move to an internal management port, which a load balancer in front of the public port
 * cannot reach; this answers on the public port in every profile, with the status and nothing else (no component details).
 * 200 when the instance can serve traffic, 503 when it cannot.
 */
@RestController
class PublicHealthController {
    private final HealthEndpoint health;

    PublicHealthController(HealthEndpoint health) {
        this.health = health;
    }

    @GetMapping("/api/health")
    ResponseEntity<Map<String, String>> health() {
        Status status = health.health().getStatus();
        return ResponseEntity.status(Status.UP.equals(status) ? 200 : 503).body(Map.of("status", status.getCode()));
    }
}
