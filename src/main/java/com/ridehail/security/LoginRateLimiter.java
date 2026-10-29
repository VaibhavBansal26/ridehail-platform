package com.ridehail.security;

import com.ridehail.common.ApiExceptions.TooManyRequestsException;
import com.ridehail.common.RateLimiter;
import com.ridehail.ratelimit.RateLimiterFactory;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Slows password guessing. Every login attempt for a phone number (including unknown numbers, so the answer never reveals
 * which accounts exist) spends from that account's allowance; when it is spent the attempt is refused with 429 and
 * Retry-After before the password is even checked. Shared across instances when Redis is configured.
 *
 * Keyed by phone, not by address: behind a proxy every client shares the proxy's address, and an address limit would throttle
 * everyone. The cost is that someone can keep a known account's allowance empty; the wait is short (see perMinute).
 */
@Component
public class LoginRateLimiter {

    private final RateLimiter perAccount;

    LoginRateLimiter(LoginLimitProperties props, RateLimiterFactory limiters) {
        this.perAccount = limiters.create("login", props.perMinute() / 60.0, props.burst());
    }

    /** @throws TooManyRequestsException (HTTP 429 with Retry-After) when the account is over its allowance */
    public void check(String phone) {
        var decision = perAccount.tryAcquire(phone.trim().toLowerCase(Locale.ROOT));
        if (!decision.allowed()) {
            long seconds = Math.max(1, (decision.retryAfterMillis() + 999) / 1000);
            throw new TooManyRequestsException("Too many sign-in attempts. Try again in " + seconds + " seconds.", seconds);
        }
    }
}
