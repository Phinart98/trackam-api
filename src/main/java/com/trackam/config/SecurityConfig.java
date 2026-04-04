package com.trackam.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final int AI_RATE_LIMIT = 60;          // max requests per window
    private static final long WINDOW_MS = 60_000L;         // 1-minute sliding window

    private final AppProperties props;

    public SecurityConfig(AppProperties props) {
        this.props = props;
    }

    /**
     * Sliding-window rate limiter for /api/ai/** endpoints.
     * Tracks per-user request timestamps in a ConcurrentLinkedDeque.
     * Returns 429 Too Many Requests with Retry-After header when limit exceeded.
     */
    static class AiRateLimitFilter extends OncePerRequestFilter {
        private final ConcurrentHashMap<String, Deque<Long>> userWindows = new ConcurrentHashMap<>();

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !request.getRequestURI().startsWith("/api/ai/");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
                chain.doFilter(request, response);
                return;
            }

            String userId = jwtAuth.getToken().getSubject();
            long now = System.currentTimeMillis();
            long windowStart = now - WINDOW_MS;

            // Per-user retry loop guards against a TOCTOU race: a concurrent thread can evict
            // and remove a deque from the map between our computeIfAbsent and synchronized(deque),
            // causing our timestamp to land on an orphaned deque and bypass the rate limit.
            // The identity check detects this and retries with the fresh map-resident deque.
            Deque<Long> timestamps;
            while (true) {
                timestamps = userWindows.computeIfAbsent(userId, k -> new ConcurrentLinkedDeque<>());
                synchronized (timestamps) {
                    if (userWindows.get(userId) != timestamps) continue; // stale deque — retry
                    boolean hadEntries = !timestamps.isEmpty();
                    while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                        timestamps.pollFirst();
                    }
                    // Evict only if we actually cleared stale entries and the deque is now empty.
                    // DO NOT evict a freshly-created empty deque — that causes an infinite loop.
                    if (hadEntries && timestamps.isEmpty()) {
                        userWindows.remove(userId, timestamps);
                        continue;
                    }
                    if (timestamps.size() >= AI_RATE_LIMIT) {
                        long oldestTs = timestamps.peekFirst() != null ? timestamps.peekFirst() : now;
                        long retryAfterSeconds = Math.max(1, (oldestTs + WINDOW_MS - now) / 1000);
                        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
                        response.setContentType("application/json");
                        response.getWriter().write("{\"error\":\"Rate limit exceeded. Please wait " + retryAfterSeconds + " seconds.\"}");
                        return;
                    }
                    timestamps.addLast(now);
                    break;
                }
            }

            chain.doFilter(request, response);
        }
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'"))
                .frameOptions(fo -> fo.deny())
                .contentTypeOptions(cto -> {})
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.decoder(jwtDecoder()))
            )
            .addFilterAfter(new AiRateLimitFilter(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        String projectUrl = props.getSupabaseProjectUrl();
        if (projectUrl == null || projectUrl.isBlank()) {
            throw new IllegalStateException(
                "SUPABASE_URL must be set — required to build the JWKS endpoint for JWT verification");
        }

        // Supabase uses ES256 (asymmetric ECDSA) — verify via public keys from JWKS,
        // not a shared secret. Spring fetches and caches the key set automatically.
        String jwksUri = projectUrl + "/auth/v1/.well-known/jwks.json";
        // Must specify ES256 — withJwkSetUri() defaults to RS256 only and silently rejects ES256 tokens
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri)
            .jwsAlgorithm(SignatureAlgorithm.ES256)
            .build();

        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator());
        // Supabase sends aud as a plain string "authenticated", not an array.
        // The JWT spec allows both shapes, so we handle both to avoid ClassCastException.
        validators.add(new JwtClaimValidator<Object>(JwtClaimNames.AUD,
            aud -> (aud instanceof List<?> list && list.contains("authenticated"))
                || "authenticated".equals(aud)));
        validators.add(new JwtIssuerValidator(projectUrl + "/auth/v1"));

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(props.getCorsAllowedOrigins().split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
