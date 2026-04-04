package com.trackam.repository;

import com.trackam.model.Goal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GoalRepository extends JpaRepository<Goal, UUID> {
    List<Goal> findByUserIdOrderByCreatedAtAsc(UUID userId);
    Optional<Goal> findByIdAndUserId(UUID id, UUID userId);
    void deleteByUserId(UUID userId);

    @Modifying
    @Transactional
    @Query("UPDATE Goal g SET g.targetAmount = g.targetAmount * :factor, g.currentAmount = g.currentAmount * :factor, g.currency = :newCurrency WHERE g.userId = :userId")
    int bulkConvertCurrency(@Param("userId") UUID userId,
                            @Param("factor") BigDecimal factor,
                            @Param("newCurrency") String newCurrency);
}
