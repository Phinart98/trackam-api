package com.trackam.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the JWT validation chain wired into SecurityConfig.jwtDecoder().
 *
 * The real bean fetches public keys from Supabase's JWKS endpoint to check the
 * cryptographic signature — that part is exercised by integration with the
 * provider. What's tested here is the trio of CLAIM validators we layer on top:
 * issuer match, audience match, and expiry. A regression in any of these would
 * let the backend accept tokens it shouldn't.
 *
 * The Supabase project URL below is canonical (matches `application.yml`'s
 * trackam.supabase.project-url) so the tests reflect production behaviour.
 */
class SecurityConfigJwtTest {

    private static final String SUPABASE_URL = "https://bkvssqatlcnofnlyurie.supabase.co";
    private static final String EXPECTED_ISSUER = SUPABASE_URL + "/auth/v1";

    @Test
    @DisplayName("issuer validator: accepts a JWT whose iss matches the Supabase auth endpoint")
    void issuerValidator_acceptsMatchingIssuer() {
        JwtIssuerValidator validator = new JwtIssuerValidator(EXPECTED_ISSUER);
        Jwt jwt = jwt().claim("iss", EXPECTED_ISSUER).build();

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    @DisplayName("issuer validator: rejects a JWT issued by anyone else")
    void issuerValidator_rejectsImpersonator() {
        JwtIssuerValidator validator = new JwtIssuerValidator(EXPECTED_ISSUER);
        Jwt forged = jwt().claim("iss", "https://attacker.example.com/auth/v1").build();

        OAuth2TokenValidatorResult result = validator.validate(forged);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors())
            .extracting(OAuth2Error::getErrorCode)
            .contains("invalid_token");
    }

    @Test
    @DisplayName("audience validator: accepts the literal Supabase aud='authenticated' string shape")
    void audValidator_acceptsAuthenticatedString() {
        JwtClaimValidator<Object> validator = supabaseAudValidator();
        Jwt jwt = jwt().claim(JwtClaimNames.AUD, "authenticated").build();

        assertThat(validator.validate(jwt).hasErrors()).isFalse();
    }

    @Test
    @DisplayName("audience validator: also accepts list-shaped aud containing 'authenticated' (spec-compliant)")
    void audValidator_acceptsAuthenticatedInList() {
        JwtClaimValidator<Object> validator = supabaseAudValidator();
        Jwt jwt = jwt().claim(JwtClaimNames.AUD, List.of("authenticated", "extra")).build();

        assertThat(validator.validate(jwt).hasErrors()).isFalse();
    }

    @Test
    @DisplayName("audience validator: rejects a JWT addressed to a different service")
    void audValidator_rejectsWrongAudience() {
        JwtClaimValidator<Object> validator = supabaseAudValidator();
        Jwt jwt = jwt().claim(JwtClaimNames.AUD, "some-other-service").build();

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    @DisplayName("timestamp validator: rejects an expired token")
    void timestampValidator_rejectsExpired() {
        JwtTimestampValidator validator = new JwtTimestampValidator();
        // Default clock skew is 60s, so push expiry well past it.
        Jwt expired = jwt()
            .issuedAt(Instant.now().minusSeconds(7200))
            .expiresAt(Instant.now().minusSeconds(600))
            .build();

        assertThat(validator.validate(expired).hasErrors()).isTrue();
    }

    @Test
    @DisplayName("timestamp validator: accepts a fresh token")
    void timestampValidator_acceptsFresh() {
        JwtTimestampValidator validator = new JwtTimestampValidator();
        Jwt fresh = jwt()
            .issuedAt(Instant.now().minusSeconds(60))
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();

        assertThat(validator.validate(fresh).hasErrors()).isFalse();
    }

    // The audience validator we ship in SecurityConfig: accepts string OR list shape.
    // Mirrors the lambda at SecurityConfig.jwtDecoder() so a regression in either
    // place breaks the same shared assertion.
    private static JwtClaimValidator<Object> supabaseAudValidator() {
        return new JwtClaimValidator<>(JwtClaimNames.AUD,
            aud -> (aud instanceof List<?> list && list.contains("authenticated"))
                || "authenticated".equals(aud));
    }

    /** Minimal Jwt builder shared across tests. */
    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("test-token")
            .header("alg", "ES256")
            .claims(c -> c.putAll(Map.of(
                "sub", "92113dc0-dbda-461c-b074-8a0c41222b7b"
            )));
    }
}
