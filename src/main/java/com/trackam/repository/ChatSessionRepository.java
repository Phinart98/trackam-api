package com.trackam.repository;

import com.trackam.model.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    List<ChatSession> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ChatSession s WHERE s.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);
}
