package com.ridehail.notify;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, String> {
    List<DeviceToken> findByProfileIdOrderByLastSeenAtDesc(UUID profileId);

    void deleteByTokenIn(Collection<String> tokens);

    long deleteByTokenAndProfileId(String token, UUID profileId);
}
