package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** The admin views the web panel is built on: names and coordinates on trips, trip detail, and the live ops snapshot. */
class AdminOpsApiTest extends ApiTestBase {

    @Test
    void tripListCarriesNamesCoordinatesAndTimestamps() throws Exception {
        Actor alice = customer("Alice");
        Actor bob = onlineRider("Bob", 0.001);
        completeRide(alice, bob);

        JsonNode trip = fetch("/api/admin/trips", admin()).get("items").get(0);

        assertThat(trip.get("customerName").asText()).isEqualTo("Alice");
        assertThat(trip.get("riderName").asText()).isEqualTo("Bob");
        assertThat(trip.get("pickupLat").asDouble()).isEqualTo(PICKUP_LAT);
        assertThat(trip.get("dropLon").asDouble()).isEqualTo(DROP_LON);
        assertThat(trip.get("requestedAt").isTextual()).isTrue();
        assertThat(trip.get("completedAt").isTextual()).isTrue();
    }

    @Test
    void tripDetailShowsPaymentOffersAndDisputes() throws Exception {
        Actor alice = customer("Alice");
        Actor bob = onlineRider("Bob", 0.001);
        Ride ride = completeRide(alice, bob);

        JsonNode detail = fetch("/api/admin/trips/" + ride.tripId(), admin());

        assertThat(detail.get("trip").get("id").asText()).isEqualTo(ride.tripId().toString());
        assertThat(detail.get("offers")).hasSize(1);
        assertThat(detail.get("offers").get(0).get("riderName").asText()).isEqualTo("Bob");
        assertThat(detail.get("offers").get(0).get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(detail.get("disputes")).isEmpty();
        assertThat(detail.has("payment")).isTrue();
    }

    @Test
    void unknownTripIs404() throws Exception {
        call(HttpMethod.GET, "/api/admin/trips/" + UUID.randomUUID(), admin(), null).andExpect(status().isNotFound());
    }

    @Test
    void liveSnapshotHasOnlineRidersWithPositionsAndActiveTrips() throws Exception {
        Actor alice = customer("Alice");
        onlineRider("Near", 0.001);
        riderOffline("Asleep");
        book(alice, quote(alice).get("quoteId").asText());

        JsonNode live = fetch("/api/admin/ops/live", admin());

        assertThat(live.get("riders")).hasSize(1);
        assertThat(live.get("riders").get(0).get("name").asText()).isEqualTo("Near");
        assertThat(live.get("trips")).hasSize(1);
        assertThat(live.get("trips").get(0).get("customerName").asText()).isEqualTo("Alice");
        assertThat(live.get("riderCounts").get("AVAILABLE").asLong()).isEqualTo(1);
        assertThat(live.get("riderCounts").get("OFFLINE").asLong()).isEqualTo(1);
        assertThat(live.get("truncated").asBoolean()).isFalse();
        assertThat(live.get("generatedAt").isTextual()).isTrue();
    }

    @Test
    void onlyAdminsMayUseThem() throws Exception {
        Actor alice = customer("Alice");
        Actor bob = onlineRider("Bob", 0.001);
        for (String url : new String[] {"/api/admin/ops/live", "/api/admin/trips/" + UUID.randomUUID()}) {
            call(HttpMethod.GET, url, null, null).andExpect(status().isUnauthorized());
            call(HttpMethod.GET, url, alice, null).andExpect(status().isForbidden());
            call(HttpMethod.GET, url, bob, null).andExpect(status().isForbidden());
        }
    }

    /** The frontend validates these same files with its schemas, so a change to either side fails a test on that side. */
    @Test
    void responseShapesMatchTheContractFixtures() throws Exception {
        Actor alice = customer("Alice");
        Actor bob = onlineRider("Bob", 0.001);
        Ride ride = completeRide(alice, bob);
        Actor carol = customer("Carol");
        onlineRider("Dan", 0.002);
        book(carol, quote(carol).get("quoteId").asText());   // something still on the road, so the live arrays are not empty
        Actor admin = admin();

        ContractFixtures.check(json, "trip-detail.json", fetch("/api/admin/trips/" + ride.tripId(), admin));
        ContractFixtures.check(json, "live-snapshot.json", fetch("/api/admin/ops/live", admin));
        ContractFixtures.check(json, "rider-page.json", fetch("/api/admin/riders", admin));
        ContractFixtures.check(json, "trip-page.json", fetch("/api/admin/trips", admin));
    }
}
