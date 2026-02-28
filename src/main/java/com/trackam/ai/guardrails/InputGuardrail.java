package com.trackam.ai.guardrails;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Input guardrails — run before AI calls.
 * Rejects prompt injection, non-financial queries, and oversized inputs.
 */
public final class InputGuardrail {

    private static final int MAX_TEXT_LENGTH = 500;

    // Prompt injection patterns: instructions trying to override system prompt
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
        Pattern.compile("ignore (previous|above|all) instructions", Pattern.CASE_INSENSITIVE),
        Pattern.compile("you are now", Pattern.CASE_INSENSITIVE),
        Pattern.compile("system prompt", Pattern.CASE_INSENSITIVE),
        Pattern.compile("forget (everything|all|your)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("pretend (you are|to be)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("act as (a|an)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("jailbreak", Pattern.CASE_INSENSITIVE),
        Pattern.compile("<\\|.*?\\|>") // model-specific injection tokens
    );

    // At least one financial keyword must be present for text parsing
    private static final List<String> FINANCIAL_KEYWORDS = List.of(
        "paid", "bought", "sold", "received", "spent", "earned", "cost",
        "price", "money", "cash", "transfer", "momo", "fee", "salary",
        "income", "expense", "cedis", "naira", "ghs", "ngn", "ksh",
        "ghc", "gh₵", "₵", "₦", "market", "shop", "food", "transport",
        "bill", "rent", "fuel", "airtime", "data"
    );

    private InputGuardrail() {}

    public static void validateText(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Input text cannot be empty.");
        }
        if (input.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                "Input too long. Maximum " + MAX_TEXT_LENGTH + " characters.");
        }
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(input).find()) {
                throw new SecurityException("Invalid input: contains disallowed patterns.");
            }
        }
        String lower = input.toLowerCase();
        boolean hasFinancialContext = FINANCIAL_KEYWORDS.stream().anyMatch(lower::contains);
        if (!hasFinancialContext) {
            // Soft check: numbers alone are often valid ("150 food")
            boolean hasNumbers = input.matches(".*\\d.*");
            if (!hasNumbers) {
                throw new IllegalArgumentException(
                    "Input doesn't appear to be a financial transaction. " +
                    "Example: 'paid 50 cedis for groceries'");
            }
        }
    }

    public static void validateAdvisorQuestion(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Question cannot be empty.");
        }
        if (question.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(
                "Question too long. Maximum " + MAX_TEXT_LENGTH + " characters.");
        }
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(question).find()) {
                throw new SecurityException("Invalid question: contains disallowed patterns.");
            }
        }
    }
}
