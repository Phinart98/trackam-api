package com.trackam.controller;

import com.trackam.config.AppProperties;
import com.trackam.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class MetricsController {

    private final AuditLogRepository auditRepo;
    private final AppProperties props;

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics(@AuthenticationPrincipal Jwt jwt) {
        if (props.getAdminUserId().isBlank() || !jwt.getSubject().equals(props.getAdminUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<Object[]> providerMetrics = auditRepo.getProviderMetrics();
        Map<String, Object> result = new HashMap<>();
        for (Object[] row : providerMetrics) {
            String provider = (String) row[0];
            long totalCalls = (long) row[1];
            double avgLatency = (double) row[2];
            long successCount = (long) row[3];
            result.put(provider, Map.of(
                "totalCalls", totalCalls,
                "avgLatencyMs", Math.round(avgLatency),
                "successRate", totalCalls == 0 ? 0 : (double) successCount / totalCalls * 100
            ));
        }
        return ResponseEntity.ok(result);
    }
}
