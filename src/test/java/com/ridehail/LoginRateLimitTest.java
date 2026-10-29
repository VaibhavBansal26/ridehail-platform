package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;

/** Password guessing is slowed per account: a few tries, then 429 with Retry-After until the allowance refills. */
@SpringBootTest(properties = {"ridehail.dispatch.expiry-scan-ms=3600000", "ridehail.payments.retry-scan-ms=3600000",
        "ridehail.login-limit.burst=3", "ridehail.login-limit.per-minute=1", "spring.datasource.url=jdbc:h2:mem:loginlimit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
class LoginRateLimitTest extends ApiTestBase {

    private String body(String phone, String password) {
        return "{\"phone\":\"" + phone + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    void guessingIsStoppedBeforeThePasswordIsChecked() throws Exception {
        String phone = "limit-" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            call(HttpMethod.POST, "/api/auth/login", null, body(phone, "wrong-password")).andExpect(status().isUnauthorized());
        }
        call(HttpMethod.POST, "/api/auth/login", null, body(phone, "wrong-password"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }

    @Test
    void theCorrectPasswordDoesNotGetThroughWhileLockedOut() throws Exception {
        customerWithoutCard("Locked");   // registration does not count as a login attempt
        String phone = "lock-" + UUID.randomUUID();
        call(HttpMethod.POST, "/api/auth/register/customer", null,
                "{\"name\":\"L\",\"phone\":\"" + phone + "\",\"password\":\"password123\"}").andExpect(status().isCreated());
        for (int i = 0; i < 3; i++) call(HttpMethod.POST, "/api/auth/login", null, body(phone, "nope-nope-nope"));

        call(HttpMethod.POST, "/api/auth/login", null, body(phone, "password123")).andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofSeconds(61));
        call(HttpMethod.POST, "/api/auth/login", null, body(phone, "password123")).andExpect(status().isOk());
    }

    @Test
    void unknownPhonesAreLimitedTooSoTheAnswerRevealsNothing() throws Exception {
        String phone = "ghost-" + UUID.randomUUID();
        int refused = 0;
        for (int i = 0; i < 5; i++) {
            if (call(HttpMethod.POST, "/api/auth/login", null, body(phone, "whatever-123")).andReturn().getResponse().getStatus() == 429) refused++;
        }
        assertThat(refused).isEqualTo(2);
    }

    @Test
    void oneAccountsAttemptsDoNotUseUpAnothers() throws Exception {
        String a = "a-" + UUID.randomUUID(), b = "b-" + UUID.randomUUID();
        for (int i = 0; i < 4; i++) call(HttpMethod.POST, "/api/auth/login", null, body(a, "wrong-password"));
        call(HttpMethod.POST, "/api/auth/login", null, body(b, "wrong-password")).andExpect(status().isUnauthorized());
    }

    @Test
    void phoneCaseAndSpacesDoNotGiveFreshAllowances() throws Exception {
        String phone = "Case-" + UUID.randomUUID();
        call(HttpMethod.POST, "/api/auth/login", null, body(phone, "wrong-password"));
        call(HttpMethod.POST, "/api/auth/login", null, body(" " + phone.toLowerCase() + " ", "wrong-password"));
        call(HttpMethod.POST, "/api/auth/login", null, body(phone.toUpperCase(), "wrong-password"));
        call(HttpMethod.POST, "/api/auth/login", null, body(phone, "wrong-password")).andExpect(status().isTooManyRequests());
    }
}
