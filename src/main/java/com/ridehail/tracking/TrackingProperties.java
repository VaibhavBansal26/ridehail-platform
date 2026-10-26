package com.ridehail.tracking;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ridehail.tracking")
public record TrackingProperties(
        /** Sustained location updates a rider may send per second. */
        double locationUpdatesPerSecond,
        /** Short bursts above the sustained rate that are still accepted (e.g. after a reconnect). */
        int locationBurst,
        /** Minimum gap between two location pushes to the same customer for one trip. */
        long pushMinIntervalMs) {}
