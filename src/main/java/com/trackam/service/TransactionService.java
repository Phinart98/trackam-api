package com.trackam.service;

import com.trackam.dto.TransactionRequest;
import com.trackam.exception.TrackAmException;
import com.trackam.model.Transaction;
import com.trackam.repository.TransactionRepository;
import com.trackam.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    private final TransactionRepository repo;
    private final EmbeddingService embeddingService;
    private final ExchangeRateService fxService;

    public List<Transaction> getAll(UUID userId) {
        return repo.findByUserIdOrderByDateDescCreatedAtDesc(userId);
    }

    public Transaction create(TransactionRequest req, UUID userId) {
        Instant date = parseDate(req.date());
        Transaction tx = Transaction.builder()
            .userId(userId)
            .type(req.type())
            .amount(req.amount())
            .currency(req.currency())
            .category(req.category())
            .description(req.description())
            .vendor(req.vendor())
            .source(req.source())
            .date(date)
            .confidence(req.confidence())
            .build();

        // Generate embedding for RAG — use sanitized text only (OWASP LLM08:2025 embedding inversion risk)
        // We embed type + category + semantic keywords only, never raw descriptions or vendor names
        String textToEmbed = req.type() + " " + req.category() + " " + sanitizeForEmbedding(req.description());
        float[] embedding = embeddingService.embed(textToEmbed);
        if (embedding != null) {
            tx.setEmbedding(embedding);
        }

        return repo.save(tx);
    }

    /**
     * Strip PII from transaction description before embedding.
     * Removes: amounts (digits), proper nouns (Title Case words), location markers.
     * Keeps: common nouns, action verbs, category-relevant keywords.
     */
    private String sanitizeForEmbedding(String description) {
        if (description == null) return "";
        return description
            .replaceAll("\\b\\d+([.,]\\d+)?\\b", "")           // remove numbers/amounts
            .replaceAll("\\b[A-Z][a-z]{2,}\\b", "")            // remove Title Case words (names/places)
            .replaceAll("\\b(GHS|NGN|KES|USD|GH₵|₦|₵)\\b", "") // remove currency symbols
            .replaceAll("\\s{2,}", " ")
            .strip()
            .toLowerCase();
    }

    private static Instant parseDate(String raw) {
        try {
            return Instant.parse(raw);                                    // "2026-04-01T00:00:00Z"
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC);   // "2026-04-01T00:00:00"
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(raw).atStartOfDay().toInstant(ZoneOffset.UTC);  // "2026-04-01"
        } catch (DateTimeParseException e) {
            throw new TrackAmException("Invalid date format. Use YYYY-MM-DD or ISO date-time.");
        }
    }

    public void delete(UUID id, UUID userId) {
        Transaction tx = repo.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new TrackAmException("Transaction not found"));
        repo.delete(tx);
    }

    /** Converts all user transactions to a new currency using today's FX rate. */
    @Transactional
    public int convertCurrency(UUID userId, String fromCurrency, String toCurrency) {
        if (fromCurrency == null || toCurrency == null || fromCurrency.equalsIgnoreCase(toCurrency)) return 0;
        if (!fromCurrency.matches("[A-Za-z]{3,4}") || !toCurrency.matches("[A-Za-z]{3,4}")) {
            throw new TrackAmException("Invalid currency code.");
        }
        ExchangeRateService.ExchangeResult result = fxService.convert(BigDecimal.ONE, fromCurrency, toCurrency, null);
        if (result == null) throw new TrackAmException("Could not fetch exchange rate for " + fromCurrency + " → " + toCurrency);
        return repo.bulkConvertCurrency(userId, result.rate(), toCurrency);
    }
}
