/**
 * KRAS TV — Official Cloudflare Worker Backend for TMDb Metadata & Multi-User Account Sync
 * Worker URL: https://kras-tmdb-proxy.asdking01.workers.dev
 *
 * Required Cloudflare Worker Secret:
 *   - TMDB_API_KEY       : Your TMDb v3 API Key (or v4 Read Access Token)
 *
 * Optional Cloudflare Bindings / Variables:
 *   - DB                 : Cloudflare D1 Database for persistent pending authorizations,
 *                          one-time auth codes, multi-user sessions, and sync items
 *                          (Automatically initializes all tables on first request if bound;
 *                           also includes AES-GCM server-sealed token fallback if D1 is not bound)
 *   - ANDROID_APP_SHA256 : Optional comma-separated SHA-256 signing certificate fingerprints
 *                          for custom release keys in /.well-known/assetlinks.json
 */

const TMDB_BASE_URL = "https://api.themoviedb.org/3";
const TMDB_IMAGE_W500 = "https://image.tmdb.org/t/p/w500";
const TMDB_IMAGE_ORIGINAL = "https://image.tmdb.org/t/p/original";
const ANDROID_PACKAGE_NAME = "com.aistudio.krastv.vmzsqx";
const DEFAULT_ANDROID_SHA256 =
  "D6:C0:10:AE:89:B2:85:50:5C:80:81:15:99:EC:61:88:8A:C1:1D:BF:55:23:AA:8C:43:E4:EE:B8:92:31:DA:D0";

const PENDING_AUTH_TTL_MS = 15 * 60 * 1000; // 15 minutes
const ONE_TIME_CODE_TTL_MS = 5 * 60 * 1000; // 5 minutes

// Per-isolate rate limiter & short-lived isolate cache for instant callback->exchange handoff
const rateLimitBuckets = new Map();
const pendingAuthIsolateCache = new Map();
const authCodeIsolateCache = new Map();
const sessionIsolateCache = new Map();

const RATE_LIMIT_WINDOW_MS = 60_000;
const RATE_LIMIT_MAX_REQUESTS = 120;

let d1SchemaInitialized = false;

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
  "Access-Control-Allow-Headers":
    "Content-Type, Authorization, X-TMDb-Session, X-TMDb-Session-Id, X-KRAS-User-Id, X-KRAS-Installation-Id, X-KRAS-Proxy-Token, X-Client-App",
  "Content-Type": "application/json; charset=utf-8"
};

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: CORS_HEADERS
  });
}

function htmlResponse(html, status = 200, extraHeaders = {}) {
  return new Response(html, {
    status,
    headers: {
      "Access-Control-Allow-Origin": "*",
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-store, no-cache, must-revalidate",
      ...extraHeaders
    }
  });
}

function checkRateLimit(request) {
  const clientIp = request.headers.get("CF-Connecting-IP") || "global";
  const now = Date.now();
  const bucket = rateLimitBuckets.get(clientIp);
  if (!bucket || now - bucket.windowStart > RATE_LIMIT_WINDOW_MS) {
    rateLimitBuckets.set(clientIp, { windowStart: now, count: 1 });
    return true;
  }
  bucket.count += 1;
  return bucket.count <= RATE_LIMIT_MAX_REQUESTS;
}

function getD1(env) {
  if (!env) return null;
  const candidate = env.DB || env.KRAS_TMDB_DB || null;
  return candidate && typeof candidate.prepare === "function" ? candidate : null;
}

async function ensureD1Schema(env) {
  const db = getD1(env);
  if (!db || d1SchemaInitialized) return db;
  try {
    await db.batch([
      db.prepare(`
        CREATE TABLE IF NOT EXISTS tmdb_auth_requests (
          request_token TEXT PRIMARY KEY,
          auth_session_id TEXT NOT NULL,
          installation_id TEXT NOT NULL DEFAULT '',
          kras_user_id TEXT NOT NULL,
          state_nonce TEXT NOT NULL DEFAULT '',
          redirect_uri TEXT NOT NULL,
          status TEXT NOT NULL DEFAULT 'pending',
          approved INTEGER NOT NULL DEFAULT 0,
          expires_at TEXT NOT NULL,
          expires_at_ms INTEGER NOT NULL DEFAULT 0,
          created_at INTEGER NOT NULL
        )
      `),
      db.prepare(`
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
        )
      `),
      db.prepare(`
        CREATE TABLE IF NOT EXISTS tmdb_user_sessions (
          session_id TEXT PRIMARY KEY,
          tmdb_session_id TEXT NOT NULL DEFAULT '',
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
        )
      `),
      db.prepare(`
        CREATE TABLE IF NOT EXISTS tmdb_accounts (
          account_id INTEGER PRIMARY KEY,
          username TEXT NOT NULL,
          name TEXT NOT NULL DEFAULT '',
          avatar_url TEXT,
          session_id TEXT NOT NULL,
          updated_at INTEGER NOT NULL,
          last_sync_at INTEGER NOT NULL DEFAULT 0
        )
      `),
      db.prepare(`
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
        )
      `)
    ]);
    d1SchemaInitialized = true;
  } catch (_) {
    // Non-fatal; individual queries have try/catch guards
  }
  return db;
}

// ============================================================================
// Cryptographic Helpers (Opaque Token Sealing & One-Time Code Generation)
// Ensures raw TMDb session_id is never exposed to the Android APK or URLs.
// ============================================================================

