package com.ridehail.rider;

import com.ridehail.common.VehicleType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RiderRepository extends JpaRepository<Rider, UUID> {

    java.util.Optional<Rider> findByProviderAccountId(String providerAccountId);

    Page<Rider> findByStatus(RiderStatus status, Pageable pageable);

    /** Riders in any of these states, capped by the page size (used by the live ops map). */
    List<Rider> findByStatusIn(java.util.Collection<RiderStatus> statuses, Pageable pageable);

    long countByStatus(RiderStatus status);

    @Query("""
            select r from Rider r
            where r.status = :status and r.vehicleType = :type
              and r.lat between :minLat and :maxLat and r.lon between :minLon and :maxLon
            """)
    List<Rider> findInBox(@Param("status") RiderStatus status, @Param("type") VehicleType type,
                          @Param("minLat") double minLat, @Param("maxLat") double maxLat,
                          @Param("minLon") double minLon, @Param("maxLon") double maxLon);

    @Query("""
            select count(r) from Rider r
            where r.status = :status and r.vehicleType = :type
              and r.lat between :minLat and :maxLat and r.lon between :minLon and :maxLon
            """)
    long countInBox(@Param("status") RiderStatus status, @Param("type") VehicleType type,
                    @Param("minLat") double minLat, @Param("maxLat") double maxLat,
                    @Param("minLon") double minLon, @Param("maxLon") double maxLon);
}
