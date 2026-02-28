package com.trackam.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Central configuration properties for TrackAm.
 * All @Value fields live here — inject AppProperties, never scatter @Value across services.
 */
@Component
@Getter
public class AppProperties {

    // ── Auth / Supabase ──────────────────────────────────────────────────────
    @Value("${trackam.supabase.jwt-secret}")
    private String supabaseJwtSecret;

    @Value("${trackam.supabase.project-url:}")
    private String supabaseProjectUrl;

    @Value("${trackam.cors.allowed-origins}")
    private String corsAllowedOrigins;

    // ── Gemini AI ────────────────────────────────────────────────────────────
    @Value("${trackam.ai.gemini.api-key:}")
    private String geminiApiKey;

    @Value("${trackam.ai.gemini.base-url}")
    private String geminiBaseUrl;

    @Value("${trackam.ai.gemini.text-model}")
    private String geminiTextModel;

    @Value("${trackam.ai.gemini.complex-model}")
    private String geminiComplexModel;

    @Value("${trackam.ai.gemini.embedding-model:text-embedding-004}")
    private String geminiEmbeddingModel;

    // ── AI limits ────────────────────────────────────────────────────────────
    @Value("${trackam.ai.max-daily-calls:500}")
    private int maxDailyCalls;

    // ── Admin ────────────────────────────────────────────────────────────────
    @Value("${trackam.admin.user-id:}")
    private String adminUserId;
}
