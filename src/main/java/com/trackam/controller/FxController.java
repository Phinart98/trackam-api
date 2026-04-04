package com.trackam.controller;

import com.trackam.service.ExchangeRateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
            @RequestParam String to,
            @AuthenticationPrincipal Jwt jwt) {

        if (!from.matches("[A-Za-z]{3,4}") || !to.matches("[A-Za-z]{3,4}")) {
            return ResponseEntity.badRequest().build();
        }

        if (from.equalsIgnoreCase(to)) {
            return ResponseEntity.ok(Map.of("rate", BigDecimal.ONE, "from", from.toUpperCase(), "to", to.toUpperCase()));
        }

        ExchangeRateService.ExchangeResult result = fxService.convert(BigDecimal.ONE, from, to, null);
        if (result == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Exchange rate unavailable for " + from + " → " + to));
        }

        return ResponseEntity.ok(Map.of("rate", result.rate(), "from", from.toUpperCase(), "to", to.toUpperCase()));
    }
}
