package com.trackam.service;

import com.trackam.dto.TransactionRequest;
import com.trackam.exception.TrackAmException;
import com.trackam.model.Transaction;
import com.trackam.repository.GoalRepository;
import com.trackam.repository.TransactionRepository;
import com.trackam.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    private final TransactionRepository repo;
    private final GoalRepository goalRepo;
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

    /**
     * Converts all user transactions and goals to a new currency.
     *
     * Uses the historical rate for each transaction's own date — more accurate than a single
     * "today's rate". ExchangeRateService caches historical rates, so N transactions across
     * D unique dates makes at most D API calls (often just 1-3 for recent history).
     * Goals use today's rate since they have no meaningful historical date.
     *
     * @return total number of updated records (transactions + goals)
     */
    @Transactional
    public int convertCurrency(UUID userId, String fromCurrency, String toCurrency) {
        if (fromCurrency == null || toCurrency == null || fromCurrency.equalsIgnoreCase(toCurrency)) return 0;
        if (!fromCurrency.matches("[A-Za-z]{3,4}") || !toCurrency.matches("[A-Za-z]{3,4}")) {
            throw new TrackAmException("Invalid currency code.");
        }

        // Per-transaction historical rate conversion
        List<Transaction> transactions = repo.findByUserIdOrderByDateDescCreatedAtDesc(userId);
        if (transactions.isEmpty()) {
            // No transactions — still convert goals using today's rate
            ExchangeRateService.ExchangeResult today = fxService.convert(BigDecimal.ONE, fromCurrency, toCurrency, null);
            if (today == null) throw new TrackAmException("Could not fetch exchange rate for " + fromCurrency + " → " + toCurrency);
            return goalRepo.bulkConvertCurrency(userId, today.rate(), toCurrency);
        }

        // Cache rates per date to avoid redundant API calls
        Map<String, BigDecimal> rateCache = new java.util.HashMap<>();
        int updatedTx = 0;
        for (Transaction tx : transactions) {
            String txDate = tx.getDate().atZone(ZoneOffset.UTC).toLocalDate().toString();
            BigDecimal rate = rateCache.computeIfAbsent(txDate, date -> {
                ExchangeRateService.ExchangeResult r = fxService.convert(BigDecimal.ONE, fromCurrency, toCurrency, date);
                // Fall back to today's rate if historical lookup fails
                if (r == null) r = fxService.convert(BigDecimal.ONE, fromCurrency, toCurrency, null);
                return r != null ? r.rate() : null;
            });
            if (rate == null) {
                throw new TrackAmException("Could not fetch exchange rate for " + fromCurrency + " → " + toCurrency);
            }
            tx.setAmount(tx.getAmount().multiply(rate).setScale(4, RoundingMode.HALF_UP));
            tx.setCurrency(toCurrency);
            updatedTx++;
        }
        repo.saveAll(transactions);

        // Goals: use today's rate (no meaningful date to anchor)
        // Must throw on failure — silently skipping leaves goals in old currency while transactions are converted
        ExchangeRateService.ExchangeResult todayRate = fxService.convert(BigDecimal.ONE, fromCurrency, toCurrency, null);
        if (todayRate == null) throw new TrackAmException("Could not fetch exchange rate for goal conversion: " + fromCurrency + " → " + toCurrency);
        goalRepo.bulkConvertCurrency(userId, todayRate.rate(), toCurrency);

        log.info("Converted {} transactions ({} unique dates) from {} to {} for user {}",
            updatedTx, rateCache.size(), fromCurrency, toCurrency, userId);
        return updatedTx;
    }
}
