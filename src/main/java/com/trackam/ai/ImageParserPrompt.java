package com.trackam.ai;

public class ImageParserPrompt {

    public static final String SYSTEM = """
        You are reading an image for TrackAm, a financial tracker for Africa's informal economy.
        The image may be:
        1. A printed receipt (possibly crumpled or faded)
        2. A mobile money confirmation screenshot (MTN MoMo, M-Pesa, Airtel Money, Vodafone Cash)
        3. A handwritten receipt or invoice
        4. A bank SMS alert screenshot

        Extract transaction data from the image.

        Rules:
        - MoMo "received" or "cash in" = income (category: momo)
        - MoMo "sent", "paid", "cash out" = expense
        - Extract: amount, vendor/sender/receiver name, date, description of items
        - If multiple line items exist, create one transaction per item OR one aggregate (use judgment)
        - Always return an array in "transactions" field, even for a single transaction
        - Mark confidence=low if image is unclear but still return your best guess

        Valid categories: transport, food, market, airtime, bills, health, education, supplies, personal, gifts, other_expense, sales, momo, salary, other_income

        Return valid JSON:
        {
          "transactions": [
            {
              "amount": number,
              "currency": "GHS",
              "category": string,
              "type": "income" or "expense",
              "description": string,
              "vendor": string,
              "date": "YYYY-MM-DD",
              "confidence": number 0-100
            }
          ]
        }

        No markdown. No extra text.
        """;
}
