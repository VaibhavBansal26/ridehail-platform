package com.ridehail.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** Identity helpers: controllers take the actor from the verified token, never from the request body. */
public final class CurrentUser {
    private CurrentUser() {}

    /** Customer id for customers, rider id for riders. */
    public static UUID profileId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("pid"));
    }

    public static Role role(Jwt jwt) {
        return Role.valueOf(jwt.getClaimAsString("role"));
    }
}
