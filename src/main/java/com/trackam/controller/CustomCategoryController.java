package com.trackam.controller;

import com.trackam.dto.CustomCategoryRequest;
import com.trackam.model.CustomCategory;
import com.trackam.repository.CustomCategoryRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CustomCategoryController {

    private final CustomCategoryRepository categoryRepo;

    @GetMapping
    public ResponseEntity<List<CustomCategory>> getAll(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(categoryRepo.findByUserIdOrderBySortOrderAsc(jwt.getSubject()));
    }

    @PostMapping
    public ResponseEntity<CustomCategory> createOrUpdate(
            @RequestBody @Valid CustomCategoryRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        String userId = jwt.getSubject();

        Optional<CustomCategory> existing = categoryRepo.findByUserIdAndId(userId, request.getId());
        CustomCategory cat = existing.orElse(CustomCategory.builder()
                .userId(userId)
                .id(request.getId())
                .sortOrder(0)
                .build());

        cat.setName(request.getName());
        cat.setIcon(request.getIcon());
        cat.setColor(request.getColor());
        cat.setBgColor(request.getBgColor());
        cat.setDotColor(request.getDotColor());
        cat.setType(request.getType());
        cat.setKeywords(request.getKeywords());

        CustomCategory saved = categoryRepo.save(cat);
        return existing.isEmpty()
            ? ResponseEntity.status(HttpStatus.CREATED).body(saved)
            : ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(
            @PathVariable String id,
            @AuthenticationPrincipal Jwt jwt) {
        categoryRepo.deleteByUserIdAndId(jwt.getSubject(), id);
        return ResponseEntity.noContent().build();
    }
}
