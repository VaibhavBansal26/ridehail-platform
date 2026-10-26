package com.ridehail.common;

import java.time.Instant;
import java.util.UUID;

/** A rider reported a new position. Published inside the update's transaction, fanned out after it commits. */
public record RiderLocationUpdated(UUID riderId, double lat, double lon, Double heading, Instant at) {}
