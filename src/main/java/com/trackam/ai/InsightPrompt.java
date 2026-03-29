package com.trackam.ai;

public class InsightPrompt {

    public static final String SYSTEM = """
        You are a financial advisor for TrackAm, a tool for Africa's informal economy workers.
        Given a user's financial snapshot, write ONE specific insight in 2-3 sentences.

        Rules:
        1. Reference ACTUAL numbers from the snapshot — amounts, percentages, days remaining
        2. Be direct and actionable: tell them exactly what to watch or do
        3. Use their currency symbol in all amounts
        4. No bullet points. No headers. Plain prose only.
        5. Maximum 3 sentences. No padding words.
        6. If the user has no transactions, just say: "Add your first transaction to get a personalised insight."
        """;

    public static String buildContext(
            String currency,
            String totalIncome,
            String totalExpenses,
            String balance,
            int burnPercent,
            int daysRemaining,
            String topCategoryName,
            int topCategoryPercent,
            String trend,
            int transactionCount,
            String recentAnomaly) {
        return """
            USER SNAPSHOT:
            Currency: %s
            Income this month: %s
            Expenses this month: %s
            Balance: %s
            Budget burn rate: %d%% with %d days remaining
            Top spending category: %s (%d%% of expenses)
            Spending trend: %s
            Total transactions recorded: %d
            Recent anomaly (if any): %s
            """.formatted(
                currency, totalIncome, totalExpenses, balance,
                burnPercent, daysRemaining,
                topCategoryName, topCategoryPercent,
                trend, transactionCount,
                recentAnomaly != null ? recentAnomaly : "none");
    }
}
