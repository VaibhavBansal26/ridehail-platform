package com.ridehail.trip;

import java.util.EnumSet;
import java.util.Set;

public enum TripStatus {
    REQUESTED, ACCEPTED, ARRIVED, IN_PROGRESS, COMPLETED, CANCELLED, NO_RIDERS_AVAILABLE;

    private Set<TripStatus> next() {
        return switch (this) {
            case REQUESTED -> EnumSet.of(ACCEPTED, CANCELLED, NO_RIDERS_AVAILABLE);
            case ACCEPTED -> EnumSet.of(ARRIVED, CANCELLED);
            case ARRIVED -> EnumSet.of(IN_PROGRESS, CANCELLED);
            case IN_PROGRESS -> EnumSet.of(COMPLETED);
            case COMPLETED, CANCELLED, NO_RIDERS_AVAILABLE -> EnumSet.noneOf(TripStatus.class);
        };
    }

    public boolean canMoveTo(TripStatus target) {
        return next().contains(target);
    }

    public boolean isActive() {
        return this == REQUESTED || this == ACCEPTED || this == ARRIVED || this == IN_PROGRESS;
    }
}