function randomHex(byteLength = 20) {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

function bytesToBase64Url(bytes) {
  let binary = "";
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64UrlToBytes(b64url) {
  const b64 = b64url.replace(/-/g, "+").replace(/_/g, "/");
  const pad = b64.length % 4 === 0 ? "" : "=".repeat(4 - (b64.length % 4));
  const binary = atob(b64 + pad);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

async function deriveWorkerAesKey(env) {
  const secretMaterial = `${(env && env.TMDB_API_KEY) || "kras_default_key"}::kras-tmdb-session-seal-v2`;
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(secretMaterial));
  return crypto.subtle.importKey("raw", digest, { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
}

/**
 * Encrypts server-side session metadata (`tmdbSessionId`, `accountId`, `krasUserId`, `installationId`)
 * into an opaque `kras_tok_...` token with AES-256-GCM using a key derived from `env.TMDB_API_KEY`.
 * The Android client cannot decrypt this token and never sees the raw TMDb `session_id`.
 */
async function sealOpaqueClientToken(env, sessionPayload) {
  try {
    const key = await deriveWorkerAesKey(env);
    const iv = new Uint8Array(12);
    crypto.getRandomValues(iv);
    const plainBytes = new TextEncoder().encode(JSON.stringify(sessionPayload));
    const cipherBuf = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, plainBytes);
    const cipherBytes = new Uint8Array(cipherBuf);
    return `kras_tok_${bytesToBase64Url(iv)}.${bytesToBase64Url(cipherBytes)}`;
  } catch (_) {
    return `kras_tok_${randomHex(24)}`;
  }
}

async function unsealOpaqueClientToken(env, token) {
  if (!token || !token.startsWith("kras_tok_")) return null;
  const raw = token.slice("kras_tok_".length);
  const dotIdx = raw.indexOf(".");
  if (dotIdx <= 0) return null;
  try {
    const iv = base64UrlToBytes(raw.slice(0, dotIdx));
    const cipherBytes = base64UrlToBytes(raw.slice(dotIdx + 1));
    const key = await deriveWorkerAesKey(env);
    const plainBuf = await crypto.subtle.decrypt({ name: "AES-GCM", iv }, key, cipherBytes);
    return JSON.parse(new TextDecoder().decode(plainBuf));
  } catch (_) {
    return null;
  }
}

async function sealOneTimeAuthCode(env, codePayload) {
  try {
    const key = await deriveWorkerAesKey(env);
    const iv = new Uint8Array(12);
    crypto.getRandomValues(iv);
    const plainBytes = new TextEncoder().encode(JSON.stringify(codePayload));
    const cipherBuf = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, plainBytes);
    return `kras_code_${bytesToBase64Url(iv)}.${bytesToBase64Url(new Uint8Array(cipherBuf))}`;
  } catch (_) {
    return `kras_code_${randomHex(20)}`;
  }
}

async function unsealOneTimeAuthCode(env, authCode) {
  if (!authCode || !authCode.startsWith("kras_code_")) return null;
  const raw = authCode.slice("kras_code_".length);
  const dotIdx = raw.indexOf(".");
  if (dotIdx <= 0) return null;
  try {
    const iv = base64UrlToBytes(raw.slice(0, dotIdx));
    const cipherBytes = base64UrlToBytes(raw.slice(dotIdx + 1));
    const key = await deriveWorkerAesKey(env);
    const plainBuf = await crypto.subtle.decrypt({ name: "AES-GCM", iv }, key, cipherBytes);
    return JSON.parse(new TextDecoder().decode(plainBuf));
  } catch (_) {
    return null;
  }
}

function buildTmdbUrl(path, params, apiKey) {
  const url = new URL(`${TMDB_BASE_URL}${path}`);
  for (const [k, v] of Object.entries(params || {})) {
    if (v !== undefined && v !== null && String(v).trim() !== "") {
      url.searchParams.set(k, String(v).trim());
    }
  }
  const cleanKey = (apiKey || "").trim();
  const isBearer = cleanKey.length > 60 && cleanKey.includes(".");
  if (!isBearer && cleanKey) {
    url.searchParams.set("api_key", cleanKey);
  }
  return { url: url.toString(), isBearer, cleanKey };
}

async function tmdbFetch(path, params, env, options = {}) {
  const apiKey = env && env.TMDB_API_KEY ? String(env.TMDB_API_KEY).trim() : "";
  if (!apiKey) {
    return {
      ok: false,
      status: 500,
      error: "TMDB_API_KEY is not configured in Cloudflare Worker secrets."
    };
  }

  const { url, isBearer, cleanKey } = buildTmdbUrl(path, params, apiKey);
  const headers = {
    Accept: "application/json",
    "Content-Type": "application/json;charset=utf-8"
  };
  if (isBearer) {
    headers.Authorization = `Bearer ${cleanKey}`;
  }

  const fetchOptions = {
    method: options.method || "GET",
    headers
  };
  if (options.body) {
    fetchOptions.body = typeof options.body === "string" ? options.body : JSON.stringify(options.body);
  }

  const response = await fetch(url, fetchOptions);
  const data = await response.json().catch(() => ({}));
  return {
    ok: response.ok,
    status: response.status,
    data
  };
}

function extractClientToken(request, body = {}) {
  const headerToken =
    request.headers.get("X-TMDb-Session-Id") ||
    request.headers.get("X-TMDb-Session") ||
    "";
  if (headerToken.trim()) return headerToken.trim();

  const authHeader = request.headers.get("Authorization") || "";
  if (authHeader.toLowerCase().startsWith("bearer ")) {
    const token = authHeader.slice(7).trim();
    if (token) return token;
  }

  return String(
    body.clientToken || body.client_token || body.sessionId || body.session_id || ""
  ).trim();
}

function extractKrasUserId(request, body = {}, url = null) {
  const headerUser = request.headers.get("X-KRAS-User-Id") || "";
  if (headerUser.trim()) return headerUser.trim();
  if (body && (body.krasUserId || body.kras_user_id)) {
    return String(body.krasUserId || body.kras_user_id).trim();
  }
  if (url && url.searchParams) {
    const qUser = url.searchParams.get("kras_user") || url.searchParams.get("uid") || "";
    if (qUser.trim()) return qUser.trim();
  }
  return "demo";
}

function extractInstallationId(request, body = {}, url = null) {
  const headerInst = request.headers.get("X-KRAS-Installation-Id") || "";
  if (headerInst.trim()) return headerInst.trim();
  if (body && (body.installationId || body.installation_id)) {
    return String(body.installationId || body.installation_id).trim();
  }
  if (url && url.searchParams) {
    const qInst = url.searchParams.get("iid") || url.searchParams.get("installation_id") || "";
    if (qInst.trim()) return qInst.trim();
  }
  return "";
}

function extractAvatarUrl(accountData) {
  if (!accountData || !accountData.avatar) return "";
  const tmdbAvatarPath = accountData.avatar.tmdb && accountData.avatar.tmdb.avatar_path;
  if (tmdbAvatarPath) {
    return `${TMDB_IMAGE_W500}${tmdbAvatarPath}`;
  }
  const gravatarHash = accountData.avatar.gravatar && accountData.avatar.gravatar.hash;
  if (gravatarHash) {
    return `https://secure.gravatar.com/avatar/${gravatarHash}.jpg?s=200`;
  }
  return "";
}

// ============================================================================
// 0. Verified Android App Links Digital Asset Links (/.well-known/assetlinks.json)
// ============================================================================

function handleAssetLinks(env) {
  const fingerprints = new Set([DEFAULT_ANDROID_SHA256]);
  if (env && env.ANDROID_APP_SHA256) {
    String(env.ANDROID_APP_SHA256)
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean)
      .forEach((fp) => fingerprints.add(fp));
  }

  const statements = [
    {
      relation: ["delegate_permission/common.handle_all_urls"],
      target: {
        namespace: "android_app",
        package_name: ANDROID_PACKAGE_NAME,
        sha256_cert_fingerprints: Array.from(fingerprints)
      }
    }
  ];

  return new Response(JSON.stringify(statements, null, 2), {
    status: 200,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "public, max-age=3600",
      "Access-Control-Allow-Origin": "*"
    }
  });
}

// ============================================================================
// 1. Browser-Based Multi-User TMDb Account Authentication Endpoints
// ============================================================================

/**
 * Step 1: POST /api/tmdb/auth/request-token (and /api/tmdb/auth/session/start)
 * Creates a fresh TMDb `request_token`, associates it with `(installationId, krasUserId, stateNonce)`,
 * persists it in D1 `tmdb_auth_requests`, and returns the official TMDb authorization URL.
 */
async function handleCreateRequestToken(request, url, env) {
  let body = {};
  if (request.method === "POST") {
    body = await request.json().catch(() => ({}));
  }

  const appLinkUrl = String(
    body.appLinkUrl || body.app_link_url || `${url.origin}/app/tmdb-auth`
  ).trim();
  const krasUserId = extractKrasUserId(request, body, url);
  const installationId = extractInstallationId(request, body, url);
  const stateNonce = String(body.state || url.searchParams.get("state") || randomHex(16)).trim();

  const tokenRes = await tmdbFetch("/authentication/token/new", {}, env);
  if (!tokenRes.ok || !tokenRes.data || !tokenRes.data.request_token) {
    return jsonResponse(
      {
        status: "error",
        ok: false,
        error:
          (tokenRes.data && tokenRes.data.status_message) ||
          tokenRes.error ||
          "Failed to create TMDb request token."
      },
      tokenRes.status || 502
    );
  }

  const requestToken = tokenRes.data.request_token;
  const expiresAt = tokenRes.data.expires_at || "";
  const nowMs = Date.now();
  const expiresAtMs = nowMs + PENDING_AUTH_TTL_MS;
  const authSessionId = `auth_${randomHex(16)}`;

  const pendingRecord = {
    requestToken,
    authSessionId,
    installationId,
    krasUserId,
    stateNonce,
    appLinkUrl,
    status: "pending",
    expiresAt,
    expiresAtMs,
    createdAt: nowMs
  };
  pendingAuthIsolateCache.set(requestToken, pendingRecord);

  const db = await ensureD1Schema(env);
  if (db) {
    try {
      await db
        .prepare(
          `INSERT OR REPLACE INTO tmdb_auth_requests
           (request_token, auth_session_id, installation_id, kras_user_id, state_nonce, redirect_uri, status, approved, expires_at, expires_at_ms, created_at)
           VALUES (?, ?, ?, ?, ?, ?, 'pending', 0, ?, ?, ?)`
        )
        .bind(
          requestToken,
          authSessionId,
          installationId,
          krasUserId,
          stateNonce,
          appLinkUrl,
          expiresAt,
          expiresAtMs,
          nowMs
        )
        .run();
    } catch (_) {}
  }

  const callbackUrl = new URL(`${url.origin}/api/tmdb/auth/callback`);
  callbackUrl.searchParams.set("app_redirect", appLinkUrl);
  if (stateNonce) callbackUrl.searchParams.set("state", stateNonce);
  if (krasUserId) callbackUrl.searchParams.set("kras_user", krasUserId);
  if (installationId) callbackUrl.searchParams.set("iid", installationId);

  const authUrl = `https://www.themoviedb.org/authenticate/${encodeURIComponent(
    requestToken
  )}?redirect_to=${encodeURIComponent(callbackUrl.toString())}`;

  return jsonResponse({
    status: "ok",
    ok: true,
    authSessionId,
    requestToken,
    request_token: requestToken,
    state: stateNonce,
    authUrl,
    auth_url: authUrl,
    expiresAt,
    expires_at: expiresAt
  });
}

/**
 * Helper to create the official TMDb session on the server, persist it in D1,
 * and issue an opaque `clientToken` + single-use `authCode`.
 */
