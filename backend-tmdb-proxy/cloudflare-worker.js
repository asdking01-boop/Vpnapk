/**
 * KRAS TV — Official Cloudflare Worker Backend for TMDb Metadata & Multi-User Account Sync
 * Worker URL: https://kras-tmdb-proxy.asdking01.workers.dev
 *
 * Required Cloudflare Worker Secret:
 *   - TMDB_API_KEY : Your TMDb v3 API Key (or v4 Read Access Token)
 *
 * Optional Cloudflare D1 Database Binding:
 *   - DB           : Cloudflare D1 Database for multi-user session state & sync caching
 *                    (Automatically initializes tables on first request if bound;
 *                     also works without D1 by verifying directly against TMDb v3 APIs)
 */

const TMDB_BASE_URL = "https://api.themoviedb.org/3";
const TMDB_IMAGE_W500 = "https://image.tmdb.org/t/p/w500";
const TMDB_IMAGE_ORIGINAL = "https://image.tmdb.org/t/p/original";

// Lightweight per-isolate rate limiter (protects auth & sync endpoints from abuse)
const rateLimitBuckets = new Map();
const RATE_LIMIT_WINDOW_MS = 60_000;
const RATE_LIMIT_MAX_REQUESTS = 120;

let d1SchemaInitialized = false;

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization, X-TMDb-Session, X-KRAS-User-Id, X-KRAS-Client",
  "Content-Type": "application/json; charset=utf-8"
};

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: CORS_HEADERS
  });
}

function htmlResponse(html, status = 200) {
  return new Response(html, {
    status,
    headers: {
      "Access-Control-Allow-Origin": "*",
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-store, no-cache, must-revalidate"
    }
  });
}

function checkRateLimit(request) {
  const ip = request.headers.get("CF-Connecting-IP") || "anon";
  const now = Date.now();
  let bucket = rateLimitBuckets.get(ip);
  if (!bucket || now - bucket.windowStart > RATE_LIMIT_WINDOW_MS) {
    bucket = { windowStart: now, count: 0 };
  }
  bucket.count += 1;
  rateLimitBuckets.set(ip, bucket);
  if (rateLimitBuckets.size > 2000) {
    for (const [k, v] of rateLimitBuckets.entries()) {
      if (now - v.windowStart > RATE_LIMIT_WINDOW_MS) rateLimitBuckets.delete(k);
    }
  }
  return bucket.count <= RATE_LIMIT_MAX_REQUESTS;
}

async function ensureDatabaseSchema(env) {
  if (!env || !env.DB || d1SchemaInitialized) return;
  try {
    await env.DB.batch([
      env.DB.prepare(
        `CREATE TABLE IF NOT EXISTS tmdb_auth_requests (
          request_token TEXT PRIMARY KEY,
          kras_user_id TEXT NOT NULL DEFAULT 'default',
          state_nonce TEXT NOT NULL DEFAULT '',
          redirect_to TEXT NOT NULL DEFAULT 'krastv://tmdb-auth',
          status TEXT NOT NULL DEFAULT 'PENDING',
          expires_at TEXT NOT NULL DEFAULT '',
          created_at INTEGER NOT NULL,
          updated_at INTEGER NOT NULL
        )`
      ),
      env.DB.prepare(
        `CREATE TABLE IF NOT EXISTS tmdb_user_sessions (
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
        )`
      ),
      env.DB.prepare(
        `CREATE TABLE IF NOT EXISTS tmdb_accounts (
          account_id INTEGER PRIMARY KEY,
          username TEXT NOT NULL,
          display_name TEXT NOT NULL DEFAULT '',
          avatar_url TEXT NOT NULL DEFAULT '',
          last_sync_at INTEGER NOT NULL DEFAULT 0,
          updated_at INTEGER NOT NULL DEFAULT 0
        )`
      ),
      env.DB.prepare(
        `CREATE TABLE IF NOT EXISTS tmdb_sync_items (
          account_id INTEGER NOT NULL,
          list_type TEXT NOT NULL,
          media_type TEXT NOT NULL,
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
        )`
      )
    ]);
    d1SchemaInitialized = true;
  } catch (_) {
    // Non-fatal if D1 is not bound or read-only
  }
}

function buildTmdbUrl(path, params, apiKey) {
  const url = new URL(`${TMDB_BASE_URL}${path}`);
  const trimmedKey = (apiKey || "").trim();
  const isBearer = trimmedKey.startsWith("eyJ") && trimmedKey.length > 40;
  if (!isBearer) {
    url.searchParams.set("api_key", trimmedKey);
  }
  for (const [k, v] of Object.entries(params || {})) {
    if (v !== undefined && v !== null && String(v).trim() !== "") {
      url.searchParams.set(k, String(v));
    }
  }
  return { url: url.toString(), isBearer, token: trimmedKey };
}

async function tmdbFetch(path, params, apiKey, options = {}) {
  const { url, isBearer, token } = buildTmdbUrl(path, params, apiKey);
  const headers = {
    Accept: "application/json",
    ...(options.headers || {})
  };
  if (isBearer) {
    headers.Authorization = `Bearer ${token}`;
  }
  if (options.body && !headers["Content-Type"]) {
    headers["Content-Type"] = "application/json;charset=utf-8";
  }

  const response = await fetch(url, {
    method: options.method || "GET",
    headers,
    body: options.body ? (typeof options.body === "string" ? options.body : JSON.stringify(options.body)) : undefined
  });

  const data = await response.json().catch(() => ({}));
  return {
    ok: response.ok,
    status: response.status,
    data
  };
}

function resolveAvatarUrl(accountData) {
  const tmdbAvatarPath = accountData?.avatar?.tmdb?.avatar_path;
  if (tmdbAvatarPath) {
    return `${TMDB_IMAGE_W500}${tmdbAvatarPath}`;
  }
  const gravatarHash = accountData?.avatar?.gravatar?.hash;
  if (gravatarHash) {
    return `https://secure.gravatar.com/avatar/${gravatarHash}.jpg?s=200&d=identicon`;
  }
  return "";
}

