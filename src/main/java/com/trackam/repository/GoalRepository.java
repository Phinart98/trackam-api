package com.trackam.repository;

import com.trackam.model.Goal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GoalRepository extends JpaRepository<Goal, UUID> {
    List<Goal> findByUserIdOrderByCreatedAtAsc(UUID userId);
    void deleteByUserId(UUID userId);
}
