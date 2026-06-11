package com.trackam.controller;

import com.trackam.ai.guardrails.InputGuardrail;
import com.trackam.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Lightweight FX rate endpoint — returns the current exchange rate between two currencies.
 * Used by the frontend for live conversion previews (e.g. goal fund modal).
 */
@RestController
@RequestMapping("/api/fx")
@RequiredArgsConstructor
public class FxController {

    private final ExchangeRateService fxService;

    /**
     * GET /api/fx/rate?from=NGN&to=GHS
     * Returns: { "rate": 0.00245, "from": "NGN", "to": "GHS" }
     */
    @GetMapping("/rate")
    public ResponseEntity<Map<String, Object>> getRate(
            @RequestParam String from,
            @RequestParam String to) {

        InputGuardrail.validateCurrencyCode(from);
        InputGuardrail.validateCurrencyCode(to);

        if (from.equalsIgnoreCase(to)) {
            return ResponseEntity.ok(Map.of("rate", BigDecimal.ONE, "from", from.toUpperCase(), "to", to.toUpperCase()));
        }

        ExchangeRateService.ExchangeResult result = fxService.convert(BigDecimal.ONE, from, to, null);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Exchange rate unavailable for " + from.toUpperCase() + " → " + to.toUpperCase());
        }

        return ResponseEntity.ok(Map.of("rate", result.rate(), "from", from.toUpperCase(), "to", to.toUpperCase()));
    }
}
