package com.trackam.repository;

import com.trackam.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findByUserIdOrderByCreatedAtDesc(UUID userId);

    @Query("SELECT a.aiProvider, COUNT(a), AVG(a.latencyMs), SUM(CASE WHEN a.success THEN 1 ELSE 0 END) FROM AuditLog a GROUP BY a.aiProvider")
    List<Object[]> getProviderMetrics();

    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.userId = :userId AND a.createdAt > :since")
    long countRecentCallsByUser(@Param("userId") UUID userId, @Param("since") java.time.Instant since);

    // Anonymize on user deletion — keep records for financial compliance, remove PII
    // Nil UUID (all zeros) is used as the anonymization sentinel — JPQL cannot assign a UUID literal
    @Modifying
    @Transactional
    @Query(value = "UPDATE audit_logs SET user_id = '00000000-0000-0000-0000-000000000000' WHERE user_id = :userId", nativeQuery = true)
    void anonymizeByUserId(@Param("userId") UUID userId);
}
