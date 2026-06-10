package com.trackam.repository;

import com.trackam.model.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdAndUserId(UUID id, UUID userId);

    List<Transaction> findByUserIdOrderByDateDescCreatedAtDesc(UUID userId);

    Page<Transaction> findByUserId(UUID userId, Pageable pageable);

    List<Transaction> findByUserIdAndDateBetweenOrderByDateDesc(
        UUID userId, Instant from, Instant to);

    @Query("SELECT t FROM Transaction t WHERE t.userId = :userId ORDER BY t.date DESC")
    List<Transaction> findRecentTransactions(@Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.userId = :userId AND t.type = :type")
    BigDecimal sumByUserIdAndType(@Param("userId") UUID userId, @Param("type") String type);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.userId = :userId")
    long countByUserId(@Param("userId") UUID userId);

    boolean existsByUserIdAndCurrencyNotIgnoreCase(UUID userId, String currency);

    @Query(value = "SELECT t.currency FROM transactions t WHERE t.user_id = :userId ORDER BY t.date DESC LIMIT 1", nativeQuery = true)
    Optional<String> findLatestCurrencyByUserId(@Param("userId") UUID userId);

    @Query("SELECT t.category, SUM(t.amount) FROM Transaction t WHERE t.userId = :userId AND t.type = 'expense' GROUP BY t.category ORDER BY SUM(t.amount) DESC")
    List<Object[]> getCategoryTotals(@Param("userId") UUID userId);

    @Query("SELECT t FROM Transaction t WHERE t.userId = :userId AND t.type = 'expense' ORDER BY t.amount DESC")
    List<Transaction> findTopExpenses(@Param("userId") UUID userId, Pageable pageable);

    // Keyword search on description (case-insensitive). Caller must escape % and _ with backslash.
    @Query("SELECT t FROM Transaction t WHERE t.userId = :userId AND LOWER(t.description) LIKE LOWER(CONCAT('%', :query, '%')) ESCAPE '\\' ORDER BY t.date DESC")
    List<Transaction> searchByDescription(@Param("userId") UUID userId, @Param("query") String query);

    @Query("SELECT t.category, SUM(t.amount) FROM Transaction t WHERE t.userId = :userId AND t.type = 'income' GROUP BY t.category ORDER BY SUM(t.amount) DESC")
    List<Object[]> getIncomeCategoryTotals(@Param("userId") UUID userId);

    @Query(value = """
        SELECT vendor, category, COUNT(*) as tx_count, SUM(amount) as total_amount
        FROM transactions
        WHERE user_id = :userId AND vendor IS NOT NULL AND vendor != ''
        GROUP BY vendor, category
        HAVING COUNT(*) >= 3
        ORDER BY tx_count DESC
        """, nativeQuery = true)
    List<Object[]> findRecurringVendors(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query("DELETE FROM Transaction t WHERE t.userId = :userId")
    void deleteByUserId(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    @Query("UPDATE Transaction t SET t.amount = t.amount * :factor, t.currency = :newCurrency WHERE t.userId = :userId")
    int bulkConvertCurrency(@Param("userId") UUID userId,
                            @Param("factor") java.math.BigDecimal factor,
                            @Param("newCurrency") String newCurrency);

    // queryEmbedding must be passed as pgvector text format "[0.1,0.2,...]"
    // CAST avoids JDBC having no float[] → pgvector type handler for bound parameters
    @Query(value = """
        SELECT * FROM transactions
        WHERE user_id = :userId AND embedding IS NOT NULL
        ORDER BY embedding <=> CAST(:queryEmbedding AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<Transaction> findSimilar(
        @Param("userId") UUID userId,
        @Param("queryEmbedding") String queryEmbedding,
        @Param("limit") int limit);
}
