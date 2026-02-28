package com.trackam.repository;

import com.trackam.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

public interface AuditLogRepository extends JpaRepository<AuditLog, String> {

    List<AuditLog> findByUserIdOrderByCreatedAtDesc(String userId);

    @Query("SELECT a.aiProvider, COUNT(a), AVG(a.latencyMs), SUM(CASE WHEN a.success THEN 1 ELSE 0 END) FROM AuditLog a GROUP BY a.aiProvider")
    List<Object[]> getProviderMetrics();

    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.userId = :userId AND a.createdAt > :since")
    long countRecentCallsByUser(@Param("userId") String userId, @Param("since") java.time.LocalDateTime since);

    // Anonymize on user deletion — keep records for financial compliance, remove PII
    @Modifying
    @Transactional
    @Query("UPDATE AuditLog a SET a.userId = 'deleted' WHERE a.userId = :userId")
    void anonymizeByUserId(@Param("userId") String userId);
}
