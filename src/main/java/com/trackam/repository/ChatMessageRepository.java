package com.trackam.repository;

import com.trackam.model.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, String> {

    List<ChatMessage> findByUserIdOrderByCreatedAtDesc(String userId);

    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    // Last 10 messages for conversation history context
    List<ChatMessage> findTop10BySessionIdOrderByCreatedAtAsc(String sessionId);

    void deleteByUserId(String userId);
}
