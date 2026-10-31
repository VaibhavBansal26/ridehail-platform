package com.ridehail.verification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface RiderVerificationRepository extends JpaRepository<RiderVerification, UUID> {
    Page<RiderVerification> findByStatus(VerificationStatus status, Pageable pageable);

    long countByStatus(VerificationStatus status);

    @Query("select min(v.submittedAt) from RiderVerification v where v.status = com.ridehail.verification.VerificationStatus.PENDING_REVIEW")
    Optional<Instant> oldestPending();

    @Query("select v.riderId from RiderVerification v where v.status = com.ridehail.verification.VerificationStatus.APPROVED")
    List<UUID> approvedRiderIds();
}

interface RiderDocumentRepository extends JpaRepository<RiderDocument, UUID> {
    List<RiderDocument> findByRiderId(UUID riderId);

    Optional<RiderDocument> findByRiderIdAndType(UUID riderId, DocumentType type);

    List<RiderDocument> findByRiderIdIn(Collection<UUID> riderIds);

    @Query("select d from RiderDocument d where d.expiresOn is not null and d.expiresOn <= :cutoff")
    List<RiderDocument> expiringBy(LocalDate cutoff);
}

interface DocumentBlobRepository extends JpaRepository<DocumentBlob, UUID> {}
