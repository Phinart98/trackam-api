package com.trackam.controller;

import com.trackam.repository.AuditLogRepository;
import com.trackam.repository.BusinessProfileRepository;
import com.trackam.repository.ChatMessageRepository;
import com.trackam.repository.ChatSessionRepository;
import com.trackam.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GDPR / Ghana DPA compliance endpoints.
 * GET  /api/user/export — download all user data as JSON
 * DELETE /api/user/data  — erase all user data (right to erasure)
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserDataController {

    private final BusinessProfileRepository profileRepo;
    private final TransactionRepository txRepo;
    private final ChatMessageRepository chatMessageRepo;
    private final ChatSessionRepository chatSessionRepo;
    private final AuditLogRepository auditRepo;

    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> export(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("exportedAt", LocalDateTime.now().toString());
        data.put("userId", userId);
        data.put("profile", profileRepo.findById(userId).orElse(null));
        data.put("transactions", txRepo.findByUserIdOrderByDateDesc(userId));
        data.put("chatSessions", chatSessionRepo.findByUserIdOrderByUpdatedAtDesc(userId));
        data.put("chatMessages", chatMessageRepo.findByUserIdOrderByCreatedAtDesc(userId));

        return ResponseEntity.ok(data);
    }

    @DeleteMapping("/data")
    @Transactional
    public ResponseEntity<Void> deleteData(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();

        chatMessageRepo.deleteByUserId(userId);
        chatSessionRepo.findByUserIdOrderByUpdatedAtDesc(userId)
            .forEach(chatSessionRepo::delete);
        txRepo.findByUserIdOrderByDateDesc(userId).forEach(txRepo::delete);
        profileRepo.deleteById(userId);
        auditRepo.anonymizeByUserId(userId); // keep records, remove PII (compliance)

        return ResponseEntity.noContent().build();
    }
}
