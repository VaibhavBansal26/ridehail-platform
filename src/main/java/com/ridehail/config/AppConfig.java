package com.ridehail.config;

import com.ridehail.dispatch.DispatchProperties;
import com.ridehail.dispute.DisputeProperties;
import com.ridehail.payment.PaymentProperties;
import com.ridehail.payment.stripe.StripeProperties;
import com.ridehail.pricing.PricingProperties;
import com.ridehail.push.RealtimeProperties;
import com.ridehail.ratelimit.RateLimitProperties;
import com.ridehail.security.SecurityProperties;
import com.ridehail.tracking.TrackingProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({PricingProperties.class, DispatchProperties.class, SecurityProperties.class,
        PaymentProperties.class, StripeProperties.class, TrackingProperties.class, DisputeProperties.class,
        RateLimitProperties.class, RealtimeProperties.class})
public class AppConfig {

    /**
     * Injected everywhere instead of Instant.now() so time-dependent logic is testable. Whole microseconds, which is the
     * precision the database keeps, so a timestamp we hold in memory always equals the one we read back.
     */
    @Bean
    public Clock clock() {
        return MicrosecondClock.systemUtc();
    }
}
