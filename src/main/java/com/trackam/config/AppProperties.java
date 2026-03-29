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

    @Value("${trackam.ai.gemini.embedding-model:gemini-embedding-001}")
    private String geminiEmbeddingModel;

    // ── Cerebras AI (text-only fallback) ─────────────────────────────────────
    @Value("${trackam.ai.cerebras.api-key:}")
    private String cerebrasApiKey;

    @Value("${trackam.ai.cerebras.base-url:https://api.cerebras.ai/v1}")
    private String cerebrasBaseUrl;

    @Value("${trackam.ai.cerebras.model:gpt-oss-120b}")
    private String cerebrasModel;

    // ── AI limits ────────────────────────────────────────────────────────────
    @Value("${trackam.ai.max-daily-calls:500}")
    private int maxDailyCalls;

    // ── Exchange rates ──────────────────────────────────────────────────────
    @Value("${trackam.exchange.base-url:https://api.frankfurter.dev}")
    private String exchangeBaseUrl;

    // ── Admin ────────────────────────────────────────────────────────────────
    @Value("${trackam.admin.user-id:}")
    private String adminUserId;
}
