package com.ridehail.dispute;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinanceIncidentRepository extends JpaRepository<FinanceIncident, UUID> {
    Optional<FinanceIncident> findByTypeAndProviderRef(IncidentType type, String providerRef);

    boolean existsByRiderIdAndTypeAndStatus(UUID riderId, IncidentType type, IncidentStatus status);

    List<FinanceIncident> findByRiderIdAndTypeAndStatus(UUID riderId, IncidentType type, IncidentStatus status);

    Page<FinanceIncident> findByStatus(IncidentStatus status, Pageable pageable);

    Page<FinanceIncident> findByStatusAndType(IncidentStatus status, IncidentType type, Pageable pageable);

    Page<FinanceIncident> findByType(IncidentType type, Pageable pageable);

    List<FinanceIncident> findByStatus(IncidentStatus status);
}
