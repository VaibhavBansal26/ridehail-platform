package com.ridehail.support;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface SupportConversationRepository extends JpaRepository<SupportConversation, UUID> {
    Optional<SupportConversation> findByUserId(UUID userId);

    Page<SupportConversation> findByStatus(ConversationStatus status, Pageable pageable);

    Page<SupportConversation> findByAwaitingReplyTrue(Pageable pageable);

    long countByAwaitingReplyTrue();

    @Query("select min(c.awaitingSince) from SupportConversation c where c.awaitingReply = true")
    Optional<Instant> oldestWaiting();
}

interface SupportMessageRepository extends JpaRepository<SupportMessage, UUID> {
    /** Newest first; callers reverse for display. */
    List<SupportMessage> findByConversationIdOrderByCreatedAtDesc(UUID conversationId, Pageable pageable);

    long countByConversationIdAndSenderAndCreatedAtAfter(UUID conversationId, Sender sender, Instant after);
}
