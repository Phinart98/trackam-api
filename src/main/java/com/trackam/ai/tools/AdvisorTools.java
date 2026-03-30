package com.trackam.ai.tools;

import com.trackam.model.Transaction;
import com.trackam.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Spring AI @Tool methods for the financial advisor.
 *
 * Each method queries the user's actual transaction data via SQL.
 * userId is extracted from ToolContext (server-side, invisible to the LLM)
 * to prevent hallucinated user IDs from leaking across accounts.
 *
 * The LLM picks which tools to call based on the user's question, Spring AI
 * handles the tool-calling loop automatically, and the LLM generates a
 * natural-language answer from the tool results.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdvisorTools {

    private final TransactionRepository txRepo;

    @Tool(description = "Get total spending broken down by category. " +
        "Returns each expense category with total amount and percentage of total spending. " +
        "Use this when the user asks about where their money goes, spending breakdown, or category analysis.")
    public String getSpendingByCategory(ToolContext ctx) {
        UUID userId = extractUserId(ctx);
        List<Object[]> totals = txRepo.getCategoryTotals(userId);

        if (totals.isEmpty()) return "No expense transactions found.";

        // Derive total from category rows — avoids a second SQL round-trip
        BigDecimal totalExpenses = totals.stream()
            .map(row -> Objects.requireNonNullElse((BigDecimal) row[1], BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        StringBuilder sb = new StringBuilder("Spending by category:\n");
        for (Object[] row : totals) {
            String category = (String) row[0];
            BigDecimal amount = Objects.requireNonNullElse((BigDecimal) row[1], BigDecimal.ZERO);
            double pct = totalExpenses.compareTo(BigDecimal.ZERO) == 0 ? 0
                : amount.divide(totalExpenses, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).doubleValue();
            sb.append("- %s: %s (%.1f%%)\n".formatted(category, amount.toPlainString(), pct));
        }
        sb.append("Total expenses: ").append(totalExpenses.toPlainString());
        return sb.toString();
    }

    @Tool(description = "Get the user's largest individual expenses ranked by amount. " +
        "Use this when the user asks about their biggest purchases, top expenses, or largest transactions.")
    public String getTopExpenses(
            @ToolParam(description = "Number of top expenses to return, between 1 and 20") int limit,
            ToolContext ctx) {
        UUID userId = extractUserId(ctx);
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<Transaction> top = txRepo.findTopExpenses(userId, PageRequest.of(0, safeLimit));

        if (top.isEmpty()) return "No expense transactions found.";

        return "Top %d expenses:\n".formatted(top.size()) + top.stream()
            .map(t -> "- %s: %s %s — %s (%s)".formatted(
                t.getDate().atZone(ZoneOffset.UTC).toLocalDate(), t.getCurrency(), t.getAmount().toPlainString(),
                t.getDescription(), t.getCategory()))
            .collect(Collectors.joining("\n"));
    }

    @Tool(description = "Get a financial summary for a specific month and year. " +
        "Returns total income, total expenses, net balance, and transaction count. " +
        "Use this when the user asks about a specific month's performance or monthly comparison.")
    public String getMonthlySummary(
            @ToolParam(description = "Year, e.g. 2026") int year,
            @ToolParam(description = "Month number 1-12, e.g. 3 for March") int month,
            ToolContext ctx) {
        UUID userId = extractUserId(ctx);
        Instant from = LocalDate.of(year, month, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = LocalDate.of(year, month, 1).plusMonths(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        List<Transaction> monthly = txRepo.findByUserIdAndDateBetweenOrderByDateDesc(userId, from, to);

        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expenses = BigDecimal.ZERO;
        for (Transaction t : monthly) {
            if ("income".equals(t.getType())) income = income.add(t.getAmount());
            else expenses = expenses.add(t.getAmount());
        }

        BigDecimal net = income.subtract(expenses);
        return """
            Monthly summary for %d-%02d:
            - Income: %s
            - Expenses: %s
            - Net: %s
            - Transactions: %d""".formatted(
            year, month, income.toPlainString(), expenses.toPlainString(), net.toPlainString(), monthly.size());
    }

    @Tool(description = "Get transactions within a date range. " +
        "Returns date, type (income/expense), category, amount, and description for each. " +
        "Use this when the user asks about transactions in a specific period.")
    public String getTransactionsByDateRange(
            @ToolParam(description = "Start date in YYYY-MM-DD format") String from,
            @ToolParam(description = "End date in YYYY-MM-DD format") String to,
            ToolContext ctx) {
        UUID userId = extractUserId(ctx);

        Instant fromDt;
        Instant toDt;
        try {
            fromDt = LocalDate.parse(from).atStartOfDay().toInstant(ZoneOffset.UTC);
            toDt = LocalDate.parse(to).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return "Invalid date format. Use YYYY-MM-DD.";
        }

        List<Transaction> txs = txRepo.findByUserIdAndDateBetweenOrderByDateDesc(userId, fromDt, toDt);

        if (txs.isEmpty()) return "No transactions found between %s and %s.".formatted(from, to);

        return "Transactions from %s to %s (%d total):\n".formatted(from, to, txs.size())
            + formatTransactionList(txs);
    }

    @Tool(description = "Find recurring expenses — vendors the user pays 3 or more times. " +
        "Shows vendor name, category, frequency, and total amount spent. " +
        "Use this when the user asks about regular bills, subscriptions, or repeated spending.")
    public String getRecurringExpenses(ToolContext ctx) {
        UUID userId = extractUserId(ctx);
        List<Object[]> recurring = txRepo.findRecurringVendors(userId);

        if (recurring.isEmpty()) {
            return "No recurring vendors found (need 3+ transactions from the same vendor).";
        }

        StringBuilder sb = new StringBuilder("Recurring expenses (3+ transactions):\n");
        for (Object[] row : recurring) {
            String vendor = (String) row[0];
            String category = (String) row[1];
            long count = ((Number) row[2]).longValue();
            BigDecimal total = (BigDecimal) row[3];
            sb.append("- %s (%s): %d times, total %s\n".formatted(vendor, category, count, total.toPlainString()));
        }
        return sb.toString();
    }

    @Tool(description = "Calculate the user's profit margin — income vs expenses ratio. " +
        "Returns total income, total expenses, net profit, and profit margin percentage. " +
        "Use this when the user asks about profitability, profit margin, or whether they're making money.")
    public String getProfitMargin(ToolContext ctx) {
        UUID userId = extractUserId(ctx);
        BigDecimal income = Objects.requireNonNullElse(txRepo.sumByUserIdAndType(userId, "income"), BigDecimal.ZERO);
        BigDecimal expenses = Objects.requireNonNullElse(txRepo.sumByUserIdAndType(userId, "expense"), BigDecimal.ZERO);
        BigDecimal net = income.subtract(expenses);

        double margin = income.compareTo(BigDecimal.ZERO) == 0 ? 0
            : net.divide(income, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();

        return """
            Profit analysis:
            - Total income: %s
            - Total expenses: %s
            - Net profit: %s
            - Profit margin: %.1f%%""".formatted(income.toPlainString(), expenses.toPlainString(), net.toPlainString(), margin);
    }

    @Tool(description = "Get the user's most recent transactions. " +
        "Returns date, type, category, amount, and description for each. " +
        "Use this when the user asks about recent activity, latest transactions, or what happened recently.")
    public String getRecentTransactions(
            @ToolParam(description = "Number of recent transactions to return, between 1 and 30") int limit,
            ToolContext ctx) {
        UUID userId = extractUserId(ctx);
        int safeLimit = Math.max(1, Math.min(limit, 30));
        List<Transaction> txs = txRepo.findRecentTransactions(userId, PageRequest.of(0, safeLimit));

        if (txs.isEmpty()) return "No transactions found.";

        return "Last %d transactions:\n".formatted(txs.size()) + formatTransactionList(txs);
    }

    @Tool(description = "Search transactions by keyword in the description. " +
        "Use this when the user asks about specific purchases, vendors, or items by name. " +
        "For example: 'rice', 'transport', 'MTN', 'Makola'.")
    public String searchTransactions(
            @ToolParam(description = "Search keyword to find in transaction descriptions") String query,
            ToolContext ctx) {
        UUID userId = extractUserId(ctx);

        if (query == null || query.isBlank()) return "Please provide a search term.";

        // Escape LIKE wildcard chars so user input is treated as a literal substring
        String safeQuery = query.trim()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
        List<Transaction> results = txRepo.searchByDescription(userId, safeQuery);

        if (results.isEmpty()) {
            return "No transactions found matching '%s'.".formatted(query);
        }

        return "Found %d transactions matching '%s':\n".formatted(results.size(), query)
            + formatTransactionList(results);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private UUID extractUserId(ToolContext ctx) {
        Object raw = ctx.getContext().get("userId");
        if (raw == null) throw new IllegalStateException("userId not found in ToolContext");
        return raw instanceof UUID u ? u : UUID.fromString(raw.toString());
    }

    private String formatTransactionList(List<Transaction> transactions) {
        return transactions.stream()
            .map(t -> "- %s [%s] %s: %s %s — %s".formatted(
                t.getDate().atZone(ZoneOffset.UTC).toLocalDate(),
                t.getType(),
                t.getCategory(),
                t.getCurrency(),
                t.getAmount().toPlainString(),
                t.getDescription()))
            .collect(Collectors.joining("\n"));
    }
}
