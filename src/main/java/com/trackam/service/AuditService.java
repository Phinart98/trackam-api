package com.trackam.service;

import com.trackam.model.AuditLog;
import com.trackam.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository repo;

    /** Async — audit writes never add latency to AI responses. */
    @Async
    public void log(UUID userId, String operation, String provider,
                    long latencyMs, boolean success, String error) {
        AuditLog entry = AuditLog.builder()
            .userId(userId)
            .operation(operation)
            .aiProvider(provider)
            .latencyMs((int) latencyMs)
            .success(success)
            .errorMessage(error)
            .build();

        // Audit rows back the daily rate-limit count (see isOverDailyLimit), so a transient
        // write failure shouldn't silently let a user slip past their cap. Retry a few times
        // with a short backoff before giving up — we're already off the request thread.
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                repo.save(entry);
                return;
            } catch (Exception e) {
                if (attempt == 3) {
                    log.error("Failed to write audit log after {} attempts (user={}, op={}): {}",
                        attempt, userId, operation, e.getMessage());
                } else {
                    try {
                        Thread.sleep(100L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    public boolean isOverDailyLimit(UUID userId, int maxCalls) {
        Instant since = Instant.now().minus(1, ChronoUnit.DAYS);
        return repo.countRecentCallsByUser(userId, since) >= maxCalls;
    }
}
