package com.trackam.repository;

import com.trackam.model.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    List<ChatMessage> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    // Last 6 messages (3 turns) — sliding window keeps immediate context without token bloat
    List<ChatMessage> findTop6BySessionIdOrderByCreatedAtDesc(UUID sessionId);

    @Modifying
    @Transactional
    void deleteByUserId(UUID userId);
}
