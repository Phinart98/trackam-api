package com.trackam.service;

import com.trackam.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Generates text embeddings via Google's native Gemini API (gemini-embedding-001, 768 dims).
 *
 * Uses the native embedContent endpoint — NOT the OpenAI-compatible shim — because the
 * OpenAI-compat endpoint does not reliably support the 'dimensions' parameter for truncation
 * (see GitHub LiteLLM #9654). The native endpoint guarantees outputDimensionality control.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {

    private static final String EMBED_URL_TEMPLATE =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:embedContent";

    private static final int OUTPUT_DIMENSIONS = 768;
    private static final int MAX_INPUT_CHARS = 2048;

    private final AppProperties props;
    private final RestTemplate restTemplate;

    /**
     * Embed text into a float[768] vector using Gemini's native API.
     * Returns null if API key not configured (embedding is optional — app still works).
     */
    public float[] embed(String text) {
        if (props.getGeminiApiKey() == null || props.getGeminiApiKey().isBlank()) {
            log.debug("Gemini API key not set — skipping embedding generation.");
            return null;
        }
        if (text == null || text.isBlank()) {
            return null;
        }

        try {
            String url = EMBED_URL_TEMPLATE.formatted(props.getGeminiEmbeddingModel());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-goog-api-key", props.getGeminiApiKey());

            String truncated = text.substring(0, Math.min(text.length(), MAX_INPUT_CHARS));

            Map<String, Object> body = Map.of(
                "content", Map.of("parts", List.of(
                    Map.of("text", truncated)
                )),
                "taskType", "SEMANTIC_SIMILARITY",
                "outputDimensionality", OUTPUT_DIMENSIONS
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            @SuppressWarnings("unchecked")
            ResponseEntity<Map<String, Object>> response =
                restTemplate.postForEntity(url, request, (Class<Map<String, Object>>) (Class<?>) Map.class);

            if (response.getBody() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> embedding = (Map<String, Object>) response.getBody().get("embedding");
                if (embedding != null) {
                    @SuppressWarnings("unchecked")
                    List<Number> values = (List<Number>) embedding.get("values");
                    float[] result = new float[values.size()];
                    for (int i = 0; i < values.size(); i++) {
                        result[i] = values.get(i).floatValue();
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            log.warn("Embedding generation failed: {}", e.getMessage());
        }
        return null;
    }
}
