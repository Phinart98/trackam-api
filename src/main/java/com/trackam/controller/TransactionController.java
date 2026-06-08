package com.trackam.controller;

import com.trackam.dto.TransactionRequest;
import com.trackam.exception.TrackAmException;
import com.trackam.model.BusinessProfile;
import com.trackam.model.Transaction;
import com.trackam.repository.BusinessProfileRepository;
import com.trackam.service.TransactionService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService txService;
    private final BusinessProfileRepository profileRepo;

    @GetMapping
    public ResponseEntity<List<Transaction>> getAll(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(txService.getAll(UUID.fromString(jwt.getSubject())));
    }

    @PostMapping
    public ResponseEntity<Transaction> create(
            @RequestBody @Valid TransactionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        ensureCurrencyMatchesProfile(userId, request.currency());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(txService.create(request, userId));
    }

    /**
     * Reject any incoming transaction whose currency doesn't match the user's profile.
     * Mixing currencies would produce nonsense balances on the dashboard (e.g. GHS-stored
     * amounts being summed alongside NGN-stored amounts as if they were the same scale).
     * The /api/transactions/convert-currency endpoint is the only path that should change
     * a user's stored currency, and it converts every transaction atomically.
     */
    private void ensureCurrencyMatchesProfile(UUID userId, String requestCurrency) {
        if (requestCurrency == null || requestCurrency.isBlank()) {
            throw new TrackAmException("Transaction currency is required.");
        }
        BusinessProfile profile = profileRepo.findById(userId).orElse(null);
        // No profile yet (very first transaction during onboarding) — let it through;
        // the profile will be created with this currency by the onboarding flow.
        if (profile == null || profile.getCurrency() == null) return;
        if (!profile.getCurrency().equalsIgnoreCase(requestCurrency)) {
            throw new TrackAmException(
                "Transaction currency " + requestCurrency
                    + " does not match profile currency " + profile.getCurrency()
                    + ". Convert your profile currency first."
            );
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        txService.delete(id, UUID.fromString(jwt.getSubject()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/convert-currency")
    public ResponseEntity<java.util.Map<String, Object>> convertCurrency(
            @RequestParam String from,
            @RequestParam String to,
            @AuthenticationPrincipal Jwt jwt) {
        int updated = txService.convertCurrency(UUID.fromString(jwt.getSubject()), from, to);
        return ResponseEntity.ok(java.util.Map.of("updated", updated, "from", from, "to", to));
    }
}