function normalizeAccountObject(accountData) {
  return {
    id: Number(accountData?.id || 0),
    username: String(accountData?.username || ""),
    name: String(accountData?.name || accountData?.username || ""),
    avatar_url: resolveAvatarUrl(accountData),
    include_adult: Boolean(accountData?.include_adult)
  };
}

function normalizeMediaType(rawType) {
  const upper = String(rawType || "").toUpperCase();
  return upper === "TV" || upper === "SERIES" ? "SERIES" : "MOVIE";
}

function mapTmdbItemToSyncItem(item, mediaType, extra = {}) {
  const normalizedType = normalizeMediaType(mediaType);
  const isSeries = normalizedType === "SERIES";
  const rawDate = isSeries ? (item.first_air_date || "") : (item.release_date || "");
  return {
    tmdb_id: Number(item.id || item.tmdb_id || 0),
    media_type: normalizedType,
    title: String(isSeries ? (item.name || item.original_name || item.title || "") : (item.title || item.original_title || item.name || "")),
    year: rawDate ? String(rawDate).slice(0, 4) : String(item.year || ""),
    poster_url: item.poster_path ? `${TMDB_IMAGE_W500}${item.poster_path}` : String(item.poster_url || ""),
    backdrop_url: item.backdrop_path ? `${TMDB_IMAGE_ORIGINAL}${item.backdrop_path}` : String(item.backdrop_url || ""),
    overview: String(item.overview || ""),
    rating: Number(item.vote_average || item.rating || 0),
    user_rating: Number(item.rating || extra.user_rating || 0),
    favorite: Boolean(extra.favorite),
    watchlist: Boolean(extra.watchlist),
    updated_at: Number(extra.updated_at || Date.now())
  };
}

function deduplicateSyncItems(items) {
  const byKey = new Map();
  for (const item of items || []) {
    const tmdbId = Number(item?.tmdb_id || item?.tmdbId || 0);
    if (tmdbId <= 0) continue;
    const mediaType = normalizeMediaType(item?.media_type || item?.mediaType);
    const key = `${mediaType}:${tmdbId}`;
    const existing = byKey.get(key);
    if (!existing) {
      byKey.set(key, item);
    } else {
      byKey.set(key, {
        ...existing,
        ...item,
        title: item.title || existing.title,
        poster_url: item.poster_url || existing.poster_url,
        backdrop_url: item.backdrop_url || existing.backdrop_url,
        overview: item.overview || existing.overview,
        user_rating: item.user_rating > 0 ? item.user_rating : existing.user_rating,
        updated_at: Math.max(Number(item.updated_at || 0), Number(existing.updated_at || 0))
      });
    }
  }
  return Array.from(byKey.values());
}

async function fetchPaginatedTmdbList(path, sessionId, language, apiKey, maxPages = 5) {
  const results = [];
  for (let page = 1; page <= maxPages; page++) {
    const res = await tmdbFetch(
      path,
      { session_id: sessionId, language: language || "en-US", sort_by: "created_at.desc", page },
      apiKey
    );
    if (!res.ok || !Array.isArray(res.data?.results)) {
      break;
    }
    results.push(...res.data.results);
    const totalPages = Number(res.data?.total_pages || 1);
    if (page >= totalPages) {
      break;
    }
  }
  return results;
}

/**
 * Validates that `sessionId` is active on TMDb and belongs to the requested `accountId`
 * and (if bound in D1) the requesting `krasUserId`. Prevents cross-user data access.
 */
async function validateSessionAndOwnership(sessionId, requestedAccountId, krasUserId, apiKey, env) {
  const cleanSession = String(sessionId || "").trim();
  if (!cleanSession) {
    return {
      ok: false,
      status: 400,
      error: "missing_session_id",
      message: "Missing TMDb session_id."
    };
  }

  await ensureDatabaseSchema(env);

  // Check D1 session binding if available
  if (env && env.DB) {
    try {
      const row = await env.DB.prepare(
        `SELECT session_id, kras_user_id, tmdb_account_id, username, display_name, avatar_url, revoked
         FROM tmdb_user_sessions
         WHERE session_id = ?1`
      )
        .bind(cleanSession)
        .first();

      if (row) {
        if (Number(row.revoked) === 1) {
          return {
            ok: false,
            status: 401,
            error: "session_revoked",
            message: "This TMDb session has been disconnected. Please reconnect your TMDb account."
          };
        }
        if (
          krasUserId &&
          row.kras_user_id &&
          row.kras_user_id !== "default" &&
          row.kras_user_id !== krasUserId
        ) {
          return {
            ok: false,
            status: 403,
            error: "cross_user_session_forbidden",
            message: "Access denied: TMDb session belongs to a different KRAS TV user profile."
          };
        }
        if (
          Number(requestedAccountId) > 0 &&
          Number(row.tmdb_account_id) > 0 &&
          Number(requestedAccountId) !== Number(row.tmdb_account_id)
        ) {
          return {
            ok: false,
            status: 403,
            error: "cross_account_access_forbidden",
            message: "Access denied: session does not match the requested TMDb account_id."
          };
        }
      }
    } catch (_) {
      // Fall through to live TMDb verification
    }
  }

  // Verify live session against TMDb /account
  const accountRes = await tmdbFetch("/account", { session_id: cleanSession }, apiKey);
  if (!accountRes.ok || !accountRes.data?.id) {
    return {
      ok: false,
      status: 401,
      error: "invalid_session",
      message: accountRes.data?.status_message || "Invalid or expired TMDb session."
    };
  }

  const account = normalizeAccountObject(accountRes.data);
  if (Number(requestedAccountId) > 0 && Number(requestedAccountId) !== account.id) {
    return {
      ok: false,
      status: 403,
      error: "cross_account_access_forbidden",
      message: "Access denied: TMDb session does not own the requested account_id."
    };
  }

  return {
    ok: true,
    status: 200,
    account
  };
}

