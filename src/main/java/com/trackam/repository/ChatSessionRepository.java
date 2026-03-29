package com.trackam.repository;

import com.trackam.model.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {

    List<ChatSession> findByUserIdOrderByUpdatedAtDesc(String userId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ChatSession s WHERE s.userId = :userId")
    void deleteByUserId(@Param("userId") String userId);
}
