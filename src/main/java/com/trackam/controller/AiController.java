package com.trackam.controller;

import java.io.IOException;
import java.util.Map;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.trackam.dto.AdvisorRequest;
import com.trackam.dto.AdvisorResponse;
import com.trackam.dto.InsightRequest;
import com.trackam.dto.ParseTextRequest;
import com.trackam.dto.ParsedTransactionResponse;
import com.trackam.service.AiService;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private static final String DEFAULT_CURRENCY = "GHS";

    private final AiService aiService;

    @PostMapping("/parse-text")
    public ResponseEntity<ParsedTransactionResponse> parseText(
            @RequestBody @Valid ParseTextRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        String currency = request.currency() != null ? request.currency() : DEFAULT_CURRENCY;
        return ResponseEntity.ok(aiService.parseText(request.text(), currency, userId));
    }

    @PostMapping("/parse-image")
    public ResponseEntity<ParsedTransactionResponse> parseImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "currency", required = false) String currency,
            @AuthenticationPrincipal Jwt jwt) throws IOException {
        String userId = jwt.getSubject();
        String userCurrency = (currency != null && !currency.isBlank()) ? currency : DEFAULT_CURRENCY;
        return ResponseEntity.ok(aiService.parseImage(file, userCurrency, userId));
    }

    @PostMapping("/insight")
    public ResponseEntity<Map<String, String>> insight(
            @RequestBody InsightRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        String text = aiService.generateInsight(request, userId);
        return ResponseEntity.ok(Map.of("insight", text));
    }

    @PostMapping("/advisor")
    public ResponseEntity<AdvisorResponse> advisor(
            @RequestBody @Valid AdvisorRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        return ResponseEntity.ok(aiService.askAdvisor(request.question(), request.context(), request.sessionId(), userId));
    }
}
