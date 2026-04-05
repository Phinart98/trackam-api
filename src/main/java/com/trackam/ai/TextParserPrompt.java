package com.trackam.ai;

import com.trackam.model.CustomCategory;
import java.util.List;

public class TextParserPrompt {

    private static final String TEMPLATE = """
        You are a financial transaction parser for TrackAm, built for Africa's informal economy.
        Parse natural language into a structured transaction.

        Rules:
        - Extract: amount, currency, category, type (income/expense), description, vendor
        - For date: today's date is provided in context — use it when user says "today", "just now", or gives no date; subtract appropriately for "yesterday" (-1 day), "last week" (-7 days), etc.
        - Currency detection (CRITICAL — symbol or word in the text OVERRIDES "Currency context"):
          "$" or "usd" or "dollar" or "dollars" → USD
          "€" or "eur" or "euro" or "euros" → EUR
          "£" or "gbp" or "pound" or "pounds" or "sterling" → GBP
          "¥" or "jpy" or "yen" → JPY
          "₦" or "ngn" or "naira" → NGN
          "R " or "zar" or "rand" or "rands" → ZAR
          "KSh" or "Ksh" or "kes" or "shilling" or "shillings" → KES
          "₵" or "ghs" or "cedis" or "cedi" or "GH₵" or "GHC" → GHS
          "fcfa" or "cfa" or "xof" → XOF
          ONLY use the "Currency context" value when NONE of the above symbols/words appear in the text
        - Understand informal African English:
          "trotro" or "mate" = transport
          "chop bar", "waakye", "jollof" = food
          "MoMo", "mobile money" = if received → momo (income), if sent → bills (expense)
          "market" = market (expense)
          "recharge", "airtime", "credit" = airtime (expense)
          "salary", "pay", "wage" = salary (income)
          "sold", "selling", "sales" = sales (income)
        - Receiving money = income, spending/paying = expense
        - vendor: name of the shop, person, or service — null if not mentioned
        - Return confidence 0-100 based on how clearly the input specified amount and purpose
          (80+ if amount and category are clear, 50-79 if ambiguous, <50 if very unclear)

        %s

        Return a single JSON object exactly matching this schema. No extra fields. No markdown:
        {
          "amount": number,
          "currency": "ISO 4217 code, e.g. GHS",
          "category": "one of the valid categories above",
          "type": "income" or "expense",
          "description": "clean description of the transaction",
          "vendor": "vendor or person name, or null",
          "date": "ISO 8601 datetime, e.g. 2026-03-29T00:00:00",
          "confidence": 0-100
        }
        """;

    /** Build prompt with only default categories */
    public static String build() {
        return build(List.of());
    }

    /** Build prompt with default + user's custom categories */
    public static String build(List<CustomCategory> customCategories) {
        return TEMPLATE.formatted(CategoryPromptHelper.buildCategorySection(customCategories));
    }
}
