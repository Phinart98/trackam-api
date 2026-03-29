# TrackAm API

Spring Boot backend for TrackAm — AI-powered financial tracker for Africa's informal economy.

## Stack

- Java 21 · Spring Boot 3 · Spring AI
- PostgreSQL via Supabase (pgvector for semantic search)
- Supabase Auth (JWT validation)
- AI: Groq (Llama 4), Gemini 2.5, Cerebras (fallback chain)

## Local Setup

**Prerequisites:** Java 21, Maven 3.9+

1. Copy the config template and fill in your values:
   ```bash
   cp application-local.yml.example application-local.yml
   ```

2. Run:
   ```bash
   mvn spring-boot:run "-Dspring-boot.run.profiles=local"
   ```

API runs at `http://localhost:8080`.

## Environment Variables

See `.env.example` for the full list of variables needed for production (Cloud Run).

For local development, use `application-local.yml` instead — Spring Boot reads it directly.

## Deployment

Deploy to Google Cloud Run. Build the JAR first:

```bash
mvn clean package -DskipTests
docker build -t gcr.io/YOUR_PROJECT/trackam-api .
```

Set all variables from `.env.example` as Cloud Run environment variables.

## Project Structure

```
src/main/java/com/trackam/
├── config/          # Security, CORS, AI provider config
├── controller/      # REST endpoints
├── service/         # Business logic + AI orchestration
├── repository/      # JPA repositories
├── model/           # JPA entities
├── dto/             # Request/response types
├── ai/              # Prompts + guardrails
└── exception/       # Error handling
src/main/resources/
├── application.yml          # Shared config (reads from env vars)
└── db/schema.sql            # Database schema (run once in Supabase)
```
