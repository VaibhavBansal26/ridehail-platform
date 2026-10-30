package com.ridehail.admin;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminActionRepository extends JpaRepository<AdminAction, UUID> {
    Page<AdminAction> findByAction(String action, Pageable pageable);
}
