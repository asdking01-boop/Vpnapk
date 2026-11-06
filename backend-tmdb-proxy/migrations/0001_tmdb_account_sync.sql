-- Cloudflare D1 Migration (Optional) for KRAS TV TMDb Account & Sync
-- Apply with: npx wrangler d1 migrations apply kras-tmdb-db --remote

CREATE TABLE IF NOT EXISTS tmdb_accounts (
  account_id INTEGER PRIMARY KEY,
  username TEXT NOT NULL,
  display_name TEXT NOT NULL DEFAULT '',
  avatar_url TEXT NOT NULL DEFAULT '',
  favorites_count INTEGER NOT NULL DEFAULT 0,
  watchlist_count INTEGER NOT NULL DEFAULT 0,
  ratings_count INTEGER NOT NULL DEFAULT 0,
  last_synced_at TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_tmdb_accounts_username ON tmdb_accounts(username);