async function saveSessionToD1(env, sessionId, krasUserId, account) {
  if (!env || !env.DB || !account?.id) return;
  await ensureDatabaseSchema(env);
  const now = Date.now();
  try {
    await env.DB.batch([
      env.DB.prepare(
        `INSERT INTO tmdb_user_sessions (
           session_id, kras_user_id, tmdb_account_id, username, display_name, avatar_url, include_adult, revoked, created_at, last_verified_at
         ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 0, ?8, ?8)
         ON CONFLICT(session_id) DO UPDATE SET
           kras_user_id = excluded.kras_user_id,
           tmdb_account_id = excluded.tmdb_account_id,
           username = excluded.username,
           display_name = excluded.display_name,
           avatar_url = excluded.avatar_url,
           revoked = 0,
           last_verified_at = excluded.last_verified_at`
      ).bind(
        sessionId,
        krasUserId || "default",
        account.id,
        account.username,
        account.name || account.username,
        account.avatar_url || "",
        account.include_adult ? 1 : 0,
        now
      ),
      env.DB.prepare(
        `INSERT INTO tmdb_accounts (account_id, username, display_name, avatar_url, last_sync_at, updated_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?5)
         ON CONFLICT(account_id) DO UPDATE SET
           username = excluded.username,
           display_name = excluded.display_name,
           avatar_url = excluded.avatar_url,
           updated_at = excluded.updated_at`
      ).bind(account.id, account.username, account.name || account.username, account.avatar_url || "", now)
    ]);
  } catch (_) {
    // Non-fatal
  }
}

async function saveSyncSnapshotToD1(env, account, favorites, watchlist, ratings) {
  if (!env || !env.DB || !account?.id) return;
  await ensureDatabaseSchema(env);
  const now = Date.now();
  try {
    const stmts = [
      env.DB.prepare(
        `INSERT INTO tmdb_accounts (account_id, username, display_name, avatar_url, last_sync_at, updated_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?5)
         ON CONFLICT(account_id) DO UPDATE SET
           username = excluded.username,
           display_name = excluded.display_name,
           avatar_url = excluded.avatar_url,
           last_sync_at = excluded.last_sync_at,
           updated_at = excluded.updated_at`
      ).bind(account.id, account.username, account.name || account.username, account.avatar_url || "", now),
      env.DB.prepare(`DELETE FROM tmdb_sync_items WHERE account_id = ?1`).bind(account.id)
    ];

    const appendItems = (list, listType) => {
      for (const item of list.slice(0, 150)) {
        stmts.push(
          env.DB.prepare(
            `INSERT OR REPLACE INTO tmdb_sync_items (
               account_id, list_type, media_type, tmdb_id, title, year, poster_url, backdrop_url, overview, tmdb_rating, user_rating, updated_at
             ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)`
          ).bind(
            account.id,
            listType,
            item.media_type,
            item.tmdb_id,
            item.title || "",
            item.year || "",
            item.poster_url || "",
            item.backdrop_url || "",
            item.overview || "",
            Number(item.rating || 0),
            Number(item.user_rating || 0),
            now
          )
        );
      }
    };

    appendItems(favorites, "FAVORITE");
    appendItems(watchlist, "WATCHLIST");
    appendItems(ratings, "RATING");

    await env.DB.batch(stmts);
  } catch (_) {
    // Non-fatal if D1 table is not migrated yet
  }
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    if (!checkRateLimit(request)) {
      return jsonResponse(
        {
          ok: false,
          error: "rate_limit_exceeded",
          message: "Too many TMDb requests. Please wait a moment and try again."
        },
        429
      );
    }

    const url = new URL(request.url);
    const pathname = url.pathname.replace(/\/+$/, "") || "/";
    const apiKey = env.TMDB_API_KEY;
    const headerKrasUserId = String(request.headers.get("X-KRAS-User-Id") || "").trim();

    // -------------------------------------------------------------------------
    // 1. Health & Connectivity Test Endpoints
    // -------------------------------------------------------------------------
    if (pathname === "/health" || pathname === "/api/tmdb/test") {
      if (!apiKey || String(apiKey).trim() === "") {
        return jsonResponse(
          {
            ok: false,
            service: "kras-tv-tmdb-proxy",
            tmdbConnected: false,
            error: "TMDB_API_KEY environment variable is not configured on the server."
          },
          503
        );
      }

      const testRes = await tmdbFetch("/configuration", {}, apiKey);
      if (testRes.ok) {
        return jsonResponse({
          ok: true,
          service: "kras-tv-tmdb-proxy",
          tmdbConnected: true,
          multiUserAuthEnabled: true,
          message: "Connected to TMDb API v3 and Multi-User Account Sync successfully."
        });
      }
      return jsonResponse(
        {
          ok: false,
          service: "kras-tv-tmdb-proxy",
          tmdbConnected: false,
          error: testRes.data?.status_message || "TMDb API key rejected by upstream."
        },
        502
      );
    }

    // -------------------------------------------------------------------------
    // 2. Browser Deep-Link Callback Trampoline
    //    GET /api/tmdb/auth/callback?request_token=...&approved=true&state=...&kras_user=...
    // -------------------------------------------------------------------------
    if (pathname === "/api/tmdb/auth/callback" && request.method === "GET") {
      const requestToken = url.searchParams.get("request_token") || "";
      const approved = url.searchParams.get("approved") || "true";
      const denied = url.searchParams.get("denied") || "false";
      const state = url.searchParams.get("state") || "";
      const krasUser = url.searchParams.get("kras_user") || "";
      const appRedirect = url.searchParams.get("app_redirect") || "krastv://tmdb-auth";

      await ensureDatabaseSchema(env);
      if (env && env.DB && requestToken) {
        try {
          await env.DB.prepare(
            `UPDATE tmdb_auth_requests
             SET status = ?1, updated_at = ?2
             WHERE request_token = ?3`
          )
            .bind(denied === "true" || approved === "false" ? "DENIED" : "APPROVED", Date.now(), requestToken)
            .run();
        } catch (_) {}
      }

      const deepLinkParams = new URLSearchParams();
      if (requestToken) deepLinkParams.set("request_token", requestToken);
      deepLinkParams.set("approved", denied === "true" ? "false" : approved);
      if (denied === "true") deepLinkParams.set("denied", "true");
      if (state) deepLinkParams.set("state", state);
      if (krasUser) deepLinkParams.set("kras_user", krasUser);

      const separator = appRedirect.includes("?") ? "&" : "?";
      const targetDeepLink = `${appRedirect}${separator}${deepLinkParams.toString()}`;

      const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>KRAS TV — TMDb Authorization</title>
  <style>
    body {
      margin: 0;
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      background: #08090E;
      color: #FFFFFF;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      padding: 24px;
      text-align: center;
    }
    .card {
      max-width: 420px;
      width: 100%;
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid rgba(255, 255, 255, 0.14);
      border-radius: 20px;
      padding: 28px 24px;
      box-shadow: 0 20px 50px rgba(0, 0, 0, 0.6);
    }
    h1 {
      font-size: 20px;
      margin: 0 0 10px;
      color: #FFFFFF;
    }
    p {
      font-size: 14px;
      line-height: 1.5;
      color: #B0B6C6;
      margin: 0 0 22px;
    }
    a.btn {
      display: inline-block;
      background: #E50914;
      color: #FFFFFF;
      font-weight: 700;
      font-size: 14px;
      text-decoration: none;
      padding: 12px 24px;
      border-radius: 12px;
    }
  </style>
  <script>
    window.onload = function() {
      window.location.replace(${JSON.stringify(targetDeepLink)});
    };
  </script>
</head>
<body>
  <div class="card">
    <h1>Returning to KRAS TV...</h1>
    <p>Your TMDb authorization has been processed. If KRAS TV does not open automatically, tap the button below or return to the app and tap <b>Complete Sign-In</b>.</p>
    <a class="btn" href="${targetDeepLink.replace(/"/g, "&quot;")}">Open KRAS TV</a>
  </div>
