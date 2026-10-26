package com.ridehail.pricing;

import com.ridehail.common.VehicleType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/** Pure pricing math: (base + distance + time, floored at the minimum fare) x surge. */
@Component
public class FareCalculator {

    private final PricingProperties props;

    FareCalculator(PricingProperties props) {
        this.props = props;
    }

    public Breakdown calculate(VehicleType type, double distanceKm, double durationMin, BigDecimal surge) {
        var rate = props.rates().get(type);
        BigDecimal distanceFare = rate.perKm().multiply(BigDecimal.valueOf(distanceKm));
        BigDecimal timeFare = rate.perMin().multiply(BigDecimal.valueOf(durationMin));
        BigDecimal subtotal = rate.base().add(distanceFare).add(timeFare).max(rate.minimum())
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.multiply(surge).setScale(2, RoundingMode.HALF_UP);
        return new Breakdown(subtotal, surge, total);
    }

    public record Breakdown(BigDecimal subtotal, BigDecimal surgeMultiplier, BigDecimal total) {}
}
