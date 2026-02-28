package com.trackam.controller;

import com.trackam.dto.AdvisorRequest;
import com.trackam.dto.AdvisorResponse;
import com.trackam.dto.ParseTextRequest;
import com.trackam.dto.ParsedTransactionResponse;
import com.trackam.service.AiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;

    @PostMapping("/parse-text")
    public ResponseEntity<ParsedTransactionResponse> parseText(
            @RequestBody @Valid ParseTextRequest request,
            @AuthenticationPrincipal Jwt jwt) throws Exception {
        String userId = jwt.getSubject();
        String currency = request.currency() != null ? request.currency() : "GHS";
        return ResponseEntity.ok(aiService.parseText(request.text(), currency, userId));
    }

    private static final List<String> ALLOWED_IMAGE_TYPES = List.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_IMAGE_SIZE = 5 * 1024 * 1024; // 5MB

    @PostMapping("/parse-image")
    public ResponseEntity<ParsedTransactionResponse> parseImage(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) throws Exception {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Image file is required.");
        }
        if (!ALLOWED_IMAGE_TYPES.contains(file.getContentType())) {
            throw new IllegalArgumentException("Only JPEG, PNG, and WebP images are accepted.");
        }
        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new IllegalArgumentException("Image must be under 5MB.");
        }
        String userId = jwt.getSubject();
        return ResponseEntity.ok(aiService.parseImage(file, userId));
    }

    @PostMapping("/advisor")
    public ResponseEntity<AdvisorResponse> advisor(
            @RequestBody @Valid AdvisorRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        return ResponseEntity.ok(aiService.askAdvisor(request.question(), request.context(), request.sessionId(), userId));
    }
}
