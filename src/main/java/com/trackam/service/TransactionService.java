package com.trackam.service;

import com.trackam.dto.TransactionRequest;
import com.trackam.model.Transaction;
import com.trackam.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    private final TransactionRepository repo;
    private final EmbeddingService embeddingService;

    public Page<Transaction> getAll(String userId, Pageable page) {
        return repo.findByUserId(userId, page);
    }

    public Transaction create(TransactionRequest req, String userId) {
        Transaction tx = Transaction.builder()
            .userId(userId)
            .type(req.type())
            .amount(req.amount())
            .currency(req.currency())
            .category(req.category())
            .description(req.description())
            .vendor(req.vendor())
            .source(req.source())
            .date(LocalDateTime.parse(req.date(), DateTimeFormatter.ISO_DATE_TIME))
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

    public void delete(String id, String userId) {
        Transaction tx = repo.findById(id)
            .orElseThrow(() -> new RuntimeException("Transaction not found"));
        if (!tx.getUserId().equals(userId)) {
            throw new RuntimeException("Not authorized");
        }
        repo.delete(tx);
    }
}
