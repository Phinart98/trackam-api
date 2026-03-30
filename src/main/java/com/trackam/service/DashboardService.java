package com.trackam.service;

import com.trackam.dto.DashboardSummaryResponse;
import com.trackam.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final TransactionRepository repo;

    public DashboardSummaryResponse getSummary(UUID userId) {
        // SQL aggregates — no full table load into memory
        BigDecimal totalIncome = Objects.requireNonNullElse(repo.sumByUserIdAndType(userId, "income"), BigDecimal.ZERO);
        BigDecimal totalExpenses = Objects.requireNonNullElse(repo.sumByUserIdAndType(userId, "expense"), BigDecimal.ZERO);
        BigDecimal balance = totalIncome.subtract(totalExpenses);
        long txCount = repo.countByUserId(userId);

        String currency = repo.findLatestCurrencyByUserId(userId).orElse("GHS");

        List<Object[]> categoryTotals = repo.getCategoryTotals(userId);
        List<DashboardSummaryResponse.CategoryBreakdown> breakdown = categoryTotals.stream()
            .map(row -> {
                String cat = (String) row[0];
                BigDecimal amount = (BigDecimal) row[1];
                double pct = totalExpenses.compareTo(BigDecimal.ZERO) == 0
                    ? 0
                    : amount.divide(totalExpenses, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).doubleValue();
                return new DashboardSummaryResponse.CategoryBreakdown(cat, amount, pct);
            })
            .collect(Collectors.toList());

        String topCategory = breakdown.isEmpty() ? "other_expense" : breakdown.get(0).category();

        return new DashboardSummaryResponse(
            totalIncome, totalExpenses, balance,
            (int) txCount, topCategory, currency, breakdown
        );
    }
}
