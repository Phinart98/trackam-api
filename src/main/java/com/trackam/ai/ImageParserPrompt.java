package com.trackam.ai;

import com.trackam.model.CustomCategory;
import java.util.List;

public class ImageParserPrompt {

    private static final String TEMPLATE = """
        You are reading an image for TrackAm, a financial tracker for Africa's informal economy.
        The image may be:
        1. A printed receipt (possibly crumpled or faded)
        2. A mobile money confirmation screenshot (MTN MoMo, M-Pesa, Airtel Money, Vodafone Cash)
        3. A handwritten receipt or invoice
        4. A bank SMS alert screenshot

        Extract the PRIMARY transaction from the image. If multiple line items exist, return
        the most significant one or an aggregate total as a single transaction.

        Rules:
        - MoMo "received" or "cash in" = income (category: momo)
        - MoMo "sent", "paid", "cash out" = expense
        - Extract: amount, vendor/sender/receiver name, date, description of items
        - Mark confidence low (0-40) if image is unclear but still return your best guess
        - IMPORTANT: Detect the ACTUAL currency shown on the receipt (look for $, €, £, USD, EUR, GBP, NGN, KES, ZAR symbols or codes)
        - The user's preferred currency is %s — but return the currency ACTUALLY on the receipt

        %s

        Return a single valid JSON object — no array wrapper:
        {
          "amount": number,
          "currency": "ISO 4217 currency code ACTUALLY shown on the receipt (e.g. USD, EUR, GHS, NGN)",
          "category": string,
          "type": "income" or "expense",
          "description": string,
          "vendor": string,
          "date": "YYYY-MM-DD",
          "confidence": number 0-100
        }

        No markdown. No extra text. No wrapper object.
        """;

    public static String build(List<CustomCategory> customCategories, String userCurrency) {
        return TEMPLATE.formatted(userCurrency, CategoryPromptHelper.buildCategorySection(customCategories));
    }
}