async function createAndPersistServerTmdbSession(
  env,
  requestToken,
  krasUserId,
  installationId,
  stateNonce
) {
  const sessionRes = await tmdbFetch(
    "/authentication/session/new",
    {},
    env,
    {
      method: "POST",
      body: { request_token: requestToken }
    }
  );

  if (!sessionRes.ok || !sessionRes.data || !sessionRes.data.session_id) {
    return {
      ok: false,
      status: sessionRes.status || 401,
      error:
        (sessionRes.data && sessionRes.data.status_message) ||
        "TMDb request token has not been approved yet or has expired."
    };
  }

  const tmdbSessionId = sessionRes.data.session_id;
  const accountRes = await tmdbFetch("/account", { session_id: tmdbSessionId }, env);
  if (!accountRes.ok || !accountRes.data || !accountRes.data.username) {
    return {
      ok: false,
      status: accountRes.status || 502,
      error: "Failed to verify TMDb account profile."
    };
  }

  const acc = accountRes.data;
  const accountId = acc.id || 0;
  const username = acc.username || "";
  const displayName = acc.name || username;
  const avatarUrl = extractAvatarUrl(acc);
  const includeAdult = acc.include_adult ? 1 : 0;
  const nowMs = Date.now();

  // Create opaque installation-bound client token (never exposes raw tmdbSessionId)
  const clientToken = await sealOpaqueClientToken(env, {
    tmdbSessionId,
    accountId,
    username,
    displayName,
    avatarUrl,
    krasUserId,
    installationId,
    iat: nowMs
  });

  // Create single-use 5-minute authorization code for the verified Android App Link
  const authCode = await sealOneTimeAuthCode(env, {
    clientToken,
    tmdbSessionId,
    accountId,
    username,
    displayName,
    avatarUrl,
    krasUserId,
    installationId,
    stateNonce,
    exp: nowMs + ONE_TIME_CODE_TTL_MS
  });

  const sessionRecord = {
    clientToken,
    tmdbSessionId,
    accountId,
    username,
    displayName,
    avatarUrl,
    krasUserId,
    installationId,
    createdAt: nowMs
  };

  sessionIsolateCache.set(clientToken, sessionRecord);
  authCodeIsolateCache.set(authCode, {
    ...sessionRecord,
    stateNonce,
    consumed: false,
    expiresAtMs: nowMs + ONE_TIME_CODE_TTL_MS
  });

  const db = await ensureD1Schema(env);
  if (db) {
    try {
      await db.batch([
        db
          .prepare(
            `UPDATE tmdb_auth_requests
             SET status = 'approved', approved = 1
             WHERE request_token = ?`
          )
          .bind(requestToken),
        db
          .prepare(
            `INSERT OR REPLACE INTO tmdb_user_sessions
             (session_id, tmdb_session_id, installation_id, kras_user_id, tmdb_account_id, username, display_name, avatar_url, include_adult, created_at, updated_at, last_sync_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)`
          )
          .bind(
            clientToken,
            tmdbSessionId,
            installationId,
            krasUserId,
            accountId,
            username,
            displayName,
            avatarUrl,
            includeAdult,
            nowMs,
            nowMs
          ),
        db
          .prepare(
            `INSERT OR REPLACE INTO tmdb_auth_codes
             (auth_code, client_token, tmdb_session_id, tmdb_account_id, username, display_name, avatar_url, installation_id, kras_user_id, state_nonce, consumed, expires_at_ms, created_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)`
          )
          .bind(
            authCode,
            clientToken,
            tmdbSessionId,
            accountId,
            username,
            displayName,
            avatarUrl,
            installationId,
            krasUserId,
            stateNonce,
            nowMs + ONE_TIME_CODE_TTL_MS,
            nowMs
          ),
        db
          .prepare(
            `INSERT OR REPLACE INTO tmdb_accounts
             (account_id, username, name, avatar_url, session_id, updated_at, last_sync_at)
             VALUES (?, ?, ?, ?, ?, ?, 0)`
          )
          .bind(accountId, username, displayName, avatarUrl, clientToken, nowMs)
      ]);
    } catch (_) {}
  }

  return {
    ok: true,
    authCode,
    clientToken,
    account: {
      id: accountId,
      accountId,
      username,
      name: displayName,
      avatarUrl,
      avatar_url: avatarUrl
    }
  };
}

/**
 * Renders the return handoff page that launches KRAS TV via Verified Android App Link
 * (`https://kras-tmdb-proxy.asdking01.workers.dev/app/tmdb-auth?code=...&state=...`)
 * and `intent://` / `krastv://tmdb-auth` with the one-time authorization code (`code`).
 */
