package com.trackam.ai.guardrails;

import com.trackam.dto.ParsedTransactionResponse;
import com.trackam.exception.TrackAmException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
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

    /** Validates with only built-in categories (used when no user context is available). */
    public static ParsedTransactionResponse validate(ParsedTransactionResponse response) {
        return validate(response, Collections.emptyList());
    }

    /** Validates and includes user's custom category IDs as additionally valid categories. */
    public static ParsedTransactionResponse validate(ParsedTransactionResponse response, List<String> customCategoryIds) {
        if (response == null) {
            throw new TrackAmException("AI returned empty response. Please try again.");
        }

        BigDecimal amount = validateAmount(response.amount());
        String type = validateType(response.type());
        String category = validateCategory(response.category(), type, customCategoryIds);
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
            confidence,
            null, null, null  // FX fields populated by AiService after guardrail
        );
    }

    private static BigDecimal validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(MIN_AMOUNT) < 0) {
            throw new TrackAmException("AI returned invalid amount. Please enter manually.");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new TrackAmException("Amount exceeds maximum (1,000,000). Please verify.");
        }
        return amount;
    }

    private static String validateType(String type) {
        if (type == null || !VALID_TYPES.contains(type.toLowerCase())) {
            return "expense"; // safe default
        }
        return type.toLowerCase();
    }

    private static String validateCategory(String category, String type, List<String> customCategoryIds) {
        if (category == null) return "income".equals(type) ? "other_income" : "other_expense";
        String lower = category.toLowerCase();
        if (VALID_CATEGORIES.contains(lower)) return lower;
        // Allow custom user-defined category IDs through without downcasing (they may be user-defined slugs)
        if (customCategoryIds != null && customCategoryIds.contains(category)) return category;
        return "income".equals(type) ? "other_income" : "other_expense";
    }

    private static String validateDate(String date) {
        if (date == null || date.isBlank()) {
            return Instant.now().toString();
        }
        try {
            LocalDateTime parsed = LocalDateTime.parse(date);
            if (isTooFarInFuture(parsed)) return Instant.now().toString();
            return parsed.toInstant(ZoneOffset.UTC).toString();
        } catch (DateTimeParseException e1) {
            try {
                // Date-only format ("2026-03-25") — common from image parser
                LocalDateTime parsed = LocalDate.parse(date).atStartOfDay();
                if (isTooFarInFuture(parsed)) return Instant.now().toString();
                return parsed.toInstant(ZoneOffset.UTC).toString();
            } catch (DateTimeParseException e2) {
                return Instant.now().toString();
            }
        }
    }

    private static boolean isTooFarInFuture(LocalDateTime dt) {
        return dt.isAfter(LocalDateTime.now().plusYears(1));
    }

    private static int validateConfidence(int confidence) {
        if (confidence < MIN_CONFIDENCE || confidence > MAX_CONFIDENCE) {
            return DEFAULT_CONFIDENCE;
        }
        return confidence;
    }

    private static String sanitizeText(String text) {
        if (text == null) return null;
        String cleaned = text.replaceAll("[\\p{Cntrl}]", "").strip();
        return cleaned.substring(0, Math.min(cleaned.length(), 500));
    }
}
