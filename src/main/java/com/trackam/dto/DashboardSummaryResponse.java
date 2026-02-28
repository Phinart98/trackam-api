package com.trackam.dto;

import java.math.BigDecimal;
import java.util.List;

public record DashboardSummaryResponse(
    BigDecimal totalIncome,
    BigDecimal totalExpenses,
    BigDecimal balance,
    int transactionCount,
    String topCategory,
    String currency,
    List<CategoryBreakdown> categoryBreakdown
) {
    public record CategoryBreakdown(
        String category,
        BigDecimal amount,
        double percentage
    ) {}
}
