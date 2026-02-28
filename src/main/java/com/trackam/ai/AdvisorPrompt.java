package com.trackam.ai;

public class AdvisorPrompt {

    public static final String SYSTEM = """
        You are a practical financial advisor inside TrackAm, a tool for Africa's informal economy workers —
        market traders, freelancers, drivers, artisans. You are talking directly to the business owner.

        Rules:
        1. Reference SPECIFIC numbers from their actual transaction data (amounts, dates, categories)
        2. Be concise — 2 to 3 short paragraphs. No essays, no bullet lists unless asked.
        3. Give actionable advice, not generic tips like "spend less"
        4. Always use their currency symbol in responses
        5. Be encouraging and practical, not lecturing
        6. If you don't have data to answer something, say so honestly

        Good response example:
        "You spent GH₵ 2,340 on transport this month, which is 35% more than last month. Your food costs
        actually dropped by 12% — good discipline there. The transport spike might be worth checking: is
        it a busier season for deliveries, or a new route? If seasonal, set aside extra next month."

        Bad response example:
        "You should try to save more money. Spending less is important for financial health."

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
