package com.ridehail.dispute;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ridehail.disputes")
public record DisputeProperties(LossPolicy lossPolicy) {

    /** Who absorbs a dispute we lose. Either way a won dispute changes nothing for anyone. */
    public enum LossPolicy {
        /** The platform pays: the rider keeps their earnings. The rider provided the ride; the dispute is the platform's risk. */
        PLATFORM,
        /** The rider's share of the lost charge is taken back from them (netted against future earnings if already paid out). */
        RIDER
    }
}
