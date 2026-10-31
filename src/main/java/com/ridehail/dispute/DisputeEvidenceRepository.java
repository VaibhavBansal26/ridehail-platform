package com.ridehail.dispute;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface DisputeEvidenceRepository extends JpaRepository<DisputeEvidence, UUID> {}
