package com.ridehail.config;

import com.ridehail.dispatch.DispatchProperties;
import com.ridehail.payment.PaymentProperties;
import com.ridehail.payment.stripe.StripeProperties;
import com.ridehail.pricing.PricingProperties;
import com.ridehail.security.SecurityProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({PricingProperties.class, DispatchProperties.class, SecurityProperties.class,
        PaymentProperties.class, StripeProperties.class})
public class AppConfig {

    /** Injected everywhere instead of Instant.now() so time-dependent logic is testable. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
