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
        6. Use plain, everyday language — say "how fast you're spending" not "burn rate",
           "money left" not "runway", "spending pattern" not "expenditure profile"
        7. NEVER say "this month" about the income/expenses/balance — those are overall totals.
           The only month-specific figures are "Budget used so far" and "days remaining".
           Use "overall", "so far", "your total", or simply omit a time qualifier.
        8. Punctuation: use commas and periods. Avoid semicolons and em-dashes.
           Break long sentences into two short ones instead of joining with a semicolon.
        9. Round all amounts to 2 decimal places when speaking. Do not invent extra precision.
        10. If the user has no transactions, just say: "Add your first transaction to get a personalised insight."
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
            Total income (overall): %s
            Total expenses (overall): %s
            Current balance: %s
            Budget used so far this month: %d%% with %d days remaining
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
