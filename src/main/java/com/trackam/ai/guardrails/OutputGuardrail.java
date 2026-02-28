package com.trackam.ai.guardrails;

import com.trackam.dto.ParsedTransactionResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * Output guardrails — run after AI returns a result.
 * Catches hallucinated amounts, future dates, and invalid categories/types.
 */
public final class OutputGuardrail {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000");
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");
    private static final int MIN_CONFIDENCE = 0;
    private static final int MAX_CONFIDENCE = 100;
    private static final int DEFAULT_CONFIDENCE = 70;

    private static final Set<String> VALID_TYPES = Set.of("income", "expense");

    private static final Set<String> VALID_CATEGORIES = Set.of(
        "transport", "food", "market", "airtime", "bills", "health",
        "education", "supplies", "personal", "gifts", "sales", "momo",
        "salary", "other_income", "other_expense"
    );

    private OutputGuardrail() {}

    public static ParsedTransactionResponse validate(ParsedTransactionResponse response) {
        if (response == null) {
            throw new RuntimeException("AI returned empty response. Please try again.");
        }

        BigDecimal amount = validateAmount(response.amount());
        String type = validateType(response.type());
        String category = validateCategory(response.category(), type);
        String date = validateDate(response.date());
        int confidence = validateConfidence(response.confidence());

        // Return sanitized response
        return new ParsedTransactionResponse(
            amount,
            response.currency(),
            category,
            type,
            sanitizeText(response.description()),
            sanitizeText(response.vendor()),
            date,
            confidence
        );
    }

    private static BigDecimal validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(MIN_AMOUNT) < 0) {
            throw new RuntimeException("AI returned invalid amount. Please enter manually.");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new RuntimeException("Amount exceeds maximum (1,000,000). Please verify.");
        }
        return amount;
    }

    private static String validateType(String type) {
        if (type == null || !VALID_TYPES.contains(type.toLowerCase())) {
            return "expense"; // safe default
        }
        return type.toLowerCase();
    }

    private static String validateCategory(String category, String type) {
        if (category == null || !VALID_CATEGORIES.contains(category.toLowerCase())) {
            return "income".equals(type) ? "other_income" : "other_expense";
        }
        return category.toLowerCase();
    }

    private static String validateDate(String date) {
        if (date == null || date.isBlank()) {
            return LocalDateTime.now().toString();
        }
        try {
            LocalDateTime parsed = LocalDateTime.parse(date);
            // Reject dates more than 1 year in the future (likely hallucination)
            if (parsed.isAfter(LocalDateTime.now().plusYears(1))) {
                return LocalDateTime.now().toString();
            }
            return date;
        } catch (DateTimeParseException e) {
            return LocalDateTime.now().toString();
        }
    }

    private static int validateConfidence(int confidence) {
        if (confidence < MIN_CONFIDENCE || confidence > MAX_CONFIDENCE) {
            return DEFAULT_CONFIDENCE;
        }
        return confidence;
    }

    private static String sanitizeText(String text) {
        if (text == null) return null;
        // Remove control characters, limit length
        return text.replaceAll("[\\p{Cntrl}]", "")
                   .strip()
                   .substring(0, Math.min(text.length(), 500));
    }
}