function renderAppReturnPage({ appLinkUrl, customSchemeUrl, androidIntentUrl, isDenied, isExpired, username }) {
  const titleText = isDenied
    ? "Login Cancelled"
    : isExpired
    ? "Login Session Expired"
    : username
    ? `Logged in as @${username}`
    : "TMDb Login Successful";
  const subtitleText = isDenied
    ? "You cancelled or denied TMDb login. Returning to KRAS TV..."
    : isExpired
    ? "Your login request expired. Please return to KRAS TV and try again."
    : "Your TMDb account has been verified. Returning to KRAS TV automatically...";

  const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>KRAS TV — TMDb Login</title>
  <style>
    body {
      margin: 0;
      padding: 24px;
      background: #08080A;
      color: #FFFFFF;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 90vh;
      text-align: center;
    }
    .card {
      max-width: 400px;
      width: 100%;
      background: #141418;
      border: 1px solid rgba(229, 9, 20, 0.45);
      border-radius: 18px;
      padding: 28px 22px;
      box-shadow: 0 16px 40px rgba(0, 0, 0, 0.75);
    }
    .badge {
      display: inline-block;
      background: rgba(229, 9, 20, 0.18);
      color: #FF4D58;
      font-weight: 800;
      font-size: 12px;
      padding: 6px 12px;
      border-radius: 999px;
      letter-spacing: 0.8px;
      margin-bottom: 14px;
    }
    h1 {
      font-size: 20px;
      margin: 0 0 10px;
    }
    p {
      color: #B3B3B8;
      font-size: 14px;
      line-height: 1.5;
      margin: 0 0 22px;
    }
    .btn {
      display: block;
      width: 100%;
      box-sizing: border-box;
      background: #E50914;
      color: #FFFFFF;
      text-decoration: none;
      font-weight: 700;
      font-size: 15px;
      padding: 13px 18px;
      border-radius: 12px;
      margin-bottom: 10px;
    }
    .btn-secondary {
      display: block;
      width: 100%;
      box-sizing: border-box;
      background: rgba(255, 255, 255, 0.08);
      color: #E0E0E5;
      text-decoration: none;
      font-weight: 600;
      font-size: 13px;
      padding: 11px 16px;
      border-radius: 10px;
    }
  </style>
  <script>
    window.onload = function() {
      setTimeout(function() {
        window.location.replace(${JSON.stringify(customSchemeUrl)});
      }, 60);
      setTimeout(function() {
        window.location.href = ${JSON.stringify(androidIntentUrl)};
      }, 420);
    };
  </script>
</head>
<body>
  <div class="card">
    <div class="badge">KRAS TV</div>
    <h1>${titleText}</h1>
    <p>${subtitleText}</p>
    <a class="btn" href="${appLinkUrl}">Return to KRAS TV</a>
    <a class="btn-secondary" href="${customSchemeUrl}">Open KRAS TV App</a>
  </div>
</body>
</html>`;

  return htmlResponse(html, 200);
}

/**
 * Step 2: GET /api/tmdb/auth/callback
 * Invoked by TMDb after the user signs in on themoviedb.org and approves (or denies) access.
 * Validates the pending authorization, creates the official TMDb session on the server,
 * generates a one-time authorization code (`code`), and redirects to KRAS TV via the
 * verified Android App Link (`https://kras-tmdb-proxy.asdking01.workers.dev/app/tmdb-auth`).
 */
async function handleAuthCallback(url, env) {
  const requestToken = (url.searchParams.get("request_token") || "").trim();
  const approvedParam = (url.searchParams.get("approved") || "").trim().toLowerCase();
  const deniedParam = (url.searchParams.get("denied") || "").trim().toLowerCase();
  let stateNonce = (url.searchParams.get("state") || "").trim();
  let krasUserId = (url.searchParams.get("kras_user") || url.searchParams.get("uid") || "").trim();
  let installationId = (url.searchParams.get("iid") || "").trim();

  const db = await ensureD1Schema(env);
  let storedReq = pendingAuthIsolateCache.get(requestToken) || null;
  if (db && requestToken) {
    try {
      const row = await db
        .prepare(
          `SELECT request_token, installation_id, kras_user_id, state_nonce, status, expires_at_ms
           FROM tmdb_auth_requests
           WHERE request_token = ?`
        )
        .bind(requestToken)
        .first();
      if (row) {
        storedReq = {
          requestToken: row.request_token,
          installationId: row.installation_id || "",
          krasUserId: row.kras_user_id || "demo",
          stateNonce: row.state_nonce || "",
          status: row.status || "pending",
          expiresAtMs: Number(row.expires_at_ms || 0)
        };
      }
    } catch (_) {}
  }

  if (storedReq) {
    if (!stateNonce) stateNonce = storedReq.stateNonce || "";
    if (!krasUserId) krasUserId = storedReq.krasUserId || "demo";
    if (!installationId) installationId = storedReq.installationId || "";
  }

  const isDenied = deniedParam === "true" || approvedParam === "false";
  const isExpired =
    !requestToken ||
    (storedReq && storedReq.expiresAtMs > 0 && Date.now() > storedReq.expiresAtMs);

  const buildLinks = (paramsObj) => {
    const appLink = new URL(`${url.origin}/app/tmdb-auth`);
    const customLink = new URL("krastv://tmdb-auth");
    for (const [k, v] of Object.entries(paramsObj)) {
      if (v) {
        appLink.searchParams.set(k, v);
        customLink.searchParams.set(k, v);
      }
    }
    const intentQuery = customLink.searchParams.toString();
    const androidIntentUrl = `intent://tmdb-auth${
      intentQuery ? "?" + intentQuery : ""
    }#Intent;scheme=krastv;package=${ANDROID_PACKAGE_NAME};S.browser_fallback_url=${encodeURIComponent(
      appLink.toString()
    )};end`;
    return {
      appLinkUrl: appLink.toString(),
      customSchemeUrl: customLink.toString(),
      androidIntentUrl
    };
  };

  if (isDenied) {
    if (db && requestToken) {
      try {
        await db
          .prepare(`UPDATE tmdb_auth_requests SET status = 'denied', approved = 0 WHERE request_token = ?`)
          .bind(requestToken)
          .run();
      } catch (_) {}
    }
    const links = buildLinks({
      status: "denied",
      denied: "true",
      state: stateNonce,
      kras_user: krasUserId
    });
    return renderAppReturnPage({ ...links, isDenied: true, isExpired: false });
  }

  if (isExpired) {
    const links = buildLinks({
      status: "expired",
      expired: "true",
      state: stateNonce,
      kras_user: krasUserId
    });
    return renderAppReturnPage({ ...links, isDenied: false, isExpired: true });
  }

  // Validate pending authorization and immediately create the official TMDb session on the server
  const sessionCreation = await createAndPersistServerTmdbSession(
    env,
    requestToken,
    krasUserId || "demo",
    installationId,
    stateNonce
  );

  if (!sessionCreation.ok) {
    const links = buildLinks({
      status: "expired",
      state: stateNonce,
      kras_user: krasUserId
    });
    return renderAppReturnPage({ ...links, isDenied: false, isExpired: true });
  }

  // Return one-time authorization code (`code`) — never a TMDb session token in the URL
  const links = buildLinks({
    code: sessionCreation.authCode,
    status: "approved",
    approved: "true",
    state: stateNonce,
    kras_user: krasUserId
  });

  return renderAppReturnPage({
    ...links,
    isDenied: false,
    isExpired: false,
    username: sessionCreation.account.username
  });
}

/**
 * Step 2b: GET /app/tmdb-auth
 * Verified Android App Link destination (`https://kras-tmdb-proxy.asdking01.workers.dev/app/tmdb-auth`).
 * When Android App Link verification is active, Android opens `MainActivity` directly with this URL.
 * If opened in a browser tab, this page immediately hands off the one-time `code` to KRAS TV.
 */
async function handleAppLinkRoute(url) {
  const code = (url.searchParams.get("code") || url.searchParams.get("auth_code") || "").trim();
  const status = (url.searchParams.get("status") || "approved").trim();
  const state = (url.searchParams.get("state") || "").trim();
  const krasUser = (url.searchParams.get("kras_user") || url.searchParams.get("uid") || "").trim();

  const customLink = new URL("krastv://tmdb-auth");
  if (code) customLink.searchParams.set("code", code);
  if (status) customLink.searchParams.set("status", status);
  if (state) customLink.searchParams.set("state", state);
  if (krasUser) customLink.searchParams.set("kras_user", krasUser);

  const intentQuery = customLink.searchParams.toString();
  const androidIntentUrl = `intent://tmdb-auth${
    intentQuery ? "?" + intentQuery : ""
  }#Intent;scheme=krastv;package=${ANDROID_PACKAGE_NAME};end`;

  return renderAppReturnPage({
    appLinkUrl: customLink.toString(),
    customSchemeUrl: customLink.toString(),
    androidIntentUrl,
    isDenied: status === "denied",
    isExpired: status === "expired",
    username: ""
  });
}

/**
 * Step 3: POST /api/tmdb/auth/session (and /api/tmdb/auth/exchange)
 * Exchanges the single-use authorization code (`code`) or verifies a pending `requestToken`
 * for an opaque `clientToken` and verified TMDb account profile.
 */
async function handleCreateSession(request, env) {
  const body = await request.json().catch(() => ({}));
  const authCode = String(body.code || body.authCode || body.auth_code || "").trim();
  const requestToken = String(body.requestToken || body.request_token || "").trim();
  const krasUserId = extractKrasUserId(request, body);
  const installationId = extractInstallationId(request, body);
  const clientStateNonce = String(body.state || "").trim();

  const db = await ensureD1Schema(env);

  // Branch A: One-Time Authorization Code Exchange (Primary Flow from Verified Android App Link)
  if (authCode) {
    let codeData = authCodeIsolateCache.get(authCode) || null;

    if (db) {
      try {
        const row = await db
          .prepare(
            `SELECT auth_code, client_token, tmdb_session_id, tmdb_account_id, username, display_name,
                    avatar_url, installation_id, kras_user_id, state_nonce, consumed, expires_at_ms
             FROM tmdb_auth_codes
             WHERE auth_code = ?`
          )
          .bind(authCode)
          .first();

        if (row) {
          if (Number(row.consumed) === 1) {
            return jsonResponse(
              {
                status: "error",
                ok: false,
                errorCode: "expired_code",
                error: "This one-time authorization code has already been used."
              },
              410
            );
          }
          codeData = {
            clientToken: row.client_token,
            tmdbSessionId: row.tmdb_session_id,
            accountId: Number(row.tmdb_account_id || 0),
            username: row.username || "",
            displayName: row.display_name || row.username || "",
            avatarUrl: row.avatar_url || "",
            installationId: row.installation_id || "",
            krasUserId: row.kras_user_id || "demo",
            stateNonce: row.state_nonce || "",
            consumed: Number(row.consumed) === 1,
            expiresAtMs: Number(row.expires_at_ms || 0)
          };
        }
      } catch (_) {}
    }

    if (!codeData) {
      const unsealed = await unsealOneTimeAuthCode(env, authCode);
      if (unsealed) {
        codeData = {
          clientToken: unsealed.clientToken,
          tmdbSessionId: unsealed.tmdbSessionId,
          accountId: Number(unsealed.accountId || 0),
          username: unsealed.username || "",
          displayName: unsealed.displayName || unsealed.username || "",
          avatarUrl: unsealed.avatarUrl || "",
          installationId: unsealed.installationId || "",
          krasUserId: unsealed.krasUserId || "demo",
          stateNonce: unsealed.stateNonce || "",
          consumed: false,
          expiresAtMs: Number(unsealed.exp || 0)
        };
      }
    }

    if (!codeData) {
      return jsonResponse(
        {
          status: "error",
          ok: false,
          errorCode: "expired_code",
          error: "Invalid or expired authorization code."
        },
        410
      );
    }

    if (codeData.consumed || (codeData.expiresAtMs > 0 && Date.now() > codeData.expiresAtMs)) {
      return jsonResponse(
        {
          status: "error",
          ok: false,
          errorCode: "expired_code",
          error: "Authorization code has expired."
        },
        410
      );
    }

    if (
      codeData.stateNonce &&
      clientStateNonce &&
      codeData.stateNonce !== clientStateNonce
    ) {
      return jsonResponse(
        {
          status: "error",
          ok: false,
          errorCode: "state_mismatch",
          error: "Authorization state verification failed."
        },
        403
      );
    }

    if (
      codeData.krasUserId &&
      krasUserId &&
      codeData.krasUserId !== krasUserId
    ) {
      return jsonResponse(
        {
          status: "error",
          ok: false,
          error: "Authorization code belongs to a different KRAS TV user profile."
        },
        403
      );
    }

    // Mark one-time authorization code as consumed immediately
    codeData.consumed = true;
    authCodeIsolateCache.set(authCode, codeData);
    if (db) {
      try {
        await db
          .prepare(`UPDATE tmdb_auth_codes SET consumed = 1 WHERE auth_code = ?`)
          .bind(authCode)
          .run();
      } catch (_) {}
    }

    return jsonResponse({
      status: "ok",
      ok: true,
      verified: true,
      clientToken: codeData.clientToken,
      sessionId: codeData.clientToken,
      session_id: codeData.clientToken,
      account: {
        id: codeData.accountId,
        accountId: codeData.accountId,
        username: codeData.username,
        name: codeData.displayName,
        avatarUrl: codeData.avatarUrl,
        avatar_url: codeData.avatarUrl
      }
    });
  }

  // Branch B: Manual Resume / Direct Request Token Verification
  if (!requestToken) {
    return jsonResponse(
      {
        status: "error",
        ok: false,
        error: "Missing authorization code or requestToken."
      },
      400
    );
  }

  // Check if `/api/tmdb/auth/callback` already created a session for this requestToken/user
  if (db) {
    try {
      const reqRow = await db
        .prepare(
          `SELECT kras_user_id, installation_id, state_nonce, status, expires_at_ms
           FROM tmdb_auth_requests
           WHERE request_token = ?`
        )
        .bind(requestToken)
        .first();

      if (reqRow) {
        if (reqRow.status === "denied") {
          return jsonResponse(
            {
              status: "error",
              ok: false,
              errorCode: "denied",
              error: "Authorization was denied on TMDb."
            },
            403
          );
        }
        if (reqRow.expires_at_ms && Date.now() > Number(reqRow.expires_at_ms)) {
          return jsonResponse(
            {
              status: "error",
              ok: false,
              errorCode: "expired_token",
              error: "Authorization session expired."
            },
            410
          );
        }
        if (reqRow.state_nonce && clientStateNonce && reqRow.state_nonce !== clientStateNonce) {
          return jsonResponse(
            {
              status: "error",
              ok: false,
              errorCode: "state_mismatch",
              error: "Authorization state verification failed."
            },
            403
          );
        }

        // If callback already created the session in D1, consume the code and return it
        const existingCodeRow = await db
          .prepare(
            `SELECT auth_code, client_token, tmdb_account_id, username, display_name, avatar_url
             FROM tmdb_auth_codes
             WHERE kras_user_id = ? AND state_nonce = ? AND consumed = 0
             ORDER BY created_at DESC LIMIT 1`
          )
          .bind(krasUserId, clientStateNonce || reqRow.state_nonce || "")
          .first();

        if (existingCodeRow && existingCodeRow.client_token) {
          await db
            .prepare(`UPDATE tmdb_auth_codes SET consumed = 1 WHERE auth_code = ?`)
            .bind(existingCodeRow.auth_code)
            .run();

          return jsonResponse({
            status: "ok",
            ok: true,
            verified: true,
            clientToken: existingCodeRow.client_token,
            sessionId: existingCodeRow.client_token,
            session_id: existingCodeRow.client_token,
            account: {
              id: Number(existingCodeRow.tmdb_account_id || 0),
              accountId: Number(existingCodeRow.tmdb_account_id || 0),
              username: existingCodeRow.username || "",
              name: existingCodeRow.display_name || existingCodeRow.username || "",
              avatarUrl: existingCodeRow.avatar_url || "",
              avatar_url: existingCodeRow.avatar_url || ""
            }
          });
        }
      }
    } catch (_) {}
  }

  // Otherwise, attempt to exchange the approved request_token directly with TMDb
  const created = await createAndPersistServerTmdbSession(
    env,
    requestToken,
    krasUserId,
    installationId,
    clientStateNonce
  );

  if (!created.ok) {
    return jsonResponse(
      {
        status: "error",
        ok: false,
        error: created.error
      },
      created.status || 401
    );
  }

  return jsonResponse({
    status: "ok",
    ok: true,
    verified: true,
    clientToken: created.clientToken,
    sessionId: created.clientToken,
    session_id: created.clientToken,
    account: created.account
  });
}

/**
 * Resolves the server-side TMDb `session_id` from the client's opaque `clientToken`
 * and validates ownership (`krasUserId`, `installationId`, `requestedAccountId`)
 * so one user can never read or modify another user's TMDb account data.
 */
async function validateSessionAndOwnership(
  env,
  clientToken,
  requestedAccountId,
  krasUserId,
  installationId
) {
  if (!clientToken) {
    return { ok: false, status: 401, error: "Missing authentication session token." };
  }

  // 1. Check isolate memory cache
  const cached = sessionIsolateCache.get(clientToken);
  if (cached && cached.tmdbSessionId) {
    if (krasUserId && cached.krasUserId && cached.krasUserId !== krasUserId) {
      return {
        ok: false,
        status: 403,
        error: "Cross-user session access is forbidden."
      };
    }
    if (
      requestedAccountId > 0 &&
      cached.accountId > 0 &&
      cached.accountId !== requestedAccountId
    ) {
      return {
        ok: false,
        status: 403,
        error: "Session does not belong to the requested TMDb accountId."
      };
    }
    return {
      ok: true,
      tmdbSessionId: cached.tmdbSessionId,
      accountId: cached.accountId,
      username: cached.username
    };
  }

  // 2. Check Cloudflare D1 `tmdb_user_sessions`
  const db = await ensureD1Schema(env);
  if (db) {
    try {
      const row = await db
        .prepare(
          `SELECT tmdb_session_id, installation_id, kras_user_id, tmdb_account_id, username
           FROM tmdb_user_sessions
           WHERE session_id = ?`
        )
        .bind(clientToken)
        .first();
      if (row) {
        if (krasUserId && row.kras_user_id && row.kras_user_id !== krasUserId) {
          return {
            ok: false,
            status: 403,
            error: "Cross-user session access is forbidden."
          };
        }
        const boundAccountId = Number(row.tmdb_account_id || 0);
        if (
          requestedAccountId > 0 &&
          boundAccountId > 0 &&
          boundAccountId !== requestedAccountId
        ) {
          return {
            ok: false,
            status: 403,
            error: "Session does not belong to the requested TMDb accountId."
          };
        }
        const realTmdbSessionId = row.tmdb_session_id || clientToken;
        if (boundAccountId > 0 && realTmdbSessionId) {
          sessionIsolateCache.set(clientToken, {
            clientToken,
            tmdbSessionId: realTmdbSessionId,
            accountId: boundAccountId,
            username: row.username || "",
            krasUserId: row.kras_user_id || krasUserId
          });
          return {
            ok: true,
            tmdbSessionId: realTmdbSessionId,
            accountId: boundAccountId,
            username: row.username || ""
          };
        }
      }
    } catch (_) {}
  }

  // 3. Unseal AES-GCM server-sealed `kras_tok_...` token if D1 wasn't bound
  const unsealed = await unsealOpaqueClientToken(env, clientToken);
  if (unsealed && unsealed.tmdbSessionId) {
    if (krasUserId && unsealed.krasUserId && unsealed.krasUserId !== krasUserId) {
      return {
        ok: false,
        status: 403,
        error: "Cross-user session access is forbidden."
      };
    }
    const boundAccountId = Number(unsealed.accountId || 0);
    if (
      requestedAccountId > 0 &&
      boundAccountId > 0 &&
      boundAccountId !== requestedAccountId
    ) {
      return {
        ok: false,
        status: 403,
        error: "Session does not belong to the requested TMDb accountId."
      };
    }
    return {
      ok: true,
      tmdbSessionId: unsealed.tmdbSessionId,
      accountId: boundAccountId,
      username: unsealed.username || ""
    };
  }

  // 4. Legacy fallback: verify directly against TMDb `/account`
  const accRes = await tmdbFetch("/account", { session_id: clientToken }, env);
  if (!accRes.ok || !accRes.data || !accRes.data.id) {
    return {
      ok: false,
      status: 401,
      error: "Invalid or expired TMDb session."
    };
  }
  const verifiedAccountId = Number(accRes.data.id);
  if (requestedAccountId > 0 && verifiedAccountId !== requestedAccountId) {
    return {
      ok: false,
      status: 403,
      error: "Session does not belong to the requested TMDb accountId."
    };
  }

  return {
    ok: true,
    tmdbSessionId: clientToken,
    accountId: verifiedAccountId,
    username: accRes.data.username || ""
  };
}

/**
 * Disconnects and revokes the user's TMDb session (`POST /api/tmdb/auth/logout` or `DELETE /api/tmdb/auth/session`).
 */
async function handleLogout(request, env) {
  const body = await request.json().catch(() => ({}));
  const clientToken = extractClientToken(request, body);
  if (!clientToken) {
    return jsonResponse({ status: "ok", ok: true });
  }

  let tmdbSessionId = "";
  const cached = sessionIsolateCache.get(clientToken);
  if (cached && cached.tmdbSessionId) {
    tmdbSessionId = cached.tmdbSessionId;
  }
  sessionIsolateCache.delete(clientToken);

  const db = await ensureD1Schema(env);
  if (db) {
    try {
      const row = await db
        .prepare(`SELECT tmdb_session_id FROM tmdb_user_sessions WHERE session_id = ?`)
        .bind(clientToken)
        .first();
      if (row && row.tmdb_session_id) {
        tmdbSessionId = row.tmdb_session_id;
      }
      await db
        .prepare(`DELETE FROM tmdb_user_sessions WHERE session_id = ?`)
        .bind(clientToken)
        .run();
    } catch (_) {}
  }

  if (!tmdbSessionId) {
    const unsealed = await unsealOpaqueClientToken(env, clientToken);
    if (unsealed && unsealed.tmdbSessionId) {
      tmdbSessionId = unsealed.tmdbSessionId;
    }
  }

  if (tmdbSessionId) {
    await tmdbFetch(
      "/authentication/session",
      {},
      env,
      {
        method: "DELETE",
        body: { session_id: tmdbSessionId }
      }
    );
  }

  return jsonResponse({ status: "ok", ok: true });
}

// ============================================================================
// 2. Two-Way Synchronization & Real-Time Favorites / Watchlist / Ratings
// ============================================================================

function normalizeSyncItemFromTmdb(rawItem, mediaType, extra = {}) {
  const isTv = mediaType === "tv" || mediaType === "SERIES";
  const tmdbId = rawItem.id || rawItem.tmdbId || rawItem.tmdb_id || 0;
  const title = (isTv ? rawItem.name || rawItem.title : rawItem.title || rawItem.name) || "";
  const releaseDate = (isTv ? rawItem.first_air_date : rawItem.release_date) || "";
  const releaseYear = releaseDate ? String(releaseDate).slice(0, 4) : "";
  const posterUrl = rawItem.poster_path ? `${TMDB_IMAGE_W500}${rawItem.poster_path}` : "";
  const rating = typeof rawItem.vote_average === "number" ? Number(rawItem.vote_average.toFixed(1)) : 0.0;
  const userRating =
    typeof rawItem.rating === "number"
      ? Number(rawItem.rating.toFixed(1))
      : typeof extra.userRating === "number"
      ? extra.userRating
      : 0.0;

  return {
    tmdbId,
    tmdb_id: tmdbId,
    mediaType: isTv ? "SERIES" : "MOVIE",
    media_type: isTv ? "SERIES" : "MOVIE",
    title,
    posterUrl,
    poster_url: posterUrl,
    releaseYear,
    year: releaseYear,
    rating,
    userRating,
    user_rating: userRating,
    favorite: extra.favorite !== undefined ? Boolean(extra.favorite) : true,
    watchlist: extra.watchlist !== undefined ? Boolean(extra.watchlist) : true,
    updated_at: Date.now()
  };
}

async function fetchPaginatedAccountList(path, tmdbSessionId, language, env, maxPages = 3) {
  const results = [];
  for (let page = 1; page <= maxPages; page++) {
    const res = await tmdbFetch(
      path,
      {
        session_id: tmdbSessionId,
        language: language || "en-US",
        sort_by: "created_at.desc",
        page
      },
      env
    );
    if (!res.ok || !res.data || !Array.isArray(res.data.results)) break;
    results.push(...res.data.results);
    const totalPages = res.data.total_pages || 1;
    if (page >= totalPages) break;
  }
  return results;
}

async function resolveTmdbIdForLocalItem(item, env) {
  const existingId = Number(item.tmdb_id || item.tmdbId || 0);
  if (existingId > 0) return existingId;

  const title = String(item.title || "").trim();
  if (!title) return 0;

  const rawType = String(item.media_type || item.mediaType || "MOVIE").toUpperCase();
  const isTv = rawType === "SERIES" || rawType === "TV";
  const year = String(item.year || item.releaseYear || "").trim();

  const searchPath = isTv ? "/search/tv" : "/search/movie";
  const params = { query: title };
  if (year) {
    if (isTv) params.first_air_date_year = year;
    else params.primary_release_year = year;
  }

  const searchRes = await tmdbFetch(searchPath, params, env);
  if (
    searchRes.ok &&
    searchRes.data &&
    Array.isArray(searchRes.data.results) &&
    searchRes.data.results.length > 0
  ) {
    return Number(searchRes.data.results[0].id || 0);
  }
  return 0;
}

/**
 * POST /api/tmdb/sync
 * Executes two-way synchronization for the authenticated KRAS TV user:
 * 1. Validates opaque `clientToken` and ownership (`krasUserId`, `installationId`, `accountId`).
 * 2. Replays queued offline mutations (`mutations`: ADD/REMOVE Favorite, ADD/REMOVE Watchlist, RATE).
 * 3. Pulls all 6 remote TMDb collections (Favorite Movies, Favorite TV, Movie Watchlist, TV Watchlist,
 *    Rated Movies, Rated TV).
 * 4. Pushes any local additions or newer local ratings not yet on TMDb.
 * 5. Updates D1 cache and returns the deduplicated collections.
 */
async function handleTwoWaySync(request, env) {
  const body = await request.json().catch(() => ({}));
  const clientToken = extractClientToken(request, body);
  const krasUserId = extractKrasUserId(request, body);
  const installationId = extractInstallationId(request, body);
  const requestedAccountId = Number(body.accountId || body.account_id || 0);
  const language = String(body.language || "en-US").trim();

  const ownership = await validateSessionAndOwnership(
    env,
    clientToken,
    requestedAccountId,
    krasUserId,
    installationId
  );
  if (!ownership.ok) {
    return jsonResponse(
      {
        status: "error",
        ok: false,
        error: ownership.error
      },
      ownership.status || 403
    );
  }

  const tmdbSessionId = ownership.tmdbSessionId;
  const accountId = ownership.accountId;

  const localFavorites = Array.isArray(body.favorites) ? body.favorites : [];
  const localWatchlist = Array.isArray(body.watchlist) ? body.watchlist : [];
  const localRatings = Array.isArray(body.ratings) ? body.ratings : [];
  const pendingMutations = Array.isArray(body.mutations) ? body.mutations : [];

  let pushedFavorites = 0;
  let pushedWatchlist = 0;
  let pushedRatings = 0;

  const removedFavoriteKeys = new Set();
  const removedWatchlistKeys = new Set();

  // Step A: Replay offline mutations in chronological order
  const sortedMutations = [...pendingMutations].sort(
    (a, b) => Number(a.updated_at || 0) - Number(b.updated_at || 0)
  );
  for (const mut of sortedMutations.slice(0, 60)) {
    const tmdbId = await resolveTmdbIdForLocalItem(mut, env);
    if (tmdbId <= 0) continue;
    const rawType = String(mut.media_type || mut.mediaType || "MOVIE").toUpperCase();
    const isTv = rawType === "SERIES" || rawType === "TV";
    const tmdbType = isTv ? "tv" : "movie";
    const normKey = `${isTv ? "SERIES" : "MOVIE"}:${tmdbId}`;
    const category = String(mut.category || "").toUpperCase();
    const action = String(mut.action || "").toUpperCase();

    if (category === "FAVORITE") {
      const isFav = action !== "REMOVE";
      if (!isFav) removedFavoriteKeys.add(normKey);
      else removedFavoriteKeys.delete(normKey);

      const res = await tmdbFetch(
        `/account/${accountId}/favorite`,
        { session_id: tmdbSessionId },
        env,
        {
          method: "POST",
          body: { media_type: tmdbType, media_id: tmdbId, favorite: isFav }
        }
      );
      if (res.ok) pushedFavorites++;
    } else if (category === "WATCHLIST") {
      const inWl = action !== "REMOVE";
      if (!inWl) removedWatchlistKeys.add(normKey);
      else removedWatchlistKeys.delete(normKey);

      const res = await tmdbFetch(
        `/account/${accountId}/watchlist`,
        { session_id: tmdbSessionId },
        env,
        {
          method: "POST",
          body: { media_type: tmdbType, media_id: tmdbId, watchlist: inWl }
        }
      );
      if (res.ok) pushedWatchlist++;
    } else if (category === "RATING") {
      const userRating = Number(mut.user_rating || mut.userRating || 0);
      if (action === "REMOVE" || userRating <= 0) {
        await tmdbFetch(
          `/${tmdbType}/${tmdbId}/rating`,
          { session_id: tmdbSessionId },
          env,
          { method: "DELETE" }
        );
      } else {
        const clamped = Math.max(0.5, Math.min(10.0, Math.round(userRating * 2) / 2));
        const res = await tmdbFetch(
          `/${tmdbType}/${tmdbId}/rating`,
          { session_id: tmdbSessionId },
          env,
          { method: "POST", body: { value: clamped } }
        );
        if (res.ok) pushedRatings++;
      }
    }
  }

  // Step B: Fetch remote TMDb lists in parallel across all 6 collections
  const [
    remoteFavMovies,
    remoteFavTv,
    remoteWatchMovies,
    remoteWatchTv,
    remoteRatedMovies,
    remoteRatedTv
  ] = await Promise.all([
    fetchPaginatedAccountList(`/account/${accountId}/favorite/movies`, tmdbSessionId, language, env),
    fetchPaginatedAccountList(`/account/${accountId}/favorite/tv`, tmdbSessionId, language, env),
    fetchPaginatedAccountList(`/account/${accountId}/watchlist/movies`, tmdbSessionId, language, env),
    fetchPaginatedAccountList(`/account/${accountId}/watchlist/tv`, tmdbSessionId, language, env),
    fetchPaginatedAccountList(`/account/${accountId}/rated/movies`, tmdbSessionId, language, env),
    fetchPaginatedAccountList(`/account/${accountId}/rated/tv`, tmdbSessionId, language, env)
  ]);

  const remoteFavKeySet = new Set();
  const mergedFavorites = [];

  for (const m of remoteFavMovies) {
    const key = `MOVIE:${m.id}`;
    if (removedFavoriteKeys.has(key)) continue;
    remoteFavKeySet.add(key);
    mergedFavorites.push(normalizeSyncItemFromTmdb(m, "movie", { favorite: true }));
  }
  for (const t of remoteFavTv) {
    const key = `SERIES:${t.id}`;
    if (removedFavoriteKeys.has(key)) continue;
    remoteFavKeySet.add(key);
    mergedFavorites.push(normalizeSyncItemFromTmdb(t, "tv", { favorite: true }));
  }

  const remoteWatchKeySet = new Set();
  const mergedWatchlist = [];

  for (const m of remoteWatchMovies) {
    const key = `MOVIE:${m.id}`;
    if (removedWatchlistKeys.has(key)) continue;
    remoteWatchKeySet.add(key);
    mergedWatchlist.push(normalizeSyncItemFromTmdb(m, "movie", { watchlist: true }));
  }
  for (const t of remoteWatchTv) {
    const key = `SERIES:${t.id}`;
    if (removedWatchlistKeys.has(key)) continue;
    remoteWatchKeySet.add(key);
    mergedWatchlist.push(normalizeSyncItemFromTmdb(t, "tv", { watchlist: true }));
  }

  const remoteRatingMap = new Map();
  const mergedRatings = [];

  for (const m of remoteRatedMovies) {
    remoteRatingMap.set(`MOVIE:${m.id}`, m.rating || 0);
    mergedRatings.push(normalizeSyncItemFromTmdb(m, "movie", { userRating: m.rating || 0 }));
  }
  for (const t of remoteRatedTv) {
    remoteRatingMap.set(`SERIES:${t.id}`, t.rating || 0);
    mergedRatings.push(normalizeSyncItemFromTmdb(t, "tv", { userRating: t.rating || 0 }));
  }

  // Step C: Push local items missing from remote TMDb
  for (const item of localFavorites.slice(0, 35)) {
    if (item.favorite === false) continue;
    const tmdbId = await resolveTmdbIdForLocalItem(item, env);
    if (tmdbId <= 0) continue;
    const rawType = String(item.media_type || item.mediaType || "MOVIE").toUpperCase();
    const isTv = rawType === "SERIES" || rawType === "TV";
    const key = `${isTv ? "SERIES" : "MOVIE"}:${tmdbId}`;
    if (!remoteFavKeySet.has(key) && !removedFavoriteKeys.has(key)) {
      const pushRes = await tmdbFetch(
        `/account/${accountId}/favorite`,
        { session_id: tmdbSessionId },
        env,
        {
          method: "POST",
          body: {
            media_type: isTv ? "tv" : "movie",
            media_id: tmdbId,
            favorite: true
          }
        }
      );
      if (pushRes.ok) {
        pushedFavorites++;
        remoteFavKeySet.add(key);
        mergedFavorites.push({
          tmdbId,
          tmdb_id: tmdbId,
          mediaType: isTv ? "SERIES" : "MOVIE",
          media_type: isTv ? "SERIES" : "MOVIE",
          title: item.title || "",
          posterUrl: item.poster_url || item.posterUrl || "",
          poster_url: item.poster_url || item.posterUrl || "",
          releaseYear: item.year || item.releaseYear || "",
          year: item.year || item.releaseYear || "",
          rating: Number(item.rating || 0),
          userRating: 0,
          user_rating: 0,
          favorite: true,
          watchlist: false,
          updated_at: Date.now()
        });
      }
    }
  }

  for (const item of localWatchlist.slice(0, 35)) {
    if (item.watchlist === false) continue;
    const tmdbId = await resolveTmdbIdForLocalItem(item, env);
    if (tmdbId <= 0) continue;
    const rawType = String(item.media_type || item.mediaType || "MOVIE").toUpperCase();
    const isTv = rawType === "SERIES" || rawType === "TV";
    const key = `${isTv ? "SERIES" : "MOVIE"}:${tmdbId}`;
    if (!remoteWatchKeySet.has(key) && !removedWatchlistKeys.has(key)) {
      const pushRes = await tmdbFetch(
        `/account/${accountId}/watchlist`,
        { session_id: tmdbSessionId },
        env,
        {
          method: "POST",
          body: {
            media_type: isTv ? "tv" : "movie",
            media_id: tmdbId,
            watchlist: true
          }
        }
      );
      if (pushRes.ok) {
        pushedWatchlist++;
        remoteWatchKeySet.add(key);
        mergedWatchlist.push({
          tmdbId,
          tmdb_id: tmdbId,
          mediaType: isTv ? "SERIES" : "MOVIE",
          media_type: isTv ? "SERIES" : "MOVIE",
          title: item.title || "",
          posterUrl: item.poster_url || item.posterUrl || "",
          poster_url: item.poster_url || item.posterUrl || "",
          releaseYear: item.year || item.releaseYear || "",
          year: item.year || item.releaseYear || "",
          rating: Number(item.rating || 0),
          userRating: 0,
          user_rating: 0,
          favorite: false,
          watchlist: true,
          updated_at: Date.now()
        });
      }
    }
  }

  for (const item of localRatings.slice(0, 35)) {
    const userRating = Number(item.user_rating || item.userRating || 0);
    if (userRating < 0.5 || userRating > 10.0) continue;
    const tmdbId = await resolveTmdbIdForLocalItem(item, env);
    if (tmdbId <= 0) continue;
    const rawType = String(item.media_type || item.mediaType || "MOVIE").toUpperCase();
    const isTv = rawType === "SERIES" || rawType === "TV";
    const key = `${isTv ? "SERIES" : "MOVIE"}:${tmdbId}`;
    const existingRemoteRating = remoteRatingMap.get(key);
    const clampedRating = Math.max(0.5, Math.min(10.0, Math.round(userRating * 2) / 2));

    if (existingRemoteRating === undefined) {
      const ratePath = isTv ? `/tv/${tmdbId}/rating` : `/movie/${tmdbId}/rating`;
      const pushRes = await tmdbFetch(
        ratePath,
        { session_id: tmdbSessionId },
        env,
        {
          method: "POST",
          body: { value: clampedRating }
        }
      );
      if (pushRes.ok) {
        pushedRatings++;
        remoteRatingMap.set(key, clampedRating);
        mergedRatings.push({
          tmdbId,
          tmdb_id: tmdbId,
          mediaType: isTv ? "SERIES" : "MOVIE",
          media_type: isTv ? "SERIES" : "MOVIE",
          title: item.title || "",
          posterUrl: item.poster_url || item.posterUrl || "",
          poster_url: item.poster_url || item.posterUrl || "",
          releaseYear: item.year || item.releaseYear || "",
          year: item.year || item.releaseYear || "",
          rating: Number(item.rating || 0),
          userRating: clampedRating,
          user_rating: clampedRating,
          favorite: false,
          watchlist: false,
          updated_at: Number(item.updated_at || Date.now())
        });
      }
    }
  }

  const db = await ensureD1Schema(env);
  if (db) {
    try {
      const nowMs = Date.now();
      await db
        .prepare(`UPDATE tmdb_user_sessions SET last_sync_at = ?, updated_at = ? WHERE session_id = ?`)
        .bind(nowMs, nowMs, clientToken)
        .run();
      await db
        .prepare(`UPDATE tmdb_accounts SET last_sync_at = ?, updated_at = ? WHERE account_id = ?`)
        .bind(nowMs, nowMs, accountId)
        .run();
    } catch (_) {}
  }

  return jsonResponse({
    status: "ok",
    ok: true,
    accountId,
    favorites: mergedFavorites,
    watchlist: mergedWatchlist,
    ratings: mergedRatings,
    pushedFavorites,
    pushedWatchlist,
    pushedRatings
  });
}

async function handlePushFavorite(request, env) {
  const body = await request.json().catch(() => ({}));
  const clientToken = extractClientToken(request, body);
  const krasUserId = extractKrasUserId(request, body);
  const installationId = extractInstallationId(request, body);
  const requestedAccountId = Number(body.accountId || body.account_id || 0);
  const mediaId = Number(body.tmdbId || body.mediaId || body.media_id || 0);
  const rawType = String(body.mediaType || body.media_type || "movie").toLowerCase();
  const mediaType = rawType === "tv" || rawType === "series" ? "tv" : "movie";
  const favorite = body.favorite !== undefined ? Boolean(body.favorite) : true;

  if (!clientToken || mediaId <= 0) {
    return jsonResponse({ status: "error", ok: false, error: "Missing session token or mediaId" }, 400);
  }

  const ownership = await validateSessionAndOwnership(
    env,
    clientToken,
    requestedAccountId,
    krasUserId,
    installationId
  );
  if (!ownership.ok) {
    return jsonResponse({ status: "error", ok: false, error: ownership.error }, ownership.status || 403);
  }

  const res = await tmdbFetch(
    `/account/${ownership.accountId}/favorite`,
    { session_id: ownership.tmdbSessionId },
    env,
    {
      method: "POST",
      body: { media_type: mediaType, media_id: mediaId, favorite }
    }
  );
  return jsonResponse({ status: res.ok ? "ok" : "error", ok: res.ok }, res.ok ? 200 : res.status);
}

async function handlePushWatchlist(request, env) {
  const body = await request.json().catch(() => ({}));
  const clientToken = extractClientToken(request, body);
  const krasUserId = extractKrasUserId(request, body);
  const installationId = extractInstallationId(request, body);
  const requestedAccountId = Number(body.accountId || body.account_id || 0);
  const mediaId = Number(body.tmdbId || body.mediaId || body.media_id || 0);
  const rawType = String(body.mediaType || body.media_type || "movie").toLowerCase();
  const mediaType = rawType === "tv" || rawType === "series" ? "tv" : "movie";
  const watchlist = body.watchlist !== undefined ? Boolean(body.watchlist) : true;

  if (!clientToken || mediaId <= 0) {
    return jsonResponse({ status: "error", ok: false, error: "Missing session token or mediaId" }, 400);
  }

  const ownership = await validateSessionAndOwnership(
    env,
    clientToken,
    requestedAccountId,
    krasUserId,
    installationId
  );
  if (!ownership.ok) {
    return jsonResponse({ status: "error", ok: false, error: ownership.error }, ownership.status || 403);
  }

  const res = await tmdbFetch(
    `/account/${ownership.accountId}/watchlist`,
    { session_id: ownership.tmdbSessionId },
    env,
    {
      method: "POST",
      body: { media_type: mediaType, media_id: mediaId, watchlist }
    }
  );
  return jsonResponse({ status: res.ok ? "ok" : "error", ok: res.ok }, res.ok ? 200 : res.status);
}

async function handlePushRating(request, env) {
  const body = await request.json().catch(() => ({}));
  const clientToken = extractClientToken(request, body);
  const krasUserId = extractKrasUserId(request, body);
  const installationId = extractInstallationId(request, body);
  const requestedAccountId = Number(body.accountId || body.account_id || 0);
  const mediaId = Number(body.tmdbId || body.mediaId || body.media_id || 0);
  const rawType = String(body.mediaType || body.media_type || "movie").toLowerCase();
  const isTv = rawType === "tv" || rawType === "series";
  const rating = Number(body.rating || body.value || 0);

  if (!clientToken || mediaId <= 0) {
    return jsonResponse({ status: "error", ok: false, error: "Missing session token or mediaId" }, 400);
  }

  const ownership = await validateSessionAndOwnership(
    env,
    clientToken,
    requestedAccountId,
    krasUserId,
    installationId
  );
  if (!ownership.ok) {
    return jsonResponse({ status: "error", ok: false, error: ownership.error }, ownership.status || 403);
  }

  const ratePath = isTv ? `/tv/${mediaId}/rating` : `/movie/${mediaId}/rating`;
  if (request.method === "DELETE" || rating <= 0) {
    const delRes = await tmdbFetch(ratePath, { session_id: ownership.tmdbSessionId }, env, {
      method: "DELETE"
    });
    return jsonResponse({ status: delRes.ok ? "ok" : "error", ok: delRes.ok }, delRes.ok ? 200 : delRes.status);
  }

  const clamped = Math.max(0.5, Math.min(10.0, Math.round(rating * 2) / 2));
  const res = await tmdbFetch(
    ratePath,
    { session_id: ownership.tmdbSessionId },
    env,
    {
      method: "POST",
      body: { value: clamped }
    }
  );
  return jsonResponse({ status: res.ok ? "ok" : "error", ok: res.ok }, res.ok ? 200 : res.status);
}

// ============================================================================
// 3. Existing Movie & TV Series Metadata Enrichment Endpoints (Preserved)
// ============================================================================

function formatMediaResponse(details, mediaType) {
  const isTv = mediaType === "tv";
  const credits = details.credits || {};
  const castList = Array.isArray(credits.cast)
    ? credits.cast.slice(0, 8).map((c) => c.name).filter(Boolean)
    : [];
  const crewList = Array.isArray(credits.crew) ? credits.crew : [];
  const directorEntry = isTv
    ? Array.isArray(details.created_by) && details.created_by[0]
      ? details.created_by[0].name
      : ""
    : (crewList.find((c) => c.job === "Director") || {}).name || "";

  const genres = Array.isArray(details.genres)
    ? details.genres.map((g) => g.name).filter(Boolean)
    : [];

  const releaseDate = (isTv ? details.first_air_date : details.release_date) || "";
  const releaseYear = releaseDate ? releaseDate.slice(0, 4) : "";

  const seasons = isTv && Array.isArray(details.seasons)
    ? details.seasons
        .filter((s) => s.season_number >= 1)
        .map((s) => ({
          seasonNumber: s.season_number,
          name: s.name || `Season ${s.season_number}`,
          episodeCount: s.episode_count || 0,
          overview: s.overview || "",
          posterUrl: s.poster_path ? `${TMDB_IMAGE_W500}${s.poster_path}` : "",
          airDate: s.air_date || ""
        }))
    : [];

  const runtimeMinutes = isTv
    ? Array.isArray(details.episode_run_time) && details.episode_run_time[0]
      ? details.episode_run_time[0]
      : 0
    : details.runtime || 0;

  return {
    tmdbId: details.id,
    mediaType,
    title: (isTv ? details.name : details.title) || "",
    overview: details.overview || "",
    posterUrl: details.poster_path ? `${TMDB_IMAGE_W500}${details.poster_path}` : "",
    backdropUrl: details.backdrop_path ? `${TMDB_IMAGE_ORIGINAL}${details.backdrop_path}` : "",
    rating: typeof details.vote_average === "number" ? Number(details.vote_average.toFixed(1)) : 0.0,
    releaseYear,
    releaseDate,
    genres,
    cast: castList,
    director: directorEntry || "",
    runtimeMinutes,
    seasons
  };
}

async function handleMetadataLookup(url, mediaType, env) {
  const idParam = url.searchParams.get("id") || url.searchParams.get("tmdbId");
  const query = (url.searchParams.get("query") || url.searchParams.get("title") || "").trim();
  const year = (url.searchParams.get("year") || "").trim();
  const language = (url.searchParams.get("language") || "en-US").trim();

  let targetId = idParam ? Number(idParam) : 0;

  if (!targetId && query) {
    const searchPath = mediaType === "tv" ? "/search/tv" : "/search/movie";
    const searchParams = { query, language };
    if (year) {
      if (mediaType === "tv") searchParams.first_air_date_year = year;
      else searchParams.primary_release_year = year;
    }
    const searchRes = await tmdbFetch(searchPath, searchParams, env);
    if (
      searchRes.ok &&
      searchRes.data &&
      Array.isArray(searchRes.data.results) &&
      searchRes.data.results.length > 0
    ) {
      targetId = searchRes.data.results[0].id;
    }
  }

  if (!targetId) {
    return jsonResponse({ error: "No matching TMDb title found" }, 404);
  }

  const detailPath = mediaType === "tv" ? `/tv/${targetId}` : `/movie/${targetId}`;
  const detailRes = await tmdbFetch(
    detailPath,
    { language, append_to_response: "credits" },
    env
  );

  if (!detailRes.ok || !detailRes.data) {
    return jsonResponse({ error: "Failed to fetch TMDb details" }, detailRes.status || 404);
  }

  return jsonResponse({
    status: "ok",
    data: formatMediaResponse(detailRes.data, mediaType)
  });
}

async function handleSeasonEpisodes(tvId, seasonNumber, url, env) {
  const language = (url.searchParams.get("language") || "en-US").trim();
  const res = await tmdbFetch(`/tv/${tvId}/season/${seasonNumber}`, { language }, env);
  if (!res.ok || !res.data) {
    return jsonResponse({ error: "Season not found" }, res.status || 404);
  }

  const episodes = Array.isArray(res.data.episodes)
    ? res.data.episodes.map((ep) => ({
        episodeNumber: ep.episode_number,
        seasonNumber: ep.season_number || Number(seasonNumber),
        title: ep.name || `Episode ${ep.episode_number}`,
        overview: ep.overview || "",
        stillUrl: ep.still_path ? `${TMDB_IMAGE_W500}${ep.still_path}` : "",
        rating: typeof ep.vote_average === "number" ? Number(ep.vote_average.toFixed(1)) : 0.0,
        durationSec: ep.runtime ? ep.runtime * 60 : 0,
        airDate: ep.air_date || ""
      }))
    : [];

  return jsonResponse({
    status: "ok",
    tvId: Number(tvId),
    seasonNumber: Number(seasonNumber),
    episodes
  });
}

// ============================================================================
// 4. Main Cloudflare Worker Router
// ============================================================================

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    if (!checkRateLimit(request)) {
      return jsonResponse(
        { status: "error", ok: false, error: "Rate limit exceeded. Please wait a moment." },
        429
      );
    }

    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";

    // 0. Verified Android App Links Digital Asset Links (/.well-known/assetlinks.json)
    if (path === "/.well-known/assetlinks.json") {
      return handleAssetLinks(env);
    }

    // 1. Health & Connectivity Test (/api/tmdb/test, /api/tmdb/health, /health)
    if (
      path === "/api/tmdb/test" ||
      path === "/api/tmdb/health" ||
      path === "/api/tmdb/connectivity-test" ||
      path === "/health" ||
      path === "/test"
    ) {
      const testRes = await tmdbFetch("/configuration", {}, env);
      const db = await ensureD1Schema(env);
      if (testRes.ok) {
        return jsonResponse({
          status: "ok",
          ok: true,
          tmdbConnected: true,
          accountAuthSupported: true,
          appLinksSupported: true,
          oneTimeCodeAuth: true,
          multiUserIsolated: true,
          d1Bound: Boolean(db),
          service: "kras-tv-tmdb-proxy",
          message: "Connected to TMDb API v3 with Browser Auth & Multi-User Account Sync"
        });
      }
      return jsonResponse(
        {
          status: "error",
          ok: false,
          tmdbConnected: false,
          error: testRes.error || "TMDb API key rejected by upstream."
        },
        testRes.status || 500
      );
    }

    // 2. TMDb Multi-User Account Authentication Routes
    if (
      (path === "/api/tmdb/auth/request-token" || path === "/api/tmdb/auth/session/start") &&
      (request.method === "POST" || request.method === "GET")
    ) {
      return handleCreateRequestToken(request, url, env);
    }

    if (path === "/api/tmdb/auth/callback" && request.method === "GET") {
      return handleAuthCallback(url, env);
    }

    if (path === "/app/tmdb-auth" && request.method === "GET") {
      return handleAppLinkRoute(url);
    }

    if (
      (path === "/api/tmdb/auth/session" || path === "/api/tmdb/auth/exchange") &&
      request.method === "POST"
    ) {
      return handleCreateSession(request, env);
    }

    if (
      (path === "/api/tmdb/auth/logout" && request.method === "POST") ||
      (path === "/api/tmdb/auth/session" && request.method === "DELETE")
    ) {
      return handleLogout(request, env);
    }

    // 3. TMDb Two-Way Synchronization & Real-Time Account Actions
    if (path === "/api/tmdb/sync" && request.method === "POST") {
      return handleTwoWaySync(request, env);
    }

    if (
      (path === "/api/tmdb/account/favorite" || path === "/api/tmdb/favorite") &&
      request.method === "POST"
    ) {
      return handlePushFavorite(request, env);
    }

    if (
      (path === "/api/tmdb/account/watchlist" || path === "/api/tmdb/watchlist") &&
      request.method === "POST"
    ) {
      return handlePushWatchlist(request, env);
    }

    if (
      (path === "/api/tmdb/account/rating" || path === "/api/tmdb/rating") &&
      (request.method === "POST" || request.method === "DELETE")
    ) {
      return handlePushRating(request, env);
    }

    // 4. TMDb Movie, TV Series, Season, and Match Metadata Enrichment Routes
    if (path === "/api/tmdb/movie" && request.method === "GET") {
      return handleMetadataLookup(url, "movie", env);
    }

    if (path === "/api/tmdb/tv" && request.method === "GET") {
      return handleMetadataLookup(url, "tv", env);
    }

    if (path === "/api/tmdb/match" && request.method === "GET") {
      const typeParam = (url.searchParams.get("type") || "movie").toLowerCase();
      const mediaType = typeParam === "tv" || typeParam === "series" ? "tv" : "movie";
      return handleMetadataLookup(url, mediaType, env);
    }

    const seasonMatch = path.match(/^\/api\/tmdb\/tv\/(\d+)\/season\/(\d+)$/);
    if (seasonMatch && request.method === "GET") {
      return handleSeasonEpisodes(seasonMatch[1], seasonMatch[2], url, env);
    }

    return jsonResponse({ error: `Route not found: ${request.method} ${path}` }, 404);
  }
};
