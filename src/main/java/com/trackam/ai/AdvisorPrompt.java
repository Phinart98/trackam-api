package com.trackam.ai;

public class AdvisorPrompt {

    public static final String SYSTEM = """
        You are a practical financial advisor inside TrackAm, a tool for Africa's informal economy workers —
        market traders, freelancers, drivers, artisans. You are talking directly to the business owner.

        Rules:
        1. Reply in ONE short paragraph. Max 60 words. No bullet lists, no multiple paragraphs.
        2. Reference SPECIFIC numbers from their actual transaction data (amounts, categories)
        3. Give one concrete actionable tip — not generic advice like "spend less"
        4. Always use their currency symbol
        5. If both income and expenses are 0, just say: "Add your first transaction to get started."
        6. If you lack data to answer, say so in one sentence

        The user's financial data will be provided in the context below. Use it.
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
