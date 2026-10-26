package com.ridehail.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import com.ridehail.common.VehicleType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// Own in-memory database and silent schedulers: a second Spring context sharing another test's database would
// re-create its tables and run background retries against its rows.
@SpringBootTest(properties = {
        "ridehail.dispatch.expiry-scan-ms=3600000",
        "ridehail.payments.retry-scan-ms=3600000",
        "spring.datasource.url=jdbc:h2:mem:fare-calculator;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
class FareCalculatorTest {

    @Autowired FareCalculator calculator;
    @Autowired SurgePricingService surge;

    @Test
    void fareIsBasePlusDistancePlusTime() {
        // SEDAN: 1.50 + 10km*0.90 + 20min*0.15 = 13.50
        var b = calculator.calculate(VehicleType.SEDAN, 10, 20, new BigDecimal("1.0"));
        assertThat(b.total()).isEqualByComparingTo("13.50");
    }

    @Test
    void minimumFareAppliesBeforeSurge() {
        // tiny BIKE trip floors at 2.00, then x1.5 = 3.00
        var b = calculator.calculate(VehicleType.BIKE, 0.1, 0.5, new BigDecimal("1.5"));
        assertThat(b.subtotal()).isEqualByComparingTo("2.00");
        assertThat(b.total()).isEqualByComparingTo("3.00");
    }

    @Test
    void surgeMultiplierFollowsDemandSupplyRatioAndIsCapped() {
        assertThat(surge.multiplierFor(1, 5)).isEqualByComparingTo("1.0");   // plenty of supply
        assertThat(surge.multiplierFor(2, 2)).isEqualByComparingTo("1.0");   // balanced
        assertThat(surge.multiplierFor(4, 1)).isEqualByComparingTo("2.5");   // 1 + 0.5 * (4-1)
        assertThat(surge.multiplierFor(50, 1)).isEqualByComparingTo("3.0");  // capped at max
        assertThat(surge.multiplierFor(3, 0)).isEqualByComparingTo("2.0");   // no riders: supply floored at 1
    }
}
