-- TrackAm — Supabase PostgreSQL Schema
-- Run this in Supabase SQL Editor (Dashboard → SQL Editor → New Query)

-- ============================================================
-- 1. Enable extensions
-- ============================================================
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_cron; -- For automated cleanup jobs

-- ============================================================
-- 2. Business Profiles
-- Extends Supabase auth.users — same UUID as primary key
-- ============================================================
CREATE TABLE IF NOT EXISTS business_profiles (
    id             UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    business_name  TEXT,
    owner_name     TEXT,
    business_type  TEXT,
    currency       TEXT NOT NULL DEFAULT 'GHS',
    country        TEXT DEFAULT 'GH',
    monthly_budget DECIMAL(19, 4),  -- 19,4 for precision + future currencies
    onboarded      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- 3. Transactions
-- Core table — supports pgvector embeddings for RAG advisor
-- ============================================================
CREATE TABLE IF NOT EXISTS transactions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    type        TEXT NOT NULL CHECK (type IN ('income', 'expense')),
    amount      DECIMAL(19, 4) NOT NULL CHECK (amount > 0),
    currency    TEXT NOT NULL DEFAULT 'GHS',
    category    TEXT NOT NULL,
    description TEXT NOT NULL,
    vendor      TEXT,
    source      TEXT NOT NULL CHECK (source IN ('manual', 'ai-text', 'ai-image', 'ai-voice')),
    date        TIMESTAMPTZ NOT NULL,
    confidence  INTEGER CHECK (confidence BETWEEN 0 AND 100),
    -- Embedding generated from sanitized text (type + category + keywords only, NOT raw description)
    -- See TransactionService.sanitizeForEmbedding() — protects against embedding inversion attacks
    embedding   VECTOR(768),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- 4. Custom Categories — user-defined categories with AI keywords
-- ============================================================
CREATE TABLE IF NOT EXISTS custom_categories (
    id             TEXT NOT NULL,
    user_id        UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    name           TEXT NOT NULL,
    icon           TEXT NOT NULL,
    color          TEXT NOT NULL,
    bg_color       TEXT NOT NULL,
    dot_color      TEXT NOT NULL,
    type           TEXT NOT NULL CHECK (type IN ('income', 'expense')),
    keywords       TEXT[] DEFAULT '{}',
    sort_order     INTEGER DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, id)
);

