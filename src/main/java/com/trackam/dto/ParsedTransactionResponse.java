package com.trackam.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Returned by AI parse endpoints AND used as Spring AI structured output target.
 * Field names must exactly match the Nuxt TypeScript ParsedTransaction interface.
 */
public record ParsedTransactionResponse(
    @JsonProperty("amount") BigDecimal amount,
    @JsonProperty("currency") String currency,
    @JsonProperty("category") String category,
    @JsonProperty("type") String type,           // "income" | "expense"
    @JsonProperty("description") String description,
    @JsonProperty("vendor") String vendor,
    @JsonProperty("date") String date,            // ISO 8601
    @JsonProperty("confidence") int confidence    // 0-100
) {}