</body>
</html>`;
      return htmlResponse(html, 200);
    }

    // Require TMDB_API_KEY for all remaining TMDb API routes
    if (!apiKey || String(apiKey).trim() === "") {
      return jsonResponse(
        {
          ok: false,
          error: "missing_api_key",
          message: "TMDB_API_KEY is not configured on the Cloudflare Worker."
        },
        503
      );
    }

    // -------------------------------------------------------------------------
    // 3. Official TMDb Multi-User Account Authentication Endpoints
    // -------------------------------------------------------------------------

    // 3A. Create Request Token: POST /api/tmdb/auth/request-token
    if (pathname === "/api/tmdb/auth/request-token" && (request.method === "POST" || request.method === "GET")) {
      let body = {};
      if (request.method === "POST") {
        body = await request.json().catch(() => ({}));
      }
      const appRedirectTo = String(body.redirect_to || url.searchParams.get("redirect_to") || "krastv://tmdb-auth").trim();
      const krasUserId = String(body.kras_user_id || headerKrasUserId || "default").trim();
      const stateNonce = String(body.state || url.searchParams.get("state") || "").trim();

      const tokenRes = await tmdbFetch("/authentication/token/new", {}, apiKey);
      if (!tokenRes.ok || !tokenRes.data?.request_token) {
        return jsonResponse(
          {
            ok: false,
            error: "request_token_failed",
            message: tokenRes.data?.status_message || "Failed to create TMDb authorization token."
          },
          502
        );
      }

      const requestToken = String(tokenRes.data.request_token);
      const expiresAt = String(tokenRes.data.expires_at || "");

      await ensureDatabaseSchema(env);
      if (env && env.DB) {
        const now = Date.now();
        try {
          await env.DB.prepare(
            `INSERT OR REPLACE INTO tmdb_auth_requests (
               request_token, kras_user_id, state_nonce, redirect_to, status, expires_at, created_at, updated_at
             ) VALUES (?1, ?2, ?3, ?4, 'PENDING', ?5, ?6, ?6)`
          )
            .bind(requestToken, krasUserId, stateNonce, appRedirectTo, expiresAt, now)
            .run();
        } catch (_) {}
      }

      // Use HTTPS Worker callback trampoline so mobile browsers reliably redirect back to krastv://tmdb-auth
      const callbackUrl = new URL(`${url.origin}/api/tmdb/auth/callback`);
      callbackUrl.searchParams.set("app_redirect", appRedirectTo);
      if (stateNonce) callbackUrl.searchParams.set("state", stateNonce);
      if (krasUserId) callbackUrl.searchParams.set("kras_user", krasUserId);

      const authUrl = `https://www.themoviedb.org/authenticate/${encodeURIComponent(requestToken)}?redirect_to=${encodeURIComponent(callbackUrl.toString())}`;

      return jsonResponse({
        ok: true,
        request_token: requestToken,
        expires_at: expiresAt,
        state: stateNonce,
        kras_user_id: krasUserId,
        auth_url: authUrl
      });
    }

    // 3B. Exchange Approved Request Token for Authenticated Session: POST /api/tmdb/auth/session
    if (pathname === "/api/tmdb/auth/session" && request.method === "POST") {
      const body = await request.json().catch(() => ({}));
      const requestToken = String(body.request_token || "").trim();
      const krasUserId = String(body.kras_user_id || headerKrasUserId || "default").trim();
      const stateNonce = String(body.state || "").trim();

      if (!requestToken) {
        return jsonResponse(
          {
            ok: false,
            error: "missing_request_token",
            message: "Missing request_token."
          },
          400
        );
      }

      // Verify state_nonce and kras_user_id against D1 pending auth record if present
      await ensureDatabaseSchema(env);
      if (env && env.DB) {
        try {
          const pendingRow = await env.DB.prepare(
            `SELECT request_token, kras_user_id, state_nonce, status
             FROM tmdb_auth_requests
             WHERE request_token = ?1`
          )
            .bind(requestToken)
            .first();

          if (pendingRow) {
            if (
              pendingRow.state_nonce &&
              stateNonce &&
              String(pendingRow.state_nonce) !== stateNonce
            ) {
              return jsonResponse(
                {
                  ok: false,
                  error: "invalid_callback_state",
                  message: "TMDb authorization state mismatch. Please restart account sign-in."
                },
                403
              );
            }
            if (
              pendingRow.kras_user_id &&
              pendingRow.kras_user_id !== "default" &&
              krasUserId &&
              krasUserId !== "default" &&
              String(pendingRow.kras_user_id) !== krasUserId
            ) {
              return jsonResponse(
                {
                  ok: false,
                  error: "cross_user_token_forbidden",
                  message: "This authorization token belongs to a different KRAS TV user profile."
                },
                403
              );
            }
          }
        } catch (_) {}
      }

      const sessionRes = await tmdbFetch(
        "/authentication/session/new",
        {},
        apiKey,
        {
          method: "POST",
          body: { request_token: requestToken }
        }
      );

      if (!sessionRes.ok || !sessionRes.data?.session_id) {
        const upstreamMsg = String(sessionRes.data?.status_message || "");
        return jsonResponse(
          {
            ok: false,
            error: "session_creation_failed",
            message: upstreamMsg.toLowerCase().includes("not been approved") || sessionRes.status === 401
              ? "Please approve the TMDb authorization request in your browser first, then return to KRAS TV."
              : upstreamMsg || "Could not create TMDb session."
          },
          401
        );
      }

      const sessionId = String(sessionRes.data.session_id);

      // Fetch connected TMDb account profile details
      const accountRes = await tmdbFetch("/account", { session_id: sessionId }, apiKey);
      if (!accountRes.ok || !accountRes.data?.id) {
        return jsonResponse(
          {
            ok: false,
            error: "account_fetch_failed",
            message: "TMDb session created, but account profile could not be retrieved."
          },
          502
        );
      }

      const account = normalizeAccountObject(accountRes.data);
      await saveSessionToD1(env, sessionId, krasUserId, account);

      if (env && env.DB) {
        try {
          await env.DB.prepare(`DELETE FROM tmdb_auth_requests WHERE request_token = ?1`)
            .bind(requestToken)
            .run();
        } catch (_) {}
      }

      return jsonResponse({
        ok: true,
        session_id: sessionId,
        kras_user_id: krasUserId,
        account
      });
    }

    // 3C. Fetch Connected TMDb Account Profile: GET /api/tmdb/account
    if (pathname === "/api/tmdb/account" && request.method === "GET") {
      const sessionId = String(
        url.searchParams.get("session_id") || request.headers.get("X-TMDb-Session") || ""
      ).trim();

      const validation = await validateSessionAndOwnership(sessionId, 0, headerKrasUserId, apiKey, env);
      if (!validation.ok) {
        return jsonResponse(
          {
            ok: false,
            error: validation.error,
            message: validation.message
          },
          validation.status
        );
      }

      return jsonResponse({
        ok: true,
        account: validation.account
      });
    }

    // 3D. Disconnect / Logout TMDb Session: POST /api/tmdb/auth/logout
    if (pathname === "/api/tmdb/auth/logout" && (request.method === "POST" || request.method === "DELETE")) {
      const body = await request.json().catch(() => ({}));
      const sessionId = String(body.session_id || request.headers.get("X-TMDb-Session") || "").trim();
      if (!sessionId) {
        return jsonResponse({ ok: true, message: "No active session provided." });
      }

      await tmdbFetch(
        "/authentication/session",
        {},
        apiKey,
        {
          method: "DELETE",
          body: { session_id: sessionId }
        }
      );

      if (env && env.DB) {
        await ensureDatabaseSchema(env);
        try {
          await env.DB.prepare(
            `UPDATE tmdb_user_sessions SET revoked = 1, last_verified_at = ?1 WHERE session_id = ?2`
          )
            .bind(Date.now(), sessionId)
            .run();
        } catch (_) {}
      }

      return jsonResponse({
        ok: true,
        message: "TMDb session disconnected."
      });
    }

    // -------------------------------------------------------------------------
    // 4. Two-Way Synchronization & Account Mutation Endpoints
    // -------------------------------------------------------------------------

    // 4A. Full Two-Way Sync (Favorites, Watchlist, Ratings + Offline Mutations): POST /api/tmdb/sync
    if (pathname === "/api/tmdb/sync" && request.method === "POST") {
      const body = await request.json().catch(() => ({}));
      const sessionId = String(body.session_id || request.headers.get("X-TMDb-Session") || "").trim();
      const krasUserId = String(body.kras_user_id || headerKrasUserId || "default").trim();
      const requestedAccountId = Number(body.account_id || 0);
      const language = String(body.language || "en-US").trim();

      const localFavorites = Array.isArray(body.favorites) ? body.favorites : [];
      const localWatchlist = Array.isArray(body.watchlist) ? body.watchlist : [];
      const localRatings = Array.isArray(body.ratings) ? body.ratings : [];
      const pendingMutations = Array.isArray(body.mutations) ? body.mutations : [];

      // Validate session & enforce per-user account ownership (prevents cross-account access)
      const validation = await validateSessionAndOwnership(
        sessionId,
        requestedAccountId,
        krasUserId,
        apiKey,
        env
      );
      if (!validation.ok) {
        return jsonResponse(
          {
            ok: false,
            error: validation.error,
            message: validation.message
          },
          validation.status
        );
      }

      const account = validation.account;
      const accountId = account.id;
      await saveSessionToD1(env, sessionId, krasUserId, account);

      // Step 1: Replay queued offline mutations (ordered oldest -> newest)
      const sortedMutations = [...pendingMutations]
        .sort((a, b) => Number(a?.updated_at || 0) - Number(b?.updated_at || 0))
        .slice(0, 60);

      const tombstoneFavorites = new Set();
      const tombstoneWatchlist = new Set();

      for (const mut of sortedMutations) {
        const tmdbId = Number(mut?.tmdb_id || 0);
        if (tmdbId <= 0) continue;
        const normalizedType = normalizeMediaType(mut?.media_type);
        const tmdbMediaType = normalizedType === "SERIES" ? "tv" : "movie";
        const category = String(mut?.category || "").toUpperCase();
        const action = String(mut?.action || "").toUpperCase();
        const itemKey = `${normalizedType}:${tmdbId}`;

        if (category === "FAVORITE") {
          const isFav = action !== "REMOVE";
          if (!isFav) tombstoneFavorites.add(itemKey);
          else tombstoneFavorites.delete(itemKey);
          await tmdbFetch(
            `/account/${accountId}/favorite`,
            { session_id: sessionId },
            apiKey,
            {
              method: "POST",
              body: {
                media_type: tmdbMediaType,
                media_id: tmdbId,
                favorite: isFav
              }
            }
          ).catch(() => null);
        } else if (category === "WATCHLIST") {
          const isWl = action !== "REMOVE";
          if (!isWl) tombstoneWatchlist.add(itemKey);
          else tombstoneWatchlist.delete(itemKey);
          await tmdbFetch(
            `/account/${accountId}/watchlist`,
            { session_id: sessionId },
            apiKey,
            {
              method: "POST",
              body: {
                media_type: tmdbMediaType,
                media_id: tmdbId,
                watchlist: isWl
              }
            }
          ).catch(() => null);
        } else if (category === "RATING") {
          if (action === "REMOVE") {
            await tmdbFetch(
              `/${tmdbMediaType}/${tmdbId}/rating`,
              { session_id: sessionId },
              apiKey,
              { method: "DELETE" }
            ).catch(() => null);
          } else {
            const rawRating = Number(mut?.user_rating || 0);
            if (rawRating > 0) {
              const clampedRating = Math.min(10, Math.max(0.5, Math.round(rawRating * 2) / 2));
              await tmdbFetch(
                `/${tmdbMediaType}/${tmdbId}/rating`,
                { session_id: sessionId },
                apiKey,
                {
                  method: "POST",
                  body: { value: clampedRating }
                }
              ).catch(() => null);
            }
          }
        }
      }

      // Step 2: Push current local Favorites, Watchlist, and Ratings to TMDb (bounded for Worker CPU limits)
      const pushPromises = [];

      for (const fav of deduplicateSyncItems(localFavorites).slice(0, 40)) {
        const tmdbId = Number(fav.tmdb_id || fav.tmdbId || 0);
        const normalizedType = normalizeMediaType(fav.media_type || fav.mediaType);
        if (tmdbId <= 0 || tombstoneFavorites.has(`${normalizedType}:${tmdbId}`)) continue;
        const tmdbMediaType = normalizedType === "SERIES" ? "tv" : "movie";
        pushPromises.push(
          tmdbFetch(
            `/account/${accountId}/favorite`,
            { session_id: sessionId },
            apiKey,
            {
              method: "POST",
              body: {
                media_type: tmdbMediaType,
                media_id: tmdbId,
                favorite: fav.favorite !== false
              }
            }
          ).catch(() => null)
        );
      }

      for (const wl of deduplicateSyncItems(localWatchlist).slice(0, 40)) {
        const tmdbId = Number(wl.tmdb_id || wl.tmdbId || 0);
        const normalizedType = normalizeMediaType(wl.media_type || wl.mediaType);
        if (tmdbId <= 0 || tombstoneWatchlist.has(`${normalizedType}:${tmdbId}`)) continue;
        const tmdbMediaType = normalizedType === "SERIES" ? "tv" : "movie";
        pushPromises.push(
          tmdbFetch(
            `/account/${accountId}/watchlist`,
            { session_id: sessionId },
            apiKey,
            {
              method: "POST",
              body: {
                media_type: tmdbMediaType,
                media_id: tmdbId,
                watchlist: wl.watchlist !== false
              }
            }
          ).catch(() => null)
        );
      }

      for (const rt of deduplicateSyncItems(localRatings).slice(0, 40)) {
        const tmdbId = Number(rt.tmdb_id || rt.tmdbId || 0);
        const rawRating = Number(rt.user_rating || rt.userRating || rt.rating || 0);
        if (tmdbId <= 0 || rawRating <= 0) continue;
        const clampedRating = Math.min(10, Math.max(0.5, Math.round(rawRating * 2) / 2));
        const normalizedType = normalizeMediaType(rt.media_type || rt.mediaType);
        const ratingPath = normalizedType === "SERIES" ? `/tv/${tmdbId}/rating` : `/movie/${tmdbId}/rating`;
        pushPromises.push(
          tmdbFetch(
            ratingPath,
            { session_id: sessionId },
            apiKey,
            {
              method: "POST",
              body: { value: clampedRating }
            }
          ).catch(() => null)
        );
      }

      if (pushPromises.length > 0) {
        await Promise.allSettled(pushPromises);
      }

      // Step 3: Pull all 6 remote TMDb collections (Movies & TV for Favorites, Watchlist, Ratings)
      const [
        favMoviesRaw,
        favTvRaw,
        wlMoviesRaw,
        wlTvRaw,
        ratedMoviesRaw,
        ratedTvRaw
      ] = await Promise.all([
        fetchPaginatedTmdbList(`/account/${accountId}/favorite/movies`, sessionId, language, apiKey, 5),
        fetchPaginatedTmdbList(`/account/${accountId}/favorite/tv`, sessionId, language, apiKey, 5),
        fetchPaginatedTmdbList(`/account/${accountId}/watchlist/movies`, sessionId, language, apiKey, 5),
        fetchPaginatedTmdbList(`/account/${accountId}/watchlist/tv`, sessionId, language, apiKey, 5),
        fetchPaginatedTmdbList(`/account/${accountId}/rated/movies`, sessionId, language, apiKey, 5),
        fetchPaginatedTmdbList(`/account/${accountId}/rated/tv`, sessionId, language, apiKey, 5)
      ]);

      const favorites = deduplicateSyncItems([
        ...favMoviesRaw.map((m) => mapTmdbItemToSyncItem(m, "MOVIE", { favorite: true })),
        ...favTvRaw.map((t) => mapTmdbItemToSyncItem(t, "SERIES", { favorite: true }))
      ]).filter((item) => !tombstoneFavorites.has(`${item.media_type}:${item.tmdb_id}`));

      const watchlist = deduplicateSyncItems([
        ...wlMoviesRaw.map((m) => mapTmdbItemToSyncItem(m, "MOVIE", { watchlist: true })),
        ...wlTvRaw.map((t) => mapTmdbItemToSyncItem(t, "SERIES", { watchlist: true }))
      ]).filter((item) => !tombstoneWatchlist.has(`${item.media_type}:${item.tmdb_id}`));

      const ratings = deduplicateSyncItems([
        ...ratedMoviesRaw.map((m) => mapTmdbItemToSyncItem(m, "MOVIE", { user_rating: m.rating })),
        ...ratedTvRaw.map((t) => mapTmdbItemToSyncItem(t, "SERIES", { user_rating: t.rating }))
      ]);

      // Step 4: Persist snapshot to Cloudflare D1 if bound
      await saveSyncSnapshotToD1(env, account, favorites, watchlist, ratings);

      return jsonResponse({
        ok: true,
        account,
        favorites,
        watchlist,
        ratings,
        synced_at: Date.now()
      });
    }

    // 4B. Single Favorite Toggle: POST /api/tmdb/account/favorite
    if (pathname === "/api/tmdb/account/favorite" && request.method === "POST") {
      const body = await request.json().catch(() => ({}));
      const sessionId = String(body.session_id || request.headers.get("X-TMDb-Session") || "").trim();
      const requestedAccountId = Number(body.account_id || 0);
      const tmdbId = Number(body.tmdb_id || 0);
      const mediaType = normalizeMediaType(body.media_type) === "SERIES" ? "tv" : "movie";
      const favorite = Boolean(body.favorite);

      if (!sessionId || tmdbId <= 0) {
        return jsonResponse({ ok: false, error: "invalid_params", message: "Missing session_id or tmdb_id." }, 400);
      }

      const validation = await validateSessionAndOwnership(
        sessionId,
        requestedAccountId,
        headerKrasUserId,
        apiKey,
        env
      );
      if (!validation.ok) {
        return jsonResponse({ ok: false, error: validation.error, message: validation.message }, validation.status);
      }

      const res = await tmdbFetch(
        `/account/${validation.account.id}/favorite`,
        { session_id: sessionId },
        apiKey,
        {
          method: "POST",
          body: {
            media_type: mediaType,
            media_id: tmdbId,
            favorite
          }
        }
      );

      return jsonResponse(
        {
          ok: res.ok,
          status_code: res.data?.status_code,
          message: res.data?.status_message || (res.ok ? "Favorite updated." : "Failed to update favorite.")
        },
        res.ok ? 200 : 502
      );
    }

    // 4C. Single Watchlist Toggle: POST /api/tmdb/account/watchlist
    if (pathname === "/api/tmdb/account/watchlist" && request.method === "POST") {
      const body = await request.json().catch(() => ({}));
      const sessionId = String(body.session_id || request.headers.get("X-TMDb-Session") || "").trim();
      const requestedAccountId = Number(body.account_id || 0);
      const tmdbId = Number(body.tmdb_id || 0);
      const mediaType = normalizeMediaType(body.media_type) === "SERIES" ? "tv" : "movie";
      const watchlist = Boolean(body.watchlist);

      if (!sessionId || tmdbId <= 0) {
        return jsonResponse({ ok: false, error: "invalid_params", message: "Missing session_id or tmdb_id." }, 400);
      }

      const validation = await validateSessionAndOwnership(
        sessionId,
        requestedAccountId,
        headerKrasUserId,
        apiKey,
        env
      );
      if (!validation.ok) {
        return jsonResponse({ ok: false, error: validation.error, message: validation.message }, validation.status);
      }

      const res = await tmdbFetch(
        `/account/${validation.account.id}/watchlist`,
        { session_id: sessionId },
        apiKey,
        {
          method: "POST",
          body: {
            media_type: mediaType,
            media_id: tmdbId,
            watchlist
          }
        }
      );

      return jsonResponse(
        {
          ok: res.ok,
          status_code: res.data?.status_code,
          message: res.data?.status_message || (res.ok ? "Watchlist updated." : "Failed to update watchlist.")
        },
        res.ok ? 200 : 502
      );
    }

    // 4D. Single Rating Update: POST /api/tmdb/account/rating
    if (pathname === "/api/tmdb/account/rating" && request.method === "POST") {
      const body = await request.json().catch(() => ({}));
      const sessionId = String(body.session_id || request.headers.get("X-TMDb-Session") || "").trim();
      const requestedAccountId = Number(body.account_id || 0);
      const tmdbId = Number(body.tmdb_id || 0);
      const isSeries = normalizeMediaType(body.media_type) === "SERIES";
      const rawRating = Number(body.rating || 0);

      if (!sessionId || tmdbId <= 0 || rawRating <= 0) {
        return jsonResponse(
          { ok: false, error: "invalid_params", message: "Missing session_id, tmdb_id, or rating." },
          400
        );
      }

      const validation = await validateSessionAndOwnership(
        sessionId,
        requestedAccountId,
        headerKrasUserId,
        apiKey,
        env
      );
      if (!validation.ok) {
        return jsonResponse({ ok: false, error: validation.error, message: validation.message }, validation.status);
      }

      const clampedRating = Math.min(10, Math.max(0.5, Math.round(rawRating * 2) / 2));
      const ratingPath = isSeries ? `/tv/${tmdbId}/rating` : `/movie/${tmdbId}/rating`;

      const res = await tmdbFetch(
        ratingPath,
        { session_id: sessionId },
        apiKey,
        {
          method: "POST",
          body: { value: clampedRating }
        }
      );

      return jsonResponse(
        {
          ok: res.ok,
          status_code: res.data?.status_code,
          message: res.data?.status_message || (res.ok ? "Rating saved." : "Failed to save rating.")
        },
        res.ok ? 200 : 502
      );
    }

    // -------------------------------------------------------------------------
    // 5. Existing Movie & TV Metadata Enrichment Endpoints (Preserved)
    // -------------------------------------------------------------------------

    // 5A. Movie Metadata: GET /api/tmdb/movie?title=...&year=...&tmdbId=...
    if (pathname === "/api/tmdb/movie" || (pathname === "/api/tmdb/match" && (url.searchParams.get("type") || "movie") === "movie")) {
      const tmdbIdParam = Number(url.searchParams.get("tmdbId") || 0);
      const title = String(url.searchParams.get("title") || "").trim();
      const year = String(url.searchParams.get("year") || "").trim();
      const language = String(url.searchParams.get("language") || "en-US").trim();

      let targetId = tmdbIdParam;
      if (targetId <= 0 && title) {
        const searchRes = await tmdbFetch(
          "/search/movie",
          { query: title, year: year || undefined, language },
          apiKey
        );
        const firstMatch = searchRes.data?.results?.[0];
        if (firstMatch?.id) {
          targetId = Number(firstMatch.id);
        }
      }

      if (targetId <= 0) {
        return jsonResponse({ found: false, error: "Movie not found on TMDb." }, 404);
      }

      const detailsRes = await tmdbFetch(
        `/movie/${targetId}`,
        { append_to_response: "credits", language },
        apiKey
      );
      if (!detailsRes.ok || !detailsRes.data?.id) {
        return jsonResponse({ found: false, error: "Movie details unavailable." }, 404);
      }

      const d = detailsRes.data;
      const castList = Array.isArray(d.credits?.cast)
        ? d.credits.cast.slice(0, 8).map((c) => c.name).filter(Boolean)
        : [];
      const directorObj = Array.isArray(d.credits?.crew)
        ? d.credits.crew.find((c) => c.job === "Director")
        : null;
      const genresList = Array.isArray(d.genres)
        ? d.genres.map((g) => g.name).filter(Boolean)
        : [];

      return jsonResponse({
        found: true,
        tmdbId: Number(d.id),
        title: d.title || d.original_title || title,
        overview: d.overview || "",
        posterUrl: d.poster_path ? `${TMDB_IMAGE_W500}${d.poster_path}` : "",
        backdropUrl: d.backdrop_path ? `${TMDB_IMAGE_ORIGINAL}${d.backdrop_path}` : "",
        rating: Number(d.vote_average || 0),
        year: d.release_date ? String(d.release_date).slice(0, 4) : year,
        genres: genresList,
        cast: castList,
        director: directorObj?.name || "",
        runtimeMinutes: Number(d.runtime || 0)
      });
    }

    // 5B. TV Series Metadata: GET /api/tmdb/tv?title=...&year=...&tmdbId=...
    if (pathname === "/api/tmdb/tv" || (pathname === "/api/tmdb/match" && url.searchParams.get("type") === "tv")) {
      const tmdbIdParam = Number(url.searchParams.get("tmdbId") || 0);
      const title = String(url.searchParams.get("title") || "").trim();
      const year = String(url.searchParams.get("year") || "").trim();
      const language = String(url.searchParams.get("language") || "en-US").trim();

      let targetId = tmdbIdParam;
      if (targetId <= 0 && title) {
        const searchRes = await tmdbFetch(
          "/search/tv",
          { query: title, first_air_date_year: year || undefined, language },
          apiKey
        );
        const firstMatch = searchRes.data?.results?.[0];
        if (firstMatch?.id) {
          targetId = Number(firstMatch.id);
        }
      }

      if (targetId <= 0) {
        return jsonResponse({ found: false, error: "TV series not found on TMDb." }, 404);
      }

      const detailsRes = await tmdbFetch(
        `/tv/${targetId}`,
        { append_to_response: "credits", language },
        apiKey
      );
      if (!detailsRes.ok || !detailsRes.data?.id) {
        return jsonResponse({ found: false, error: "TV series details unavailable." }, 404);
      }

      const d = detailsRes.data;
      const castList = Array.isArray(d.credits?.cast)
        ? d.credits.cast.slice(0, 8).map((c) => c.name).filter(Boolean)
        : [];
      const creatorName = Array.isArray(d.created_by) && d.created_by.length > 0
        ? d.created_by.map((c) => c.name).filter(Boolean).join(", ")
        : "";
      const genresList = Array.isArray(d.genres)
        ? d.genres.map((g) => g.name).filter(Boolean)
        : [];
      const seasonsList = Array.isArray(d.seasons)
        ? d.seasons.map((s) => ({
            seasonNumber: Number(s.season_number || 0),
            name: s.name || `Season ${s.season_number}`,
            episodeCount: Number(s.episode_count || 0),
            posterUrl: s.poster_path ? `${TMDB_IMAGE_W500}${s.poster_path}` : "",
            overview: s.overview || "",
            airDate: s.air_date || ""
          }))
        : [];

      return jsonResponse({
        found: true,
        tmdbId: Number(d.id),
        title: d.name || d.original_name || title,
        overview: d.overview || "",
        posterUrl: d.poster_path ? `${TMDB_IMAGE_W500}${d.poster_path}` : "",
        backdropUrl: d.backdrop_path ? `${TMDB_IMAGE_ORIGINAL}${d.backdrop_path}` : "",
        rating: Number(d.vote_average || 0),
        year: d.first_air_date ? String(d.first_air_date).slice(0, 4) : year,
        genres: genresList,
        cast: castList,
        director: creatorName,
        seasons: seasonsList
      });
    }

    // 5C. TV Season Episodes: GET /api/tmdb/tv/:tvId/season/:seasonNumber
    const seasonMatch = pathname.match(/^\/api\/tmdb\/tv\/(\d+)\/season\/(\d+)$/);
    if (seasonMatch && request.method === "GET") {
      const tvId = Number(seasonMatch[1]);
      const seasonNumber = Number(seasonMatch[2]);
      const language = String(url.searchParams.get("language") || "en-US").trim();

      const seasonRes = await tmdbFetch(`/tv/${tvId}/season/${seasonNumber}`, { language }, apiKey);
      if (!seasonRes.ok || !seasonRes.data) {
        return jsonResponse({ found: false, episodes: [] }, 404);
      }

      const episodes = Array.isArray(seasonRes.data.episodes)
        ? seasonRes.data.episodes.map((ep) => ({
            episodeNumber: Number(ep.episode_number || 0),
            seasonNumber: Number(ep.season_number ?? seasonNumber),
            name: ep.name || `Episode ${ep.episode_number}`,
            overview: ep.overview || "",
            stillUrl: ep.still_path ? `${TMDB_IMAGE_W500}${ep.still_path}` : "",
            airDate: ep.air_date || "",
            rating: Number(ep.vote_average || 0),
            runtimeMinutes: Number(ep.runtime || 0)
          }))
        : [];

      return jsonResponse({
        found: true,
        tvId,
        seasonNumber,
        episodes
      });
    }

    return jsonResponse(
      {
        ok: false,
        error: "not_found",
        message: `Endpoint not found: ${request.method} ${pathname}`
      },
      404
    );
  }
};
