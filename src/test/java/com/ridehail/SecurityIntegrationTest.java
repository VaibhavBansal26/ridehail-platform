package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class SecurityIntegrationTest extends ApiTestBase {

    @Test
    void protectedEndpointsRejectMissingOrGarbageTokens() throws Exception {
        callWithToken(HttpMethod.POST, "/api/fares/quote", null, quoteJson()).andExpect(status().isUnauthorized());
        callWithToken(HttpMethod.GET, "/api/riders/me/offers", null, null).andExpect(status().isUnauthorized());
        callWithToken(HttpMethod.GET, "/api/admin/trips", "not-a-jwt", null).andExpect(status().isUnauthorized());
    }

    @Test
    void rolesAreEnforcedPerEndpoint() throws Exception {
        Actor customer = customer("Ravi");
        Actor rider = onlineRider("Asha", 0.005);
        Actor admin = admin();

        call(HttpMethod.GET, "/api/riders/me/offers", customer, null).andExpect(status().isForbidden());
        call(HttpMethod.POST, "/api/fares/quote", rider, quoteJson()).andExpect(status().isForbidden());
        call(HttpMethod.POST, "/api/trips", rider, "{\"quoteId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isForbidden());
        call(HttpMethod.GET, "/api/admin/trips", customer, null).andExpect(status().isForbidden());
        call(HttpMethod.GET, "/api/admin/riders", rider, null).andExpect(status().isForbidden());
        call(HttpMethod.GET, "/api/admin/trips", admin, null).andExpect(status().isOk());
        call(HttpMethod.GET, "/api/admin/riders?status=AVAILABLE", admin, null).andExpect(status().isOk());
    }

    @Test
    void tripIsVisibleOnlyToItsPartiesAndAdmins() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Actor customer = customer("Ravi");
        Actor stranger = customer("Mallory");
        Actor otherRider = onlineRider("Bystander", 0.5);
        UUID trip = book(customer, quote(customer).get("quoteId").asText());
        String offerId = fetch("/api/riders/me/offers", rider).get(0).get("offerId").asText();
        call(HttpMethod.POST, "/api/riders/me/offers/" + offerId + "/accept", rider, null).andExpect(status().isNoContent());

        String url = "/api/trips/" + trip;
        call(HttpMethod.GET, url, customer, null).andExpect(status().isOk());
        call(HttpMethod.GET, url, rider, null).andExpect(status().isOk());
        call(HttpMethod.GET, url, admin(), null).andExpect(status().isOk());
        call(HttpMethod.GET, url, stranger, null).andExpect(status().isForbidden());
        call(HttpMethod.GET, url, otherRider, null).andExpect(status().isForbidden());
    }

    @Test
    void ridersCannotDriveSomeoneElsesTripAndCustomersCannotCancelOthers() throws Exception {
        Actor rider = onlineRider("Asha", 0.005);
        Actor otherRider = onlineRider("Other", 0.5);
        Actor customer = customer("Ravi");
        Actor stranger = customer("Mallory");
        UUID trip = book(customer, quote(customer).get("quoteId").asText());
        String offerId = fetch("/api/riders/me/offers", rider).get(0).get("offerId").asText();
        call(HttpMethod.POST, "/api/riders/me/offers/" + offerId + "/accept", rider, null).andExpect(status().isNoContent());

        call(HttpMethod.POST, "/api/trips/" + trip + "/start", otherRider, null).andExpect(status().isForbidden());
        call(HttpMethod.POST, "/api/trips/" + trip + "/cancel", stranger, null).andExpect(status().isForbidden());
    }

    @Test
    void adminCanCancelAnyTrip() throws Exception {
        onlineRider("Asha", 0.005);
        Actor customer = customer("Ravi");
        UUID trip = book(customer, quote(customer).get("quoteId").asText());

        JsonNode cancelled = body(call(HttpMethod.POST, "/api/admin/trips/" + trip + "/cancel", admin(), null)
                .andExpect(status().isOk()));
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void loginRejectsBadCredentialsAndUnknownUsersIdentically() throws Exception {
        String phone = UUID.randomUUID().toString();
        callWithToken(HttpMethod.POST, "/api/auth/register/customer", null,
                "{\"name\":\"R\",\"phone\":\"" + phone + "\",\"password\":\"password123\"}").andExpect(status().isCreated());

        callWithToken(HttpMethod.POST, "/api/auth/login", null, "{\"phone\":\"" + phone + "\",\"password\":\"password123\"}")
                .andExpect(status().isOk());
        callWithToken(HttpMethod.POST, "/api/auth/login", null, "{\"phone\":\"" + phone + "\",\"password\":\"wrong-password\"}")
                .andExpect(status().isUnauthorized());
        callWithToken(HttpMethod.POST, "/api/auth/login", null, "{\"phone\":\"nobody\",\"password\":\"password123\"}")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registrationValidatesInputAndRejectsDuplicatePhones() throws Exception {
        String phone = UUID.randomUUID().toString();
        String ok = "{\"name\":\"R\",\"phone\":\"" + phone + "\",\"password\":\"password123\"}";
        callWithToken(HttpMethod.POST, "/api/auth/register/customer", null,
                "{\"name\":\"R\",\"phone\":\"x\",\"password\":\"short\"}").andExpect(status().isBadRequest());
        callWithToken(HttpMethod.POST, "/api/auth/register/customer", null, ok).andExpect(status().isCreated());
        callWithToken(HttpMethod.POST, "/api/auth/register/rider", null,
                "{\"name\":\"R\",\"phone\":\"" + phone + "\",\"password\":\"password123\",\"vehicleType\":\"BIKE\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void refreshTokensAreOnlyUsableForRefreshAndAccessTokensOnlyForTheApi() throws Exception {
        Actor customer = customer("Ravi");

        // refresh token cannot call the API
        callWithToken(HttpMethod.POST, "/api/fares/quote", customer.refreshToken(), quoteJson()).andExpect(status().isUnauthorized());
        // access token cannot be exchanged for new tokens
        callWithToken(HttpMethod.POST, "/api/auth/refresh", null, "{\"refreshToken\":\"" + customer.token() + "\"}")
                .andExpect(status().isUnauthorized());

        // a valid refresh token yields a working access token
        JsonNode refreshed = body(callWithToken(HttpMethod.POST, "/api/auth/refresh", null,
                "{\"refreshToken\":\"" + customer.refreshToken() + "\"}").andExpect(status().isOk()));
        callWithToken(HttpMethod.POST, "/api/fares/quote", refreshed.get("accessToken").asText(), quoteJson())
                .andExpect(status().isCreated());
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        Actor customer = customer("Ravi");
        String[] parts = customer.token().split("\\.");
        String forged = parts[0] + "." + parts[1] + "." + "A".repeat(parts[2].length());
        callWithToken(HttpMethod.POST, "/api/fares/quote", forged, quoteJson()).andExpect(status().isUnauthorized());
    }
}