-- ============================================================
-- 5. Goals — savings targets with progress tracking
-- ============================================================
CREATE TABLE IF NOT EXISTS goals (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    name           TEXT NOT NULL,
    target_amount  DECIMAL(19, 4) NOT NULL CHECK (target_amount > 0),
    current_amount DECIMAL(19, 4) NOT NULL DEFAULT 0 CHECK (current_amount >= 0),
    currency       TEXT NOT NULL DEFAULT 'GHS',
    icon           TEXT NOT NULL DEFAULT 'i-lucide-trending-up',
    color          TEXT NOT NULL DEFAULT 'text-emerald-600',
    bg_color       TEXT NOT NULL DEFAULT 'bg-emerald-100',
    dot_color      TEXT NOT NULL DEFAULT '#10b981',
    deadline       DATE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- 6. Chat Sessions — groups advisor conversations
-- ============================================================
CREATE TABLE IF NOT EXISTS chat_sessions (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    title      TEXT,  -- auto-generated from first user message
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- 5. Chat Messages (Advisor conversation history)
-- ============================================================
CREATE TABLE IF NOT EXISTS chat_messages (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    role       TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
    content    TEXT NOT NULL CHECK (char_length(content) <= 5000),
    metadata   JSONB DEFAULT '{}',  -- token count, model used, etc.
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- 6. Audit Logs (every AI call tracked)
-- NOTE: No FK to auth.users — intentional. Audit survives user deletion (compliance).
-- ============================================================
CREATE TABLE IF NOT EXISTS audit_logs (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL,   -- intentionally no FK
    operation     TEXT NOT NULL,   -- 'parse-text' | 'parse-image' | 'advisor'
    ai_provider   TEXT,            -- 'groq' | 'gemini-lite' | 'gemini-flash'
    model         TEXT,
    input_tokens  INTEGER,
    output_tokens INTEGER,
    cost_usd      DECIMAL(8, 6),
    latency_ms    INTEGER,
    success       BOOLEAN NOT NULL DEFAULT TRUE,
    error_message TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- 7. Indexes
-- ============================================================

-- Transactions: most common query pattern (user's transactions, newest first)
CREATE INDEX IF NOT EXISTS idx_transactions_user_date
    ON transactions(user_id, date DESC);

-- Goals: user's goals, oldest first (consistent ordering)
CREATE INDEX IF NOT EXISTS idx_goals_user
    ON goals(user_id, created_at ASC);

-- Transactions: covering index for dashboard SUM aggregates (index-only scan)
-- Enables: SELECT SUM(amount) WHERE user_id = ? AND type = ? without touching heap
CREATE INDEX IF NOT EXISTS idx_transactions_user_type_amount
    ON transactions(user_id, type) INCLUDE (amount);

-- Chat sessions: user's sessions, newest first
CREATE INDEX IF NOT EXISTS idx_chat_sessions_user
    ON chat_sessions(user_id, created_at DESC);

-- Chat messages: messages within a session (for conversation retrieval)
CREATE INDEX IF NOT EXISTS idx_chat_messages_session
    ON chat_messages(session_id, created_at);

-- Audit logs: user's recent AI calls (for rate limiting check)
CREATE INDEX IF NOT EXISTS idx_audit_logs_user
    ON audit_logs(user_id, created_at DESC);

-- HNSW vector index for cosine similarity search (RAG advisor)
-- Tuned for <100K vectors: m=16 (connections per node), ef_construction=200 (build quality)
-- Works immediately — no training data needed (unlike IVFFlat)
CREATE INDEX IF NOT EXISTS idx_transactions_embedding
    ON transactions USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 200);

-- ============================================================
-- 8. Row Level Security (users see only their own data)
-- ============================================================
ALTER TABLE transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE goals ENABLE ROW LEVEL SECURITY;
ALTER TABLE business_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE custom_categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE chat_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE chat_messages ENABLE ROW LEVEL SECURITY;
-- NOTE: audit_logs intentionally has NO RLS — admin-only access via backend

CREATE POLICY "transactions: users see own rows"
    ON transactions FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "goals: users see own rows"
    ON goals FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "profiles: users see own row"
    ON business_profiles FOR ALL
    USING (auth.uid() = id)
    WITH CHECK (auth.uid() = id);

CREATE POLICY "custom_categories: users see own rows"
    ON custom_categories FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "chat_sessions: users see own rows"
    ON chat_sessions FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "chat_messages: users see own rows"
    ON chat_messages FOR ALL
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

-- ============================================================
-- 9. Updated-at trigger (applied to tables with updated_at)
-- ============================================================
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_goals_updated_at
    BEFORE UPDATE ON goals
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER trg_transactions_updated_at
    BEFORE UPDATE ON transactions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER trg_profiles_updated_at
    BEFORE UPDATE ON business_profiles
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER trg_custom_categories_updated_at
    BEFORE UPDATE ON custom_categories
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER trg_chat_sessions_updated_at
    BEFORE UPDATE ON chat_sessions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- ============================================================
-- 10. Data Retention — automated cleanup via pg_cron
-- Financial compliance: keep audit logs 3 years
-- Privacy: auto-delete old chat sessions after 1 year of inactivity
-- ============================================================

-- Delete audit logs older than 3 years (daily at 3am UTC)
SELECT cron.schedule(
    'cleanup-old-audit-logs',
    '0 3 * * *',
    $$DELETE FROM audit_logs WHERE created_at < NOW() - INTERVAL '3 years'$$
);

-- Delete chat sessions not updated in over 1 year (daily at 3:30am UTC)
SELECT cron.schedule(
    'cleanup-old-chat-sessions',
    '30 3 * * *',
    $$DELETE FROM chat_sessions WHERE updated_at < NOW() - INTERVAL '1 year'$$
);
