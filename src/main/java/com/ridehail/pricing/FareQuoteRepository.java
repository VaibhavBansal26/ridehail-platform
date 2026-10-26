package com.ridehail.pricing;

import com.ridehail.common.VehicleType;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FareQuoteRepository extends JpaRepository<FareQuote, UUID> {

    @Query("""
            select count(distinct q.customerId) from FareQuote q
            where q.vehicleType = :type and q.createdAt >= :since and q.customerId <> :customerId
              and q.pickupLat between :minLat and :maxLat and q.pickupLon between :minLon and :maxLon
            """)
    long countDistinctCustomersInBoxSince(@Param("type") VehicleType type, @Param("since") Instant since,
                                          @Param("customerId") UUID customerId,
                                          @Param("minLat") double minLat, @Param("maxLat") double maxLat,
                                          @Param("minLon") double minLon, @Param("maxLon") double maxLon);
}
