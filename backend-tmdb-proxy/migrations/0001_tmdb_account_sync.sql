-- ============================================================================
-- KRAS TV — Cloudflare D1 Database Migration for Multi-User TMDb Account Sync
-- Migration: 0001_tmdb_account_sync.sql
-- ============================================================================

-- 1. Pending browser authorization requests (15-minute TTL)
CREATE TABLE IF NOT EXISTS tmdb_auth_requests (
    request_token TEXT PRIMARY KEY,
    auth_session_id TEXT NOT NULL,
    installation_id TEXT NOT NULL DEFAULT '',
    kras_user_id TEXT NOT NULL,
    state_nonce TEXT NOT NULL DEFAULT '',
    redirect_uri TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending', -- 'pending', 'approved', 'denied', 'expired', 'consumed'
    approved INTEGER NOT NULL DEFAULT 0,
    expires_at TEXT NOT NULL,
    expires_at_ms INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tmdb_auth_requests_user
    ON tmdb_auth_requests(kras_user_id, installation_id, created_at DESC);

-- 2. Single-use authorization codes issued after TMDb browser callback (5-minute TTL)
--    Used by Android App Link handoff so TMDb session tokens never appear in URLs or APK.
CREATE TABLE IF NOT EXISTS tmdb_auth_codes (
    auth_code TEXT PRIMARY KEY,
    client_token TEXT NOT NULL,
    tmdb_session_id TEXT NOT NULL,
    tmdb_account_id INTEGER NOT NULL,
    username TEXT NOT NULL,
    display_name TEXT NOT NULL DEFAULT '',
    avatar_url TEXT NOT NULL DEFAULT '',
    installation_id TEXT NOT NULL DEFAULT '',
    kras_user_id TEXT NOT NULL,
    state_nonce TEXT NOT NULL DEFAULT '',
    consumed INTEGER NOT NULL DEFAULT 0,
    expires_at_ms INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tmdb_auth_codes_lookup
    ON tmdb_auth_codes(kras_user_id, installation_id, consumed);

-- 3. Multi-user TMDb sessions bound to KRAS TV installation + user profile
--    Maps opaque `client_token` -> server-side `tmdb_session_id`.
CREATE TABLE IF NOT EXISTS tmdb_user_sessions (
    session_id TEXT PRIMARY KEY,          -- Opaque KRAS client_token
    tmdb_session_id TEXT NOT NULL DEFAULT '', -- Official TMDb v3 session_id (kept server-side only)
    installation_id TEXT NOT NULL DEFAULT '',
    kras_user_id TEXT NOT NULL,
    tmdb_account_id INTEGER NOT NULL,
    username TEXT NOT NULL,
    display_name TEXT NOT NULL DEFAULT '',
    avatar_url TEXT,
    include_adult INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    last_sync_at INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_tmdb_user_sessions_kras_user
    ON tmdb_user_sessions(kras_user_id, installation_id);

CREATE INDEX IF NOT EXISTS idx_tmdb_user_sessions_account_id
    ON tmdb_user_sessions(tmdb_account_id);

-- 4. TMDb account profiles
CREATE TABLE IF NOT EXISTS tmdb_accounts (
    account_id INTEGER PRIMARY KEY,
    username TEXT NOT NULL,
    name TEXT NOT NULL DEFAULT '',
    avatar_url TEXT,
    session_id TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    last_sync_at INTEGER NOT NULL DEFAULT 0
);

-- 5. Cached synchronized Favorites, Watchlist, and Ratings per TMDb account
CREATE TABLE IF NOT EXISTS tmdb_sync_items (
    account_id INTEGER NOT NULL,
    tmdb_id INTEGER NOT NULL,
    media_type TEXT NOT NULL,
    title TEXT NOT NULL DEFAULT '',
    poster_url TEXT,
    backdrop_url TEXT,
    release_year TEXT,
    tmdb_rating REAL,
    user_rating REAL,
    is_favorite INTEGER NOT NULL DEFAULT 0,
    in_watchlist INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (account_id, tmdb_id, media_type)
);

CREATE INDEX IF NOT EXISTS idx_tmdb_sync_items_account
    ON tmdb_sync_items(account_id, updated_at DESC);
