-- ============================================================
-- MicrOps AUTH database
-- Owns identity only: users, OAuth identities, one-time auth tokens.
-- Sessions and refresh tokens live in Redis (auth:sess:<sid>,
-- auth:user:<sub>:sessions), NOT in this database.
-- Roles, permissions, organizations, memberships live on the tenant
-- (Spring) side. No foreign keys out of this database; other services
-- store user_id as a plain UUID.
-- Run against an empty database: psql -d microps_auth -f auth_schema.sql
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- for gen_random_uuid()

-- ============================================================
-- SHARED TRIGGER FUNCTION
-- ============================================================
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = clock_timestamp();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================
-- USERS
-- One row per human. Identity only: no roles, no orgs, no admin flags.
-- password_hash is NULL for OAuth-only accounts.
-- ============================================================
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255),
    email_verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);
-- case-insensitive email uniqueness among live users
CREATE UNIQUE INDEX users_email_lower_idx
    ON users (lower(email)) WHERE deleted_at IS NULL;

CREATE TRIGGER update_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- USER IDENTITIES (OAuth)
-- One row per (provider, provider_user_id); a user can link several.
-- encrypted_token/iv: provider access token, only needed if you call
-- provider APIs as the user. If the GitHub App covers repo access,
-- these stay NULL and can be dropped later.
-- ============================================================
CREATE TABLE user_identities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider VARCHAR(50) NOT NULL CHECK (provider IN ('github','google')),
    provider_user_id VARCHAR(255) NOT NULL,
    encrypted_token TEXT,
    iv VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (provider, provider_user_id)
);
CREATE INDEX idx_user_identities_user_id ON user_identities(user_id);

CREATE TRIGGER update_user_identities_updated_at
    BEFORE UPDATE ON user_identities
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ============================================================
-- USER TOKENS (one-time: email verification, password reset)
-- Needed because password login and email_verified_at exist.
-- Hash-only storage, same rule as refresh tokens: never store raw.
-- Postgres, not Redis: low-volume, one-time, must survive a restart.
-- Issue flow: insert a new token, mark any older unused token of the
-- same user+type as used (or delete it) in the same transaction.
-- ============================================================
CREATE TABLE user_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(32) NOT NULL CHECK (type IN ('EMAIL_VERIFICATION','PASSWORD_RESET')),
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX user_tokens_token_hash_uk ON user_tokens(token_hash);
CREATE INDEX idx_user_tokens_user_id ON user_tokens(user_id, type);

CREATE TRIGGER update_user_tokens_updated_at
    BEFORE UPDATE ON user_tokens
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
