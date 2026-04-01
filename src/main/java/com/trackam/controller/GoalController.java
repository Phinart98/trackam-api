package com.trackam.controller;

import com.trackam.dto.GoalRequest;
import com.trackam.model.Goal;
import com.trackam.service.GoalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/goals")
@RequiredArgsConstructor
public class GoalController {

    private final GoalService goalService;

    @GetMapping
    public ResponseEntity<List<Goal>> getAll(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(goalService.getAll(UUID.fromString(jwt.getSubject())));
    }

    @PostMapping
    public ResponseEntity<Goal> create(
            @RequestBody @Valid GoalRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(goalService.create(request, UUID.fromString(jwt.getSubject())));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Goal> update(
            @PathVariable UUID id,
            @RequestBody @Valid GoalRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(goalService.update(id, request, UUID.fromString(jwt.getSubject())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        goalService.delete(id, UUID.fromString(jwt.getSubject()));
        return ResponseEntity.noContent().build();
    }
}
