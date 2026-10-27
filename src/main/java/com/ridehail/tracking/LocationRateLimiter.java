package com.ridehail.tracking;

import com.ridehail.common.ApiExceptions.TooManyRequestsException;
import com.ridehail.common.RateLimiter;
import com.ridehail.ratelimit.RateLimiterFactory;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Caps how often one rider's app may report its position. A buggy or abusive client (or a retry storm after a bad
 * network) is told to slow down before it costs a database write or a fan-out to the customer.
 */
@Component
public class LocationRateLimiter {

    private final RateLimiter perRider;

    LocationRateLimiter(TrackingProperties props, RateLimiterFactory limiters) {
        // shared across instances when Redis is configured: a rider's updates may be spread over several servers
        this.perRider = limiters.create("location-in", props.locationUpdatesPerSecond(), props.locationBurst());
    }

    /** @throws TooManyRequestsException (HTTP 429 with Retry-After) when the rider is over the limit */
    public void check(UUID riderId) {
        var decision = perRider.tryAcquire(riderId.toString());
        if (!decision.allowed()) {
            long seconds = Math.max(1, (decision.retryAfterMillis() + 999) / 1000);
            throw new TooManyRequestsException("Location updates are limited; slow down", seconds);
        }
    }
}
