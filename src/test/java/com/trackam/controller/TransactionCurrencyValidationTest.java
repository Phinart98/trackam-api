package com.trackam.controller;

import com.trackam.dto.TransactionRequest;
import com.trackam.exception.TrackAmException;
import com.trackam.model.BusinessProfile;
import com.trackam.model.Transaction;
import com.trackam.repository.BusinessProfileRepository;
import com.trackam.service.TransactionService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the rule that a transaction's currency must match the user's profile currency.
 *
 * Without this guard, a frontend bug (or malicious client) could store a USD-amount
 * transaction in a NGN-profile account; the dashboard balance sums values across
 * currencies without conversion and produces nonsense (e.g. 7000 GHS-stored amounts
 * compared against 32000 NGN-converted amounts → a false negative balance). The
 * controller is the last line of defense — it must reject mismatches with HTTP 400.
 */
@ExtendWith(MockitoExtension.class)
class TransactionCurrencyValidationTest {

    private static final UUID USER_ID = UUID.fromString("92113dc0-dbda-461c-b074-8a0c41222b7b");

    @Mock TransactionService txService;
    @Mock BusinessProfileRepository profileRepo;

    @InjectMocks TransactionController controller;

    private Jwt userJwt;

    @BeforeEach
    void setUp() {
        userJwt = Jwt.withTokenValue("test")
            .header("alg", "ES256")
            .claim("sub", USER_ID.toString())
            .build();
    }

    @Test
    @DisplayName("matching currency: request goes through to the service and the saved transaction is returned")
    void matchingCurrencyIsPersisted() {
        when(profileRepo.findById(USER_ID)).thenReturn(Optional.of(profileWithCurrency("GHS")));
        Transaction saved = Transaction.builder().id(UUID.randomUUID()).userId(USER_ID).currency("GHS").build();
        when(txService.create(any(TransactionRequest.class), any(UUID.class))).thenReturn(saved);

        var response = controller.create(requestWithCurrency("GHS"), userJwt);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isSameAs(saved);
        verify(txService).create(any(TransactionRequest.class), any(UUID.class));
    }

    @Test
    @DisplayName("mismatched currency: TrackAmException raised → 400 from GlobalExceptionHandler; service NOT called")
    void mismatchedCurrencyRejected() {
        when(profileRepo.findById(USER_ID)).thenReturn(Optional.of(profileWithCurrency("GHS")));

        assertThatThrownBy(() -> controller.create(requestWithCurrency("USD"), userJwt))
            .isInstanceOf(TrackAmException.class)
            .hasMessageContaining("USD")
            .hasMessageContaining("GHS");

        verify(txService, never()).create(any(), any());
    }

    @Test
    @DisplayName("internal helper is case-insensitive (defense-in-depth)")
    void currencyMatchIsCaseInsensitive() {
        // Production HTTP requests can't actually reach this path with lowercase,
        // because TransactionRequest.currency carries @Pattern("[A-Z]{3,4}") and the
        // controller uses @Valid — Bean Validation rejects "ghs" with 400 first.
        // This test exists to lock in the equalsIgnoreCase behaviour of the helper
        // itself, in case future refactors loosen the upstream validation.
        when(profileRepo.findById(USER_ID)).thenReturn(Optional.of(profileWithCurrency("GHS")));
        when(txService.create(any(TransactionRequest.class), any(UUID.class)))
            .thenReturn(Transaction.builder().id(UUID.randomUUID()).build());

        assertThatCode(() -> controller.create(requestWithCurrency("ghs"), userJwt))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("no profile yet (onboarding): currency check is skipped; service is still called")
    void noProfileSkipsCheck() {
        when(profileRepo.findById(USER_ID)).thenReturn(Optional.empty());
        when(txService.create(any(TransactionRequest.class), any(UUID.class)))
            .thenReturn(Transaction.builder().id(UUID.randomUUID()).build());

        assertThatCode(() -> controller.create(requestWithCurrency("GHS"), userJwt))
            .doesNotThrowAnyException();
        verify(txService).create(any(), any());
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private static BusinessProfile profileWithCurrency(String currency) {
        BusinessProfile profile = new BusinessProfile();
        profile.setId(USER_ID);
        profile.setCurrency(currency);
        return profile;
    }

    private static TransactionRequest requestWithCurrency(String currency) {
        return new TransactionRequest(
            "expense",
            new BigDecimal("150.00"),
            currency,
            "market",
            "Bought rice",
            "Makola",
            "manual",
            "2026-06-04",
            null
        );
    }
}
