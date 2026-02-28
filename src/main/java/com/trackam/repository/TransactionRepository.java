package com.trackam.repository;

import com.trackam.model.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, String> {

    List<Transaction> findByUserIdOrderByDateDesc(String userId);

    Page<Transaction> findByUserId(String userId, Pageable pageable);

    List<Transaction> findByUserIdAndDateBetweenOrderByDateDesc(
        String userId, LocalDateTime from, LocalDateTime to);

    // SQL aggregates — no full table scan into memory
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t WHERE t.userId = :userId AND t.type = :type")
    BigDecimal sumByUserIdAndType(@Param("userId") String userId, @Param("type") String type);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.userId = :userId")
    long countByUserId(@Param("userId") String userId);

    @Query("SELECT t.currency FROM Transaction t WHERE t.userId = :userId ORDER BY t.date DESC")
    List<String> findLatestCurrencyByUserId(@Param("userId") String userId);

    @Query("SELECT t.category, SUM(t.amount) FROM Transaction t WHERE t.userId = :userId AND t.type = 'expense' GROUP BY t.category ORDER BY SUM(t.amount) DESC")
    List<Object[]> getCategoryTotals(@Param("userId") String userId);

    // Hibernate-vector handles float[] ↔ vector type mapping natively
    @Query(value = """
        SELECT * FROM transactions
        WHERE user_id = :userId AND embedding IS NOT NULL
        ORDER BY embedding <=> :queryEmbedding
        LIMIT :limit
        """, nativeQuery = true)
    List<Transaction> findSimilar(
        @Param("userId") String userId,
        @Param("queryEmbedding") float[] queryEmbedding,
        @Param("limit") int limit);
}
