package com.trackam.service;

import com.trackam.model.AuditLog;
import com.trackam.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository repo;

    /** Async — audit writes never add latency to AI responses. */
    @Async
    public void log(String userId, String operation, String provider,
                    long latencyMs, boolean success, String error) {
        try {
            repo.save(AuditLog.builder()
                .userId(userId)
                .operation(operation)
                .aiProvider(provider)
                .latencyMs((int) latencyMs)
                .success(success)
                .errorMessage(error)
                .build());
        } catch (Exception e) {
            log.error("Failed to write audit log: {}", e.getMessage());
        }
    }

    public boolean isOverDailyLimit(String userId, int maxCalls) {
        LocalDateTime since = LocalDateTime.now().minusDays(1);
        return repo.countRecentCallsByUser(userId, since) >= maxCalls;
    }
}
