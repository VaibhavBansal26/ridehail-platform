package com.ridehail.push;

import com.ridehail.security.Role;
import java.security.Principal;

/** Authenticated STOMP session. The name is the profile id, which is what user destinations are addressed by. */
record WsPrincipal(String name, Role role) implements Principal {
    @Override
    public String getName() {
        return name;
    }
}
