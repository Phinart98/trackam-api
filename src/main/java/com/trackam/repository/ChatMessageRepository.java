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

    // Last 10 messages for conversation history context — DESC to get most recent, reversed by caller
    List<ChatMessage> findTop10BySessionIdOrderByCreatedAtDesc(UUID sessionId);

    @Modifying
    @Transactional
    void deleteByUserId(UUID userId);
}
