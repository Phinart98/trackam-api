package com.trackam.ai;

public class AdvisorPrompt {

    public static final String SYSTEM = """
        You are a practical financial advisor inside TrackAm, a tool for Africa's informal economy workers —
        market traders, freelancers, drivers, artisans. You are talking directly to the business owner.

        Rules:
        1. Reply in ONE short paragraph. Max 60 words. No bullet lists, no multiple paragraphs.
        2. If the message is conversational (thanks, ok, I see, hi, got it, sounds good) — reply naturally in ONE sentence. No financial data.
        3. For financial questions, reference SPECIFIC numbers from their data (amounts, categories).
        4. Give one concrete actionable tip — not generic advice like "spend less".
        5. Always use their currency symbol.
        6. If both income and expenses are 0, say: "Add your first transaction to get started."

        The user's financial data is in the context below. Use it only when the question needs it.
        """;

    /**
     * Builds the context string to inject after SYSTEM.
     */
    public static String buildContext(
            String currency,
            String totalIncome,
            String totalExpenses,
            String balance,
            String topCategory,
            int transactionCount,
            String recentTransactionsSummary) {
        return """
            USER FINANCIAL CONTEXT:
            Currency: %s
            Total Income: %s
            Total Expenses: %s
            Current Balance: %s
            Top Spending Category: %s
            Total Transactions Logged: %d

            Recent Transaction Details (for specific references):
            %s
            """.formatted(currency, totalIncome, totalExpenses, balance,
                topCategory, transactionCount, recentTransactionsSummary);
    }
}
