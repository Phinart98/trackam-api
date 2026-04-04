package com.trackam.ai.guardrails;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.web.multipart.MultipartFile;

/**
 * Input guardrails — run before AI calls.
 * Rejects prompt injection, non-financial queries, and oversized inputs.
 */
public final class InputGuardrail {

    private static final int MAX_TEXT_LENGTH = 500;
    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024; // 10 MB

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
            // Soft check: short numeric inputs are likely valid ("150 food", "50 rice")
            // but long inputs without financial keywords are likely off-topic
            boolean isShortNumericEntry = input.matches(".*\\d.*") && input.trim().split("\\s+").length <= 6;
            if (!isShortNumericEntry) {
                throw new IllegalArgumentException(
                    "Input doesn't appear to be a financial transaction. " +
                    "Example: 'paid 50 cedis for groceries'");
            }
        }
    }

    /**
     * Validate image file by reading magic bytes — do NOT trust Content-Type header.
     * Accepts: JPEG, PNG, WebP, GIF.
     */
    public static void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Image file is required.");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("Image too large. Maximum 10 MB.");
        }
        try {
            byte[] bytes = file.getBytes();
            byte[] header = bytes.length >= 12 ? Arrays.copyOf(bytes, 12) : bytes;
            if (!isAllowedImageType(header)) {
                throw new IllegalArgumentException("Only JPEG, PNG, WebP, and GIF images are accepted.");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read image file.");
        }
    }

    private static boolean isAllowedImageType(byte[] h) {
        if (h.length < 4) return false;
        // JPEG: FF D8 FF
        if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) return true;
        // PNG: 89 50 4E 47
        if ((h[0] & 0xFF) == 0x89 && h[1] == 0x50 && h[2] == 0x4E && h[3] == 0x47) return true;
        // GIF: 47 49 46 38
        if (h[0] == 0x47 && h[1] == 0x49 && h[2] == 0x46 && h[3] == 0x38) return true;
        // WebP: RIFF????WEBP (bytes 0-3 = "RIFF", bytes 8-11 = "WEBP")
        if (h.length >= 12 && h[0] == 0x52 && h[1] == 0x49 && h[2] == 0x46 && h[3] == 0x46
                && h[8] == 0x57 && h[9] == 0x45 && h[10] == 0x42 && h[11] == 0x50) return true;
        return false;
    }

    // startsWith match — "audio/webm" covers "audio/webm;codecs=opus", etc.
    private static final List<String> ALLOWED_AUDIO_TYPES = List.of(
        "audio/webm", "audio/ogg", "audio/mp4", "audio/mpeg"
    );

    /**
     * Validate audio upload: size limit + MIME type allowlist.
     * We trust Content-Type (not magic bytes) because audio codec headers vary too widely.
     * The file goes straight to Groq — no execution risk from a mistyped MIME.
     */
    public static void validateAudio(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Audio file is required.");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("Audio file too large. Maximum 10 MB.");
        }
        String contentType = file.getContentType();
        if (contentType == null || ALLOWED_AUDIO_TYPES.stream().noneMatch(contentType::startsWith)) {
            throw new IllegalArgumentException("Unsupported audio format. Use WebM, OGG, or MP4.");
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
