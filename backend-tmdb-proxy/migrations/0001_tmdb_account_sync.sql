-- ============================================================================
-- KRAS TV — Cloudflare D1 Multi-User TMDb Account & Two-Way Sync Schema
-- Migration: 0001_tmdb_account_sync.sql
-- ============================================================================

-- 1. Pending Authorization Request Tokens (CSRF & Multi-User Callback Validation)
CREATE TABLE IF NOT EXISTS tmdb_auth_requests (
    request_token TEXT PRIMARY KEY,
    kras_user_id TEXT NOT NULL DEFAULT 'default',
    state_nonce TEXT NOT NULL DEFAULT '',
    redirect_to TEXT NOT NULL DEFAULT 'krastv://tmdb-auth',
    status TEXT NOT NULL DEFAULT 'PENDING',
    expires_at TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tmdb_auth_requests_user
    ON tmdb_auth_requests(kras_user_id, created_at DESC);

-- 2. Authenticated Multi-User TMDb Sessions (Isolated per KRAS TV user & TMDb account)
CREATE TABLE IF NOT EXISTS tmdb_user_sessions (
    session_id TEXT PRIMARY KEY,
    kras_user_id TEXT NOT NULL DEFAULT 'default',
    tmdb_account_id INTEGER NOT NULL,
    username TEXT NOT NULL,
    display_name TEXT NOT NULL DEFAULT '',
    avatar_url TEXT NOT NULL DEFAULT '',
    include_adult INTEGER NOT NULL DEFAULT 0,
    revoked INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    last_verified_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tmdb_user_sessions_kras_user
    ON tmdb_user_sessions(kras_user_id, revoked);

CREATE INDEX IF NOT EXISTS idx_tmdb_user_sessions_account
    ON tmdb_user_sessions(tmdb_account_id, revoked);

-- 3. Connected TMDb Accounts Cache & Sync Timestamps
CREATE TABLE IF NOT EXISTS tmdb_accounts (
    account_id INTEGER PRIMARY KEY,
    username TEXT NOT NULL,
    display_name TEXT NOT NULL DEFAULT '',
    avatar_url TEXT NOT NULL DEFAULT '',
    last_sync_at INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL DEFAULT 0
);

-- 4. Synchronized User Collections (Favorites, Watchlist, Ratings for Movies & TV Series)
CREATE TABLE IF NOT EXISTS tmdb_sync_items (
    account_id INTEGER NOT NULL,
    list_type TEXT NOT NULL,      -- 'FAVORITE' | 'WATCHLIST' | 'RATING'
    media_type TEXT NOT NULL,     -- 'MOVIE' | 'SERIES'
    tmdb_id INTEGER NOT NULL,
    title TEXT NOT NULL DEFAULT '',
    year TEXT NOT NULL DEFAULT '',
    poster_url TEXT NOT NULL DEFAULT '',
    backdrop_url TEXT NOT NULL DEFAULT '',
    overview TEXT NOT NULL DEFAULT '',
    tmdb_rating REAL NOT NULL DEFAULT 0,
    user_rating REAL NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, list_type, media_type, tmdb_id)
);

CREATE INDEX IF NOT EXISTS idx_tmdb_sync_items_account
    ON tmdb_sync_items(account_id, list_type);
