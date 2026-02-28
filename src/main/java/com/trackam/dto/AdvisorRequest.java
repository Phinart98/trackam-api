package com.trackam.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record AdvisorRequest(
    @NotBlank String question,
    AdvisorContext context,
    String sessionId  // nullable — null starts a new session
) {
    public record AdvisorContext(
        BigDecimal totalIncome,
        BigDecimal totalExpenses,
        BigDecimal balance,
        String topCategory,
        int transactionCount,
        String currency
    ) {}
}
