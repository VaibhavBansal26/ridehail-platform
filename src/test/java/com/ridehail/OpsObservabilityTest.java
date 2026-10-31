package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.http.HttpMethod;

/**
 * What an on-call person relies on: request ids in the logs, metrics that move, and metrics that only ops can read.
 * (Metrics export is off by default in Spring's test mode; the annotation turns it on so the endpoint exists to be tested.)
 */
@AutoConfigureObservability
class OpsObservabilityTest extends ApiTestBase {

    private String scrape(Actor admin) throws Exception {
        return call(HttpMethod.GET, "/actuator/prometheus", admin, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void everyResponseCarriesARequestIdAndOnlyAHarmlessCallerIdIsKept() throws Exception {
        String generated = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health"))
                .andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(generated).matches("[0-9a-f-]{36}");

        String kept = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health").header("X-Request-Id", "gw-abc-12345"))
                .andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(kept).isEqualTo("gw-abc-12345");

        // too short, too long, or with characters that could forge a log line: replaced
        for (String bad : new String[] {"short", "x".repeat(65), "has spaces in it!!", "inject\" ,\"level\":\"ERROR"}) {
            String replaced = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health").header("X-Request-Id", bad))
                    .andReturn().getResponse().getHeader("X-Request-Id");
            assertThat(replaced).as(bad).isNotEqualTo(bad).matches("[0-9a-f-]{36}");
        }
    }

    @Test
    void healthIsPublicOnTheServingPortAndSaysNothingElse() throws Exception {
        var res = call(HttpMethod.GET, "/api/health", null, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(res).fieldNames()).toIterable().containsExactly("status");   // no component details to strangers
        assertThat(json.readTree(res).get("status").asText()).isEqualTo("UP");
    }

    @Test
    void metricsAreForOpsOnTheServingPort() throws Exception {
        call(HttpMethod.GET, "/actuator/prometheus", null, null).andExpect(status().isUnauthorized());
        call(HttpMethod.GET, "/actuator/prometheus", customer("Ravi"), null).andExpect(status().isForbidden());
        call(HttpMethod.GET, "/actuator/prometheus", admin(), null).andExpect(status().isOk());
        // health stays open for load balancers
        call(HttpMethod.GET, "/actuator/health", null, null).andExpect(status().isOk());
    }

    @Test
    void businessMetricsMoveWithTheBusiness() throws Exception {
        Actor ops = admin();
        double before = counter(scrape(ops), "TRIP_COMPLETED");

        Actor ravi = customer("Ravi");
        Actor asha = onlineRider("Asha", 0.005);
        assertThat(scrape(ops)).contains("ridehail_riders{").contains("status=\"AVAILABLE\"");
        completeRide(ravi, asha);

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                .until(() -> counter(scrape(ops), "TRIP_COMPLETED") == before + 1);
        String text = scrape(ops);
        assertThat(counter(text, "TRIP_REQUESTED")).isGreaterThanOrEqualTo(1);
        // what a dashboard and its alerts are built on
        assertThat(text).contains("ridehail_payments{").contains("ridehail_payouts{").contains("ridehail_disputes_open")
                .contains("ridehail_incidents_open").contains("ridehail_ledger_balanced 1.0");
    }

    private static double counter(String scrape, String type) {
        for (String line : scrape.split("\n")) {
            if (line.startsWith("ridehail_ride_events_total{") && line.contains("type=\"" + type + "\"")) {
                return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
            }
        }
        return 0;
    }
}
