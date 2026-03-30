package com.trackam.repository;

import com.trackam.model.CustomCategory;
import com.trackam.model.CustomCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomCategoryRepository extends JpaRepository<CustomCategory, CustomCategoryId> {

    List<CustomCategory> findByUserIdOrderBySortOrderAsc(UUID userId);

    Optional<CustomCategory> findByUserIdAndId(UUID userId, String id);

    @Modifying
    @Transactional
    void deleteByUserIdAndId(UUID userId, String id);
}
