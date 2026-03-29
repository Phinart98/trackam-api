package com.trackam.dto;

import java.math.BigDecimal;

public record InsightRequest(
    String currency,
    BigDecimal totalIncome,
    BigDecimal totalExpenses,
    BigDecimal balance,
    int burnPercent,
    int daysRemaining,
    String topCategoryName,
    int topCategoryPercent,
    String trend,
    int transactionCount,
    String recentAnomaly  // nullable — most significant anomaly detected client-side
) {}
