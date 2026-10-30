package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ridehail.security.Role;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** Named admins, and a record of who did what. */
class AdminAuditTest extends ApiTestBase {

    @AfterEach
    void removeCreatedAdmins() {
        accountRepo.findAll().stream().filter(a -> a.getRole() == Role.ADMIN && !a.getPhone().equals("admin")).forEach(accountRepo::delete);
    }

    private String create(String phone, String password) {
        return "{\"phone\":\"" + phone + "\",\"password\":\"" + password + "\"}";
    }

    private Actor newAdmin(Actor by, String phone) throws Exception {
        lastCreated = phone;
        call(HttpMethod.POST, "/api/admin/admins", by, create(phone, "a-long-password-1")).andExpect(status().isCreated());
        return actor(phone, "a-long-password-1");
    }

    private Actor actor(String phone, String password) throws Exception {
        JsonNode b = body(call(HttpMethod.POST, "/api/auth/login", null, create(phone, password)));
        return new Actor(UUID.fromString(b.get("profileId").asText()), b.get("accessToken").asText(), b.get("refreshToken").asText());
    }

    private JsonNode auditFor(Actor viewer, String action) throws Exception {
        return fetch("/api/admin/audit?size=100&action=" + action, viewer).get("items");
    }

    /** The log is append-only, so it holds other tests' rows too; find ours by target (or, for creations, by the name in the detail). */
    private JsonNode entryFor(Actor viewer, String action, String targetId) throws Exception {
        for (JsonNode e : auditFor(viewer, action)) {
            if (targetId != null ? targetId.equals(e.get("targetId").asText()) : e.get("detail").asText().contains("ops-")) {
                if (targetId != null || e.get("detail").asText().contains(lastCreated)) return e;
            }
        }
        throw new AssertionError("no " + action + " entry for " + (targetId != null ? targetId : lastCreated));
    }

    private String lastCreated;

    @Test
    void anActionIsRecordedAgainstThePersonWhoDidIt() throws Exception {
        Actor root = admin();
        String name = "ops-" + UUID.randomUUID();
        Actor ops = newAdmin(root, name);
        Actor alice = customer("Alice");
        onlineRider("Near", 0.001);
        call(HttpMethod.POST, "/api/trips", alice, "{\"quoteId\":\"" + quote(alice).get("quoteId").asText() + "\"}");
        UUID trip = tripRepo.findAll().get(0).getId();

        call(HttpMethod.POST, "/api/admin/trips/" + trip + "/cancel", ops, null).andExpect(status().isOk());

        JsonNode entry = entryFor(root, "TRIP_CANCELLED", trip.toString());
        assertThat(entry.get("actorPhone").asText()).isEqualTo(name);
        assertThat(entry.get("targetType").asText()).isEqualTo("TRIP");
    }

    @Test
    void aFailedActionLeavesNoRecordOfSuccess() throws Exception {
        Actor root = admin();
        int before = auditFor(root, "TRIP_CANCELLED").size();
        call(HttpMethod.POST, "/api/admin/trips/" + UUID.randomUUID() + "/cancel", root, null).andExpect(status().isNotFound());
        assertThat(auditFor(root, "TRIP_CANCELLED")).hasSize(before);
    }

    @Test
    void creatingAnAdminIsItselfAudited() throws Exception {
        Actor root = admin();
        String name = "ops-" + UUID.randomUUID();
        newAdmin(root, name);
        assertThat(entryFor(root, "ADMIN_CREATED", null).get("detail").asText()).contains(name);
    }

    @Test
    void passwordsMustBeLongAndNamesUnique() throws Exception {
        Actor root = admin();
        call(HttpMethod.POST, "/api/admin/admins", root, create("short-" + UUID.randomUUID(), "tooshort")).andExpect(status().isBadRequest());
        call(HttpMethod.POST, "/api/admin/admins", root, create("admin", "a-long-password-1")).andExpect(status().isConflict());
    }

    @Test
    void aDisabledAdminCannotSignInAgain() throws Exception {
        Actor root = admin();
        String name = "ops-" + UUID.randomUUID();
        Actor ops = newAdmin(root, name);
        call(HttpMethod.POST, "/api/admin/admins/" + ops.id() + "/disable", root, null).andExpect(status().isNotFound());   // profile id is not the account id
        UUID accountId = accountRepo.findByPhone(name).orElseThrow().getId();

        call(HttpMethod.POST, "/api/admin/admins/" + accountId + "/disable", root, null).andExpect(status().isOk());
        call(HttpMethod.POST, "/api/auth/login", null, create(name, "a-long-password-1")).andExpect(status().isUnauthorized());
        call(HttpMethod.POST, "/api/auth/refresh", null, "{\"refreshToken\":\"" + ops.refreshToken() + "\"}").andExpect(status().isUnauthorized());

        call(HttpMethod.POST, "/api/admin/admins/" + accountId + "/enable", root, null).andExpect(status().isOk());
        call(HttpMethod.POST, "/api/auth/login", null, create(name, "a-long-password-1")).andExpect(status().isOk());
    }

    @Test
    void youCannotLockYourselfOrEveryoneOut() throws Exception {
        Actor root = admin();
        UUID rootAccount = accountRepo.findByPhone("admin").orElseThrow().getId();
        call(HttpMethod.POST, "/api/admin/admins/" + rootAccount + "/disable", root, null).andExpect(status().isConflict());

        String name = "ops-" + UUID.randomUUID();
        Actor ops = newAdmin(root, name);
        UUID opsAccount = accountRepo.findByPhone(name).orElseThrow().getId();
        call(HttpMethod.POST, "/api/admin/admins/" + opsAccount + "/disable", root, null).andExpect(status().isOk());
        // only one active admin is left (root): the last one cannot be disabled even by someone else... who is now disabled, so root is "you"
        call(HttpMethod.POST, "/api/admin/admins/" + rootAccount + "/disable", ops, null).andExpect(status().isConflict());
    }

    @Test
    void listShowsWhoYouAreAndOnlyAdminsMayUseAnyOfIt() throws Exception {
        Actor root = admin();
        JsonNode list = body(call(HttpMethod.GET, "/api/admin/admins", root, null));
        assertThat(list.findValues("you").stream().filter(JsonNode::asBoolean).count()).isEqualTo(1);

        Actor alice = customer("Alice");
        for (String url : new String[] {"/api/admin/admins", "/api/admin/audit"}) {
            call(HttpMethod.GET, url, null, null).andExpect(status().isUnauthorized());
            call(HttpMethod.GET, url, alice, null).andExpect(status().isForbidden());
        }
        call(HttpMethod.POST, "/api/admin/admins", alice, create("x-" + UUID.randomUUID(), "a-long-password-1")).andExpect(status().isForbidden());
    }

    @Test
    void responseShapesMatchTheContractFixtures() throws Exception {
        Actor root = admin();
        newAdmin(root, "ops-" + UUID.randomUUID());
        ContractFixtures.check(json, "admin-list.json", body(call(HttpMethod.GET, "/api/admin/admins", root, null)));
        ContractFixtures.check(json, "audit-page.json", fetch("/api/admin/audit", root));
    }
}
