package com.trackam.repository;

import com.trackam.model.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, String> {

    List<ChatMessage> findByUserIdOrderByCreatedAtDesc(String userId);

    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    // Last 10 messages for conversation history context — DESC to get most recent, reversed by caller
    List<ChatMessage> findTop10BySessionIdOrderByCreatedAtDesc(String sessionId);

    @Modifying
    @Transactional
    void deleteByUserId(String userId);
}
