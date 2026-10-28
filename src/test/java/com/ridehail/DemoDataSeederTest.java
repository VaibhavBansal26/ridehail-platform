package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehail.dispute.DisputeRepository;
import com.ridehail.dispute.FinanceIncidentRepository;
import com.ridehail.payment.PaymentRepository;
import com.ridehail.rider.RiderRepository;
import com.ridehail.trip.TripRepository;
import com.ridehail.trip.TripStatus;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** The demo city must load cleanly and cover every state the admin panel has a view for. */
@AutoConfigureMockMvc
@SpringBootTest(properties = {"ridehail.demo-data.enabled=true", "spring.datasource.url=jdbc:h2:mem:demoseed;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "ridehail.dispatch.expiry-scan-ms=3600000", "ridehail.payments.retry-scan-ms=3600000"})
class DemoDataSeederTest {
    @Autowired TripRepository trips;
    @Autowired RiderRepository riders;
    @Autowired PaymentRepository payments;
    @Autowired DisputeRepository disputes;
    @Autowired FinanceIncidentRepository incidents;
    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

    @Test
    void coversEveryStateThePanelShows() {
        var statuses = trips.findAll().stream().map(t -> t.getStatus()).distinct().toList();
        assertThat(statuses).containsExactlyInAnyOrder(TripStatus.values());
        assertThat(riders.count()).isEqualTo(18);
        assertThat(payments.count()).isEqualTo(14);
        assertThat(disputes.count()).isEqualTo(5);
        assertThat(incidents.count()).isEqualTo(4);
        assertThat(Arrays.stream(com.ridehail.rider.RiderStatus.values()).allMatch(s -> riders.countByStatus(s) > 0)).isTrue();
    }

    /** The finance endpoints, on realistic data (disputes, incidents, captured payments), against the frontend's fixtures. */
    @Test
    void financeResponsesMatchTheContractFixtures() throws Exception {
        String login = mvc.perform(MockMvcRequestBuilders.post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"admin\",\"password\":\"dev-admin-password\"}")).andReturn().getResponse().getContentAsString();
        String token = json.readTree(login).get("accessToken").asText();
        for (String[] f : new String[][] {
                {"payment-page.json", "/api/admin/payments"}, {"dispute-page.json", "/api/admin/disputes"},
                {"incident-page.json", "/api/admin/incidents"}, {"attention.json", "/api/admin/finance/attention"},
                {"trial-balance.json", "/api/admin/ledger/trial-balance"}, {"funding.json", "/api/admin/payouts/funding"}}) {
            var body = json.readTree(mvc.perform(MockMvcRequestBuilders.get(f[1]).header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getContentAsString());
            ContractFixtures.check(json, f[0], body);
        }
    }
}
