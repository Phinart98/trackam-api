# TrackAm API

**Spring Boot backend for [TrackAm](https://github.com/Phinart98/trackam)** — AI-powered financial tracker for Africa's informal economy. BeOrchid Africa Developers Hackathon 2026 (FinTech track).

→ Live: deployed on Google Cloud Run (`europe-west1`)
→ Frontend repo: [trackam](https://github.com/Phinart98/trackam) (Nuxt 4, deployed on Vercel)
→ Architecture: [trackam/docs/ARCHITECTURE.md](https://github.com/Phinart98/trackam/blob/master/docs/ARCHITECTURE.md)

---

## Stack

- **Java 21** · **Spring Boot 3.4** · **Spring AI 1.0.3**
- **PostgreSQL 17 + pgvector** via Supabase (transaction pooler, `prepareThreshold=0`)
- **Supabase Auth** — JWT validated via JWKS (ES256), no shared secret
- **AI providers**: Groq (Llama 4 Scout vision), Google Gemini Flash-Lite / Flash (text + tool-use), Cerebras gpt-oss-120b (fallback)
- **Container**: Docker multi-stage, non-root `app` user, JRE-alpine runtime

## How the AI layer works

The headline isn't "calls an LLM" — it's the **reliability layer** wrapped around every call. Built using Spring AI's structured-output, multimodal, and tool-calling APIs.

### Multi-provider fallback chain — `AiService.callWithFallback`

```
User → AiController → AiService.parseText(text, currency, userId)
                          │
                          ▼
       callWithFallback(userId, "parse-text", [
         Gemini Flash-Lite,      ← primary (lowest cost+latency)
         Groq Llama 4 Scout,     ← fallback 1 (different vendor)
         Gemini Flash,           ← fallback 2 (stronger reasoning)
         Cerebras gpt-oss-120b   ← fallback 3 (different vendor again)
       ])
            │
            ├── on schema-valid response → return
            ├── on exception → log attempt, try next provider
            └── all exhausted → throw TrackAmException → 503 to client
```

The chain survives any single vendor's outage. Each attempt is logged via `AuditService` async (operation, latency, success/fail) so we can see in production where providers fail.

### Vision parsing — `AiService.parseImage`

Receipts and MoMo screenshots go through `parseImage(MultipartFile file, ...)`:

1. `InputGuardrail.validateImage(file)` — **magic-byte check** (JPEG/PNG/GIF/WebP headers), size limit, rejects MIME spoofing
2. `file.getBytes()` read **once** into `ByteArrayResource` — reused across fallback attempts (no double-read bug)
3. `callWithFallback` chain: Groq Llama 4 Scout (vision) → Gemini Flash (vision)
4. Structured output → same `ParsedTransactionResponse` DTO as text parse

### Advisor — tool-use with safe scoping (`AiService.askAdvisor`)

Replaced the original RAG approach with Spring AI tool-calling — better for structured financial data.

- Gemini Flash receives the user's question + a small compact context (monthly totals, top categories, recent 10 txs)
- It can invoke `@Tool`-annotated methods on `AdvisorTools` (e.g. `totalSpendByCategory(daysBack)`, `transactionsInRange(...)`)
- **Critical:** each tool reads `userId` from Spring AI's `ToolContext`, not from the LLM's tool args — Gemini cannot hallucinate or substitute a different user's ID
- Tools run real SQL via `TransactionRepository`; result is fed back into the chat turn
- Conversation history persisted to `chat_sessions` + `chat_messages` for multi-turn

### Input + output guardrails

- `InputGuardrail` — rejects prompt injection patterns, validates magic bytes for images, enforces size limits, rejects non-financial queries on parse endpoints
- `OutputGuardrail` — clamps impossible amounts, rejects future-dated transactions, ensures returned `category` is in the user's actual category set (no hallucinated categories make it to the DB)

### Per-user rate limit — `SecurityConfig.AiRateLimitFilter`

Sliding-window per-`userId` rate limiter on `/api/ai/**`: 60 requests / minute. On limit hit, returns `429` with a `Retry-After` header. Uses `ConcurrentHashMap<userId, Deque<timestamp>>` with TOCTOU-safe eviction.

### Embeddings — native Gemini, not OpenAI-compat

`EmbeddingService` uses the **native Gemini `gemini-embedding-001`** endpoint, not the OpenAI-compat shim. The shim's `dimensions` parameter was unreliable; native returns deterministic 768-dim vectors that match the `pgvector(768)` column on `transactions.embedding`.

## Security

- **JWT via JWKS** — `NimbusJwtDecoder.withJwkSetUri(...)` + `JwsAlgorithm.ES256` + `JwtIssuerValidator` + audience validator (`"authenticated"`). No shared HMAC secret to leak or rotate.
- **Per-user scoping in every controller** — `userId = UUID.fromString(jwt.getSubject())` is the single source of truth; every repository query is scoped by it. No IDOR path.
- **Stateless sessions** — `SessionCreationPolicy.STATELESS`, no server-side session store.
- **Strict CORS** — production origin pinned via `FRONTEND_URL`; `http://localhost:*` permitted via `allowedOriginPatterns` for local dev. Verified empirically (legit localhost passes, `localhost.evil.com` returns 403).
- **CSRF disabled** — correct for stateless Bearer-token APIs (cookies not used for auth).
- **HSTS + frame-deny + CSP `default-src 'none'`** on backend responses.
- **GDPR endpoints** — `GET /api/user/export` and `DELETE /api/user/data` both scoped by JWT subject; delete cascades chat, categories, goals, transactions, profile.
- **Container hardening** — Dockerfile runtime stage runs as non-root `app:app`; JAR copied with `--chown=app:app`.

## Local setup

**Prerequisites:** Java 21, Maven 3.9+

1. Copy the local config template:
   ```bash
   cp application-local.yml.example application-local.yml
   ```
   Fill in Supabase DB credentials and AI provider keys.

2. Run:
   ```bash
   mvn spring-boot:run "-Dspring-boot.run.profiles=local"
   ```

API runs at `http://localhost:8080`. Hit `http://localhost:8080/actuator/health` to confirm.

## Environment variables

See `.env.example` for the full list used in production (Cloud Run).

Key ones:
- `SUPABASE_JDBC_URL` — must use port `6543` (transaction pooler) with `prepareThreshold=0`
- `SUPABASE_DB_USER` / `SUPABASE_DB_PASSWORD`
- `SUPABASE_URL` — used to build the JWKS URI (`/auth/v1/.well-known/jwks.json`)
- `FRONTEND_URL` — CORS allowed origin (production; localhost auto-allowed)
- `GROQ_API_KEY` / `GEMINI_API_KEY` / `CEREBRAS_API_KEY` — at least one must be valid

For local dev, use `application-local.yml` instead of env vars — Spring Boot reads it directly when the `local` profile is active.

## Deployment

Deploys to Google Cloud Run via Cloud Build trigger on push to `master`:

```
GitHub push → Cloud Build → Docker (multi-stage) → Artifact Registry
              → Cloud Run deploy (europe-west1, --cpu-boost, min-instances=0)
```

Image is tagged with the git SHA. Health check: `wget /actuator/health` every 30s (60s start-up grace).

## Project structure

```
src/main/java/com/trackam/
├── config/              # SecurityConfig (JWT/JWKS/CORS/rate-limit), AiConfig, AppProperties, BeansConfig
├── controller/          # REST endpoints: Ai, Transaction, Profile, Goal, CustomCategory,
│                        # Dashboard, Fx, UserData (GDPR), Metrics
├── service/             # AiService (fallback chain), TransactionService, GoalService, DashboardService,
│                        # AuditService (async logging), ExchangeRateService, EmbeddingService
├── repository/          # JPA repositories — every query scoped by userId
├── model/               # JPA entities (Transaction, BusinessProfile, Goal, CustomCategory,
│                        # ChatSession, ChatMessage, AuditLog) — all timestamps Instant
├── dto/                 # Request/response types
├── ai/
│   ├── prompts: TextParserPrompt, ImageParserPrompt, AdvisorPrompt, InsightPrompt, CategoryPromptHelper
│   ├── guardrails/      # InputGuardrail, OutputGuardrail
│   └── tools/           # AdvisorTools — @Tool methods scoped via ToolContext userId
└── exception/           # TrackAmException, GlobalExceptionHandler (sanitizes errors)
src/main/resources/
├── application.yml      # Shared config (env-driven)
└── db/schema.sql        # Postgres schema (run once in Supabase)
```

## Testing

```bash
mvn test
```

See `src/test/java/com/trackam/` for the test suite covering JWT validation, AI fallback chain, and input guardrails.
