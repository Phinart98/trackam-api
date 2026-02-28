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
 * Generates text embeddings via Gemini text-embedding-004 (768 dimensions).
 * Uses the OpenAI-compatible endpoint to keep a single HTTP client pattern.
 *
 * Model note: text-embedding-004 is being deprecated in favor of gemini-embedding-001
 * (3072 dims). Migrate by: update model name + schema column size + HNSW index m/ef params.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {

    private static final String EMBEDDING_URL =
        "https://generativelanguage.googleapis.com/v1beta/openai/embeddings";

    private final AppProperties props;
    private final RestTemplate restTemplate;

    /**
     * Embed text into a float[768] vector.
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
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(props.getGeminiApiKey());

            Map<String, Object> body = Map.of(
                "model", props.getGeminiEmbeddingModel(),
                "input", text.substring(0, Math.min(text.length(), 2048))
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(
                EMBEDDING_URL, request, Map.class);

            if (response.getBody() != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> data = (List<Map<String, Object>>) response.getBody().get("data");
                if (data != null && !data.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    List<Double> embedding = (List<Double>) data.get(0).get("embedding");
                    float[] result = new float[embedding.size()];
                    for (int i = 0; i < embedding.size(); i++) {
                        result[i] = embedding.get(i).floatValue();
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
