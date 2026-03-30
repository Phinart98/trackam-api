package com.trackam.controller;

import com.trackam.dto.TransactionRequest;
import com.trackam.model.Transaction;
import com.trackam.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService txService;

    @GetMapping
    public ResponseEntity<Page<Transaction>> getAll(
            @AuthenticationPrincipal Jwt jwt,
            @PageableDefault(size = 50, sort = "date", direction = Sort.Direction.DESC) Pageable page) {
        return ResponseEntity.ok(txService.getAll(UUID.fromString(jwt.getSubject()), page));
    }

    @PostMapping
    public ResponseEntity<Transaction> create(
            @RequestBody @Valid TransactionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(txService.create(request, UUID.fromString(jwt.getSubject())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        txService.delete(id, UUID.fromString(jwt.getSubject()));
        return ResponseEntity.noContent().build();
    }
}
