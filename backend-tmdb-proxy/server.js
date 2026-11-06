/**
 * KRAS TV — Production Server-Side TMDb API v3 Backend Proxy
 *
 * Security & Architecture:
 * - Reads TMDB_API_KEY exclusively from server-side environment variables
 *   (process.env.TMDB_API_KEY or Google Cloud Secret Manager mounted secret file).
 * - Never exposes TMDB_API_KEY in client code, APK, HTTP responses, headers, or logs.
 * - Provides authentication (Bearer / X-KRAS-Proxy-Token), sliding-window IP rate limiting,
 *   strict CORS controls, input validation, and bounded in-memory TTL caching.
 * - Implements full TMDb API v3 endpoints:
 *   - GET  /health
 *   - GET  /api/tmdb/health
 *   - GET  /api/tmdb/connectivity-test
 *   - GET  /api/tmdb/search/movie
 *   - GET  /api/tmdb/search/tv
 *   - GET  /api/tmdb/movie/:movieId
 *   - GET  /api/tmdb/tv/:tvId
 *   - GET  /api/tmdb/tv/:tvId/season/:seasonNumber
 *   - GET  /api/tmdb/tv/:tvId/season/:seasonNumber/episode/:episodeNumber
 *   - GET  /api/tmdb/match
 *   - POST /api/tmdb/match
 *   - GET  /api/tmdb/movie
 *   - GET  /api/tmdb/tv
 */

require("dotenv").config();
const fs = require("fs");
const crypto = require("crypto");
const express = require("express");
const cors = require("cors");

const app = express();
app.disable("x-powered-by");
app.set("trust proxy", true);

const PORT = parseInt(process.env.PORT || "8080", 10);
const TMDB_BASE_URL = "https://api.themoviedb.org/3";
const TMDB_IMAGE_BASE_W500 = "https://image.tmdb.org/t/p/w500";
const TMDB_IMAGE_BASE_W1280 = "https://image.tmdb.org/t/p/w1280";
const TMDB_IMAGE_BASE_W185 = "https://image.tmdb.org/t/p/w185";

// ============================================================================
// 1. Bounded In-Memory TTL Cache (6-Hour TTL, Max 3,000 Entries)
// ============================================================================
const CACHE_TTL_MS = parseInt(process.env.CACHE_TTL_MS || String(6 * 60 * 60 * 1000), 10);
const MAX_CACHE_ENTRIES = parseInt(process.env.MAX_CACHE_ENTRIES || "3000", 10);
const responseCache = new Map();

function getCached(key) {
  const entry = responseCache.get(key);
  if (!entry) return null;
  if (Date.now() - entry.timestamp > CACHE_TTL_MS) {
    responseCache.delete(key);
    return null;
  }
  return entry.data;
}

function setCached(key, data) {
  if (responseCache.size >= MAX_CACHE_ENTRIES) {
    const oldestKey = responseCache.keys().next().value;
    if (oldestKey) responseCache.delete(oldestKey);
  }
  responseCache.set(key, { timestamp: Date.now(), data });
}

function clearCache() {
  responseCache.clear();
}

// ============================================================================
// 2. Server-Side Secret Resolution (Env Var or Google Cloud Secret Manager File)
// ============================================================================
function getTmdbKey() {
  let raw = (process.env.TMDB_API_KEY || "").trim();
  if (!raw && process.env.TMDB_API_KEY_FILE) {
    try {
      raw = fs.readFileSync(process.env.TMDB_API_KEY_FILE, "utf8").trim();
    } catch (_) {
      raw = "";
    }
  }
  if (!raw || raw === "your_server_side_tmdb_api_key_here" || raw === "YOUR_TMDB_API_KEY") {
    return null;
  }
  return raw;
}

function getProxyAuthToken() {
  let raw = (process.env.PROXY_AUTH_TOKEN || "").trim();
  if (!raw && process.env.PROXY_AUTH_TOKEN_FILE) {
    try {
      raw = fs.readFileSync(process.env.PROXY_AUTH_TOKEN_FILE, "utf8").trim();
    } catch (_) {
      raw = "";
    }
  }
  if (!raw || raw === "optional_shared_secret_token_for_clients") {
    return null;
  }
  return raw;
}

// ============================================================================
// 3. Security Headers & CORS Configuration
// ============================================================================
app.use((req, res, next) => {
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("X-Frame-Options", "DENY");
  res.setHeader("Referrer-Policy", "no-referrer");
  res.setHeader("X-XSS-Protection", "0");
  next();
});

const allowedOriginsEnv = (process.env.ALLOWED_ORIGINS || "*").trim();
const allowedOriginsList = allowedOriginsEnv === "*"
  ? ["*"]
  : allowedOriginsEnv.split(",").map((o) => o.trim()).filter(Boolean);

app.use(
  cors({
    origin(origin, callback) {
      // Allow requests with no Origin header (such as KRAS TV Android OkHttpClient)
      if (!origin) return callback(null, true);
      if (allowedOriginsList.includes("*") || allowedOriginsList.includes(origin)) {
        return callback(null, true);
      }
      const err = new Error("Origin not allowed by CORS policy.");
      err.statusCode = 403;
      return callback(err);
    },
    methods: ["GET", "POST", "DELETE", "OPTIONS"],
    allowedHeaders: [
      "Accept",
      "Content-Type",
      "Authorization",
      "X-KRAS-Proxy-Token",
      "X-TMDb-Session-Id",
      "X-Client-App"
    ],
    maxAge: 86400
  })
);

app.use(express.json({ limit: "64kb" }));

// ============================================================================
// 4. Sliding-Window IP Rate Limiter
// ============================================================================
const RATE_LIMIT_WINDOW_MS = parseInt(process.env.RATE_LIMIT_WINDOW_MS || "60000", 10);
const RATE_LIMIT_MAX_REQUESTS = parseInt(process.env.RATE_LIMIT_MAX_REQUESTS || "120", 10);
const ipRequestCounters = new Map();

function getClientIp(req) {
  const forwarded = req.headers["x-forwarded-for"];
  if (typeof forwarded === "string" && forwarded.trim().length > 0) {
    return forwarded.split(",")[0].trim();
  }
  return req.ip || (req.socket && req.socket.remoteAddress) || "unknown";
}

function rateLimitMiddleware(req, res, next) {
  const now = Date.now();
  const ip = getClientIp(req);
  let bucket = ipRequestCounters.get(ip);

  if (!bucket || now >= bucket.resetAt) {
    bucket = { count: 0, resetAt: now + RATE_LIMIT_WINDOW_MS };
    ipRequestCounters.set(ip, bucket);
  }

  bucket.count += 1;

  // Bound map size by pruning expired buckets if map grows large
  if (ipRequestCounters.size > 5000) {
    for (const [k, v] of ipRequestCounters.entries()) {
      if (now >= v.resetAt) ipRequestCounters.delete(k);
    }
  }

  const remaining = Math.max(0, RATE_LIMIT_MAX_REQUESTS - bucket.count);
  const resetSeconds = Math.ceil((bucket.resetAt - now) / 1000);

  res.setHeader("X-RateLimit-Limit", String(RATE_LIMIT_MAX_REQUESTS));
  res.setHeader("X-RateLimit-Remaining", String(remaining));
  res.setHeader("X-RateLimit-Reset", String(resetSeconds));

  if (bucket.count > RATE_LIMIT_MAX_REQUESTS) {
    res.setHeader("Retry-After", String(resetSeconds));
    return res.status(429).json({
      ok: false,
      error: "Too many requests. Please slow down and retry shortly.",
      retryAfterSeconds: resetSeconds
    });
  }

  return next();
}

app.use(rateLimitMiddleware);

// ============================================================================
// 5. Authentication Middleware (Constant-Time Token Verification)
// ============================================================================
function safeCompareStrings(a, b) {
  const bufA = Buffer.from(String(a || ""), "utf8");
  const bufB = Buffer.from(String(b || ""), "utf8");
  if (bufA.length !== bufB.length) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

function extractClientToken(req) {
  const headerToken = req.headers["x-kras-proxy-token"];
  if (typeof headerToken === "string" && headerToken.trim()) {
    return headerToken.trim();
  }
  const authHeader = req.headers["authorization"];
  if (typeof authHeader === "string" && authHeader.toLowerCase().startsWith("bearer ")) {
    return authHeader.slice(7).trim();
  }
  if (typeof req.query.token === "string" && req.query.token.trim()) {
    return req.query.token.trim();
  }
  return "";
}

function requireProxyAuth(req, res, next) {
  const configuredToken = getProxyAuthToken();
  if (!configuredToken) {
    // When PROXY_AUTH_TOKEN is not configured, allow requests from KRAS TV clients
    return next();
  }

  const providedToken = extractClientToken(req);
  if (!providedToken || !safeCompareStrings(providedToken, configuredToken)) {
    return res.status(401).json({
      ok: false,
      status: "unauthorized",
      tmdbConnected: false,
      error: "Unauthorized: valid X-KRAS-Proxy-Token or Authorization Bearer token is required."
    });
  }

  return next();
}

// ============================================================================
// 6. Input Validation & IPTV Title Normalization Utilities
// ============================================================================
const LANGUAGE_REGEX = /^[a-z]{2}(-[A-Z]{2})?$/;
const YEAR_REGEX = /^(18[89]\d|19\d\d|20[0-3]\d)$/;
const CONTROL_CHARS_REGEX = /[\u0000-\u001F\u007F]/g;

function sanitizeTitleInput(raw) {
  if (typeof raw !== "string") return "";
  return raw.replace(CONTROL_CHARS_REGEX, " ").trim();
}

function validateLanguage(raw) {
  const lang = typeof raw === "string" && raw.trim() ? raw.trim() : "en-US";
  if (!LANGUAGE_REGEX.test(lang)) {
    return { valid: false, error: "Invalid 'language' format. Expected BCP-47 code such as 'en-US' or 'ar-SA'." };
  }
  return { valid: true, value: lang };
}

function validateOptionalYear(raw) {
  if (raw === undefined || raw === null || String(raw).trim() === "") {
    return { valid: true, value: "" };
  }
  const yr = String(raw).trim();
  if (!YEAR_REGEX.test(yr)) {
    return { valid: false, error: "Invalid 'year' parameter. Expected a 4-digit release year between 1888 and 2039." };
  }
  return { valid: true, value: yr };
}

function validateOptionalPositiveInt(raw, fieldName, maxVal = 99999999) {
  if (raw === undefined || raw === null || String(raw).trim() === "" || String(raw).trim() === "0") {
    return { valid: true, value: 0 };
  }
  const str = String(raw).trim();
  if (!/^\d+$/.test(str)) {
    return { valid: false, error: `Invalid '${fieldName}': must be a positive integer.` };
  }
  const num = parseInt(str, 10);
  if (!(num >= 1 && num <= maxVal)) {
    return { valid: false, error: `Invalid '${fieldName}': must be between 1 and ${maxVal}.` };
  }
  return { valid: true, value: num };
}

function validateRequiredPositiveInt(raw, fieldName, minVal = 1, maxVal = 99999999) {
  const str = String(raw === undefined || raw === null ? "" : raw).trim();
  if (!/^\d+$/.test(str)) {
    return { valid: false, error: `Parameter '${fieldName}' must be an integer between ${minVal} and ${maxVal}.` };
  }
  const num = parseInt(str, 10);
  if (num < minVal || num > maxVal) {
    return { valid: false, error: `Parameter '${fieldName}' must be between ${minVal} and ${maxVal}.` };
  }
  return { valid: true, value: num };
}

const LEADING_PREFIX_REGEX =
  /^(?:[A-Z]{2,4}\s*\|\s*|[A-Z]{2,3}\s+-\s+|(?:EN|US|UK|AR|FR|DE|ES|IT|TR|RU|NL|PT|BR|IN|CA|AU|VIP|VOD)\s*:\s*)+/i;
const BRACKET_YEAR_REGEX = /[(\[](19\d{2}|20[0-3]\d)[)\]]/;
const TRAILING_YEAR_REGEX = /\b(19\d{2}|20[0-3]\d)\s*$/;
const SEASON_EPISODE_SUFFIX_REGEX = /\b(?:S\d{1,2}\s*E\d{1,3}|S\d{1,2}|Season\s*\d{1,2}|Episode\s*\d{1,3})\b.*$/i;
const QUALITY_CODEC_REGEX = /\b(?:8K|4K|UHD|2160p|1080p|FHD|Full\s*HD|720p|HD|480p|SD|HEVC|H\.?265|H\.?264|x264|x265|HDR10\+?|HDR|DV|Dolby\s*Vision|Atmos|50\s*FPS|60\s*FPS|BluRay|WEB-?DL|WEBRip|BRRip|CAM|MULTI|SUB|DUB|VOSTFR)\b/gi;
const BRACKETS_NOISE_REGEX = /\[[^\]]*\]|\([^)]*\)/g;

/**
 * Cleans raw IPTV (Xtream Codes / M3U) titles into a clean TMDb search query and optional release year.
 */
function cleanIptvTitle(rawTitle) {
  let working = sanitizeTitleInput(rawTitle);
  if (!working) return { cleanTitle: "", extractedYear: "" };

  let extractedYear = "";
  const bracketYear = working.match(BRACKET_YEAR_REGEX);
  if (bracketYear) {
    extractedYear = bracketYear[1];
  }

  working = working.replace(LEADING_PREFIX_REGEX, "");
  working = working.replace(SEASON_EPISODE_SUFFIX_REGEX, "");
  working = working.replace(BRACKETS_NOISE_REGEX, " ");
  working = working.replace(QUALITY_CODEC_REGEX, " ");

  const trimmedBeforeYear = working.trim().replace(/[-|:.]+$/, "").trim();
  if (!extractedYear) {
    const trailingMatch = trimmedBeforeYear.match(TRAILING_YEAR_REGEX);
    if (trailingMatch) {
      const candidateYear = trailingMatch[1];
      const withoutYear = trimmedBeforeYear.slice(0, -candidateYear.length).trim();
      if (withoutYear.length > 0) {
        extractedYear = candidateYear;
        working = withoutYear;
      }
    }
  } else if (trimmedBeforeYear.endsWith(extractedYear)) {
    working = trimmedBeforeYear.slice(0, -extractedYear.length);
  }

  const cleanTitle = working
    .replace(/\s{2,}/g, " ")
    .trim()
    .replace(/^[-|:._]+|[-|:._]+$/g, "")
    .trim();

  return {
    cleanTitle: cleanTitle || sanitizeTitleInput(rawTitle),
    extractedYear
  };
}

function normalizeForComparison(str) {
  return String(str || "")
    .toLowerCase()
    .replace(/[^a-z0-9\u0600-\u06FF]+/g, " ")
    .trim();
}

/**
 * Ranks TMDb search candidates by exact/prefix title similarity, release year match, and popularity.
 */
function selectBestTmdbMatch(results, queryTitle, targetYear, isTv = false) {
  if (!Array.isArray(results) || results.length === 0) return null;
  const normQuery = normalizeForComparison(queryTitle);

  let bestItem = results[0];
  let bestScore = -Infinity;

  for (const item of results) {
    let score = 0;
    const primaryTitle = isTv ? item.name : item.title;
    const originalTitle = isTv ? item.original_name : item.original_title;
    const normPrimary = normalizeForComparison(primaryTitle);
    const normOriginal = normalizeForComparison(originalTitle);

    if (normPrimary === normQuery || normOriginal === normQuery) {
      score += 100;
    } else if (normPrimary.startsWith(normQuery) || normOriginal.startsWith(normQuery)) {
      score += 55;
    } else if (normPrimary.includes(normQuery) || normQuery.includes(normPrimary)) {
      score += 30;
    }

    const releaseDate = (isTv ? item.first_air_date : item.release_date) || "";
    const itemYear = releaseDate.slice(0, 4);
    if (targetYear && itemYear) {
      if (itemYear === targetYear) {
        score += 60;
      } else if (Math.abs(parseInt(itemYear, 10) - parseInt(targetYear, 10)) === 1) {
        score += 20;
      } else {
        score -= 15;
      }
    }

    if (item.poster_path) score += 10;
    if (item.overview) score += 5;
    score += Math.min(20, (item.vote_count || 0) / 250);

    if (score > bestScore) {
      bestScore = score;
      bestItem = item;
    }
  }

  return bestItem;
}

// ============================================================================
// 7. Upstream TMDb API v3 Fetch Helper
// ============================================================================
async function tmdbRequest(path, { method = "GET", queryParams = {}, body = null } = {}) {
  const apiKey = getTmdbKey();
  if (!apiKey) {
    const err = new Error("TMDB_API_KEY is not configured on the backend server.");
    err.statusCode = 503;
    err.code = "SERVER_KEY_MISSING";
    throw err;
  }

  const url = new URL(`${TMDB_BASE_URL}${path}`);
  const headers = {
    Accept: "application/json",
    "User-Agent": "KRAS-TV-TMDb-Proxy/1.0"
  };

  if (body !== null && body !== undefined) {
    headers["Content-Type"] = "application/json;charset=utf-8";
  }

  // Supports both TMDb v3 API Key (32-char hex) and v4 JWT Read Access Token
  if (apiKey.startsWith("eyJ")) {
    headers["Authorization"] = `Bearer ${apiKey}`;
  } else {
    url.searchParams.set("api_key", apiKey);
  }

  for (const [k, v] of Object.entries(queryParams)) {
    if (v !== undefined && v !== null && String(v).trim() !== "") {
      url.searchParams.set(k, String(v).trim());
    }
  }

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 14000);

  try {
    const response = await fetch(url.toString(), {
      method,
      headers,
      body: body !== null && body !== undefined ? JSON.stringify(body) : undefined,
      signal: controller.signal
    });

    const data = await response.json().catch(() => ({}));

    if (!response.ok) {
      const upstreamMsg = data && data.status_message ? data.status_message : "";
      const err = new Error(
        response.status === 401 || response.status === 403
          ? upstreamMsg || `TMDb authorization rejected (HTTP ${response.status}).`
          : upstreamMsg || `TMDb upstream returned HTTP ${response.status}.`
      );
      err.statusCode = response.status;
      err.tmdbStatusCode = data && data.status_code ? data.status_code : 0;
      throw err;
    }

    return data;
  } finally {
    clearTimeout(timeoutId);
  }
}

async function tmdbFetch(path, queryParams = {}) {
  return tmdbRequest(path, { method: "GET", queryParams });
}

function buildPosterUrl(path) {
  return path ? `${TMDB_IMAGE_BASE_W500}${path}` : "";
}

function buildBackdropUrl(path) {
  return path ? `${TMDB_IMAGE_BASE_W1280}${path}` : "";
}

function buildProfileUrl(path) {
  return path ? `${TMDB_IMAGE_BASE_W185}${path}` : "";
}

// ============================================================================
// 8. Response Formatters (Compatible with KRAS TV Android Client & REST API)
// ============================================================================
function formatMovieDetailsPayload(details, fallbackTitle = "", fallbackYear = "") {
  const genresArray = Array.isArray(details.genres)
    ? details.genres.map((g) => g.name).filter(Boolean)
    : [];
  const castDetailed = details.credits && Array.isArray(details.credits.cast)
    ? details.credits.cast.slice(0, 10).map((c) => ({
        id: c.id || 0,
        name: c.name || "",
        character: c.character || "",
        profileUrl: buildProfileUrl(c.profile_path)
      }))
    : [];
  const castNames = castDetailed.map((c) => c.name).filter(Boolean);
  const directorList = details.credits && Array.isArray(details.credits.crew)
    ? details.credits.crew
        .filter((c) => c.job === "Director")
        .map((c) => c.name)
        .filter(Boolean)
    : [];

  const releaseDate = details.release_date || "";
  const releaseYear = releaseDate.length >= 4 ? releaseDate.substring(0, 4) : fallbackYear;

  return {
    ok: true,
    mediaType: "movie",
    tmdbId: details.id || 0,
    imdbId: details.imdb_id || (details.external_ids && details.external_ids.imdb_id) || "",
    title: details.title || details.original_title || fallbackTitle,
    originalTitle: details.original_title || "",
    tagline: details.tagline || "",
    overview: details.overview || "",
    posterUrl: buildPosterUrl(details.poster_path),
    backdropUrl: buildBackdropUrl(details.backdrop_path),
    rating: typeof details.vote_average === "number" ? Number(details.vote_average.toFixed(1)) : 0.0,
    voteCount: details.vote_count || 0,
    releaseYear: releaseYear || "",
    year: releaseYear || "",
    releaseDate,
    genres: genresArray.join(", "),
    genreList: genresArray,
    cast: castNames.join(", "),
    castList: castDetailed,
    director: directorList.join(", "),
    runtimeMinutes: details.runtime || 0,
    status: details.status || ""
  };
}

function formatTvDetailsPayload(details, fallbackTitle = "", fallbackYear = "") {
  const genresArray = Array.isArray(details.genres)
    ? details.genres.map((g) => g.name).filter(Boolean)
    : [];
  const castDetailed = details.credits && Array.isArray(details.credits.cast)
    ? details.credits.cast.slice(0, 10).map((c) => ({
        id: c.id || 0,
        name: c.name || "",
        character: c.character || "",
        profileUrl: buildProfileUrl(c.profile_path)
      }))
    : [];
  const castNames = castDetailed.map((c) => c.name).filter(Boolean);
  const creators = Array.isArray(details.created_by)
    ? details.created_by.map((c) => c.name).filter(Boolean)
    : [];

  const firstAirDate = details.first_air_date || "";
  const releaseYear = firstAirDate.length >= 4 ? firstAirDate.substring(0, 4) : fallbackYear;

  const seasons = Array.isArray(details.seasons)
    ? details.seasons
        .filter((s) => typeof s.season_number === "number" && s.season_number >= 1)
        .map((s) => ({
          seasonNumber: s.season_number,
          name: s.name || `Season ${s.season_number}`,
          episodeCount: s.episode_count || 0,
          overview: s.overview || "",
          posterUrl: buildPosterUrl(s.poster_path),
          airDate: s.air_date || ""
        }))
    : [];

  return {
    ok: true,
    mediaType: "tv",
    tmdbId: details.id || 0,
    title: details.name || details.original_name || fallbackTitle,
    originalTitle: details.original_name || "",
    tagline: details.tagline || "",
    overview: details.overview || "",
    posterUrl: buildPosterUrl(details.poster_path),
    backdropUrl: buildBackdropUrl(details.backdrop_path),
    rating: typeof details.vote_average === "number" ? Number(details.vote_average.toFixed(1)) : 0.0,
    voteCount: details.vote_count || 0,
    releaseYear: releaseYear || "",
    year: releaseYear || "",
    releaseDate: firstAirDate,
    genres: genresArray.join(", "),
    genreList: genresArray,
    cast: castNames.join(", "),
    castList: castDetailed,
    director: creators.join(", "),
    numberOfSeasons: details.number_of_seasons || seasons.length,
    numberOfEpisodes: details.number_of_episodes || 0,
    status: details.status || "",
    seasons
  };
}

function formatSeasonDetailsPayload(tvId, seasonData, defaultSeasonNumber) {
  const episodes = Array.isArray(seasonData.episodes)
    ? seasonData.episodes.map((ep) => ({
        id: ep.id || 0,
        episodeNumber: ep.episode_number || 1,
        seasonNumber: ep.season_number || defaultSeasonNumber,
        title: ep.name || `Episode ${ep.episode_number || 1}`,
        overview: ep.overview || "",
        stillUrl: buildPosterUrl(ep.still_path),
        rating: typeof ep.vote_average === "number" ? Number(ep.vote_average.toFixed(1)) : 0.0,
        durationSec: ep.runtime ? ep.runtime * 60 : 0,
        runtimeMinutes: ep.runtime || 0,
        airDate: ep.air_date || ""
      }))
    : [];

  return {
    ok: true,
    tmdbId: tvId,
    seasonNumber: seasonData.season_number ?? defaultSeasonNumber,
    name: seasonData.name || `Season ${defaultSeasonNumber}`,
    overview: seasonData.overview || "",
    posterUrl: buildPosterUrl(seasonData.poster_path),
    airDate: seasonData.air_date || "",
    episodeCount: episodes.length,
    episodes
  };
}

// ============================================================================
// 9. Health & Live TMDb Connectivity Test Endpoints
// ============================================================================
async function handleHealthAndConnectivityCheck(req, res) {
  const apiKey = getTmdbKey();
  if (!apiKey) {
    return res.status(503).json({
      ok: false,
      status: "unconfigured",
      service: "kras-tv-tmdb-proxy",
      tmdbApiVersion: "v3",
      tmdbConnected: false,
      authRequired: Boolean(getProxyAuthToken()),
      message: "TMDB_API_KEY environment variable is not configured on the server."
    });
  }

  try {
    const config = await tmdbFetch("/configuration");
    const secureBaseUrl =
      (config && config.images && config.images.secure_base_url) || "https://image.tmdb.org/t/p/";
    const hasImagesConfig = Boolean(config && config.images && config.images.secure_base_url);

    return res.status(200).json({
      ok: hasImagesConfig,
      status: hasImagesConfig ? "connected" : "degraded",
      service: "kras-tv-tmdb-proxy",
      tmdbApiVersion: "v3",
      tmdbConnected: hasImagesConfig,
      imageBaseUrl: secureBaseUrl,
      authRequired: Boolean(getProxyAuthToken()),
      cacheEntries: responseCache.size,
      message: "Connected to TMDb API v3 via secure server-side proxy."
    });
  } catch (err) {
    const statusCode = err.statusCode || 502;
    return res.status(statusCode).json({
      ok: false,
      status: "error",
      service: "kras-tv-tmdb-proxy",
      tmdbApiVersion: "v3",
      tmdbConnected: false,
      authRequired: Boolean(getProxyAuthToken()),
      message: err.message || "Failed to reach TMDb API v3."
    });
  }
}

// Cloud Run root /health endpoint & authenticated /api/tmdb/health + /api/tmdb/connectivity-test
app.get("/health", handleHealthAndConnectivityCheck);
app.get("/api/tmdb/health", requireProxyAuth, handleHealthAndConnectivityCheck);
app.get("/api/tmdb/connectivity-test", requireProxyAuth, handleHealthAndConnectivityCheck);

// ============================================================================
// 10. Movie Search & TV Search Endpoints
// ============================================================================

/**
 * GET /api/tmdb/search/movie?query=Dune&year=2024&language=en-US&page=1
 */
app.get("/api/tmdb/search/movie", requireProxyAuth, async (req, res) => {
  const rawQuery = sanitizeTitleInput(req.query.query || req.query.title || "");
  if (!rawQuery || rawQuery.length > 200) {
    return res.status(400).json({
      ok: false,
      error: "Query parameter 'query' (1-200 characters) is required."
    });
  }

  const yearValidation = validateOptionalYear(req.query.year);
  if (!yearValidation.valid) {
    return res.status(400).json({ ok: false, error: yearValidation.error });
  }

  const langValidation = validateLanguage(req.query.language);
  if (!langValidation.valid) {
    return res.status(400).json({ ok: false, error: langValidation.error });
  }

  const pageValidation = validateOptionalPositiveInt(req.query.page, "page", 500);
  if (!pageValidation.valid) {
    return res.status(400).json({ ok: false, error: pageValidation.error });
  }
  const page = pageValidation.value || 1;

  const cleaned = cleanIptvTitle(rawQuery);
  const searchQuery = cleaned.cleanTitle || rawQuery;
  const effectiveYear = yearValidation.value || cleaned.extractedYear;

  const cacheKey = `search_movie:${searchQuery.toLowerCase()}:${effectiveYear}:${langValidation.value}:${page}`;
  const cached = getCached(cacheKey);
  if (cached) return res.json(cached);

  try {
    const data = await tmdbFetch("/search/movie", {
      query: searchQuery,
      year: effectiveYear || undefined,
      include_adult: "false",
      language: langValidation.value,
      page
    });

    const results = Array.isArray(data.results)
      ? data.results.map((m) => ({
          tmdbId: m.id,
          title: m.title || m.original_title || "",
          originalTitle: m.original_title || "",
          overview: m.overview || "",
          posterUrl: buildPosterUrl(m.poster_path),
          backdropUrl: buildBackdropUrl(m.backdrop_path),
          rating: typeof m.vote_average === "number" ? Number(m.vote_average.toFixed(1)) : 0.0,
          releaseDate: m.release_date || "",
          releaseYear: (m.release_date || "").slice(0, 4)
        }))
      : [];

    const payload = {
      ok: true,
      query: searchQuery,
      year: effectiveYear,
      page: data.page || page,
      totalResults: data.total_results || results.length,
      totalPages: data.total_pages || 1,
      results
    };

    setCached(cacheKey, payload);
    return res.json(payload);
  } catch (err) {
    return res.status(err.statusCode || 500).json({
      ok: false,
      error: err.message || "Failed to search movies on TMDb."
    });
  }
});

/**
 * GET /api/tmdb/search/tv?query=Breaking+Bad&year=2008&language=en-US&page=1
 */
app.get("/api/tmdb/search/tv", requireProxyAuth, async (req, res) => {
  const rawQuery = sanitizeTitleInput(req.query.query || req.query.title || "");
  if (!rawQuery || rawQuery.length > 200) {
    return res.status(400).json({
      ok: false,
      error: "Query parameter 'query' (1-200 characters) is required."
    });
  }

  const yearValidation = validateOptionalYear(req.query.year);
  if (!yearValidation.valid) {
    return res.status(400).json({ ok: false, error: yearValidation.error });
  }

  const langValidation = validateLanguage(req.query.language);
  if (!langValidation.valid) {
    return res.status(400).json({ ok: false, error: langValidation.error });
  }

  const pageValidation = validateOptionalPositiveInt(req.query.page, "page", 500);
  if (!pageValidation.valid) {
    return res.status(400).json({ ok: false, error: pageValidation.error });
  }
  const page = pageValidation.value || 1;

  const cleaned = cleanIptvTitle(rawQuery);
  const searchQuery = cleaned.cleanTitle || rawQuery;
  const effectiveYear = yearValidation.value || cleaned.extractedYear;

  const cacheKey = `search_tv:${searchQuery.toLowerCase()}:${effectiveYear}:${langValidation.value}:${page}`;
  const cached = getCached(cacheKey);
  if (cached) return res.json(cached);

  try {
    const data = await tmdbFetch("/search/tv", {
      query: searchQuery,
      first_air_date_year: effectiveYear || undefined,
      include_adult: "false",
      language: langValidation.value,
      page
    });

    const results = Array.isArray(data.results)
      ? data.results.map((t) => ({
          tmdbId: t.id,
          title: t.name || t.original_name || "",
          originalTitle: t.original_name || "",
          overview: t.overview || "",
          posterUrl: buildPosterUrl(t.poster_path),
          backdropUrl: buildBackdropUrl(t.backdrop_path),
          rating: typeof t.vote_average === "number" ? Number(t.vote_average.toFixed(1)) : 0.0,
          releaseDate: t.first_air_date || "",
          releaseYear: (t.first_air_date || "").slice(0, 4)
        }))
      : [];

    const payload = {
      ok: true,
      query: searchQuery,
      year: effectiveYear,
      page: data.page || page,
      totalResults: data.total_results || results.length,
      totalPages: data.total_pages || 1,
      results
    };

    setCached(cacheKey, payload);
    return res.json(payload);
  } catch (err) {
    return res.status(err.statusCode || 500).json({
      ok: false,
      error: err.message || "Failed to search TV series on TMDb."
    });
  }
});

// ============================================================================
// 11. Direct Movie Details, TV Details, Seasons, and Episodes Endpoints
// ============================================================================

/**
 * GET /api/tmdb/movie/:movieId?language=en-US
 */
app.get("/api/tmdb/movie/:movieId", requireProxyAuth, async (req, res) => {
  const idValidation = validateRequiredPositiveInt(req.params.movieId, "movieId");
  if (!idValidation.valid) {
    return res.status(400).json({ ok: false, error: idValidation.error });
  }

  const langValidation = validateLanguage(req.query.language);
  if (!langValidation.valid) {
    return res.status(400).json({ ok: false, error: langValidation.error });
  }

  const movieId = idValidation.value;
  const language = langValidation.value;
  const cacheKey = `movie_id:${movieId}:${language}`;
  const cached = getCached(cacheKey);
  if (cached) return res.json(cached);

  try {
    const details = await tmdbFetch(`/movie/${movieId}`, {
      append_to_response: "credits,external_ids",
      language
    });
    const payload = formatMovieDetailsPayload(details);
    setCached(cacheKey, payload);
    return res.json(payload);
  } catch (err) {
    return res.status(err.statusCode || 500).json({
      ok: false,
      error: err.message || "Failed to fetch movie details from TMDb."
    });
  }
});

/**
 * GET /api/tmdb/tv/:tvId?language=en-US
 */
app.get("/api/tmdb/tv/:tvId", requireProxyAuth, async (req, res) => {
  const idValidation = validateRequiredPositiveInt(req.params.tvId, "tvId");
  if (!idValidation.valid) {
    return res.status(400).json({ ok: false, error: idValidation.error });
  }

  const langValidation = validateLanguage(req.query.language);
  if (!langValidation.valid) {
    return res.status(400).json({ ok: false, error: langValidation.error });
  }

  const tvId = idValidation.value;
  const language = langValidation.value;
  const cacheKey = `tv_id:${tvId}:${language}`;
  const cached = getCached(cacheKey);
  if (cached) return res.json(cached);

  try {
    const details = await tmdbFetch(`/tv/${tvId}`, {
      append_to_response: "credits,external_ids",
      language
    });
    const payload = formatTvDetailsPayload(details);
    setCached(cacheKey, payload);
    return res.json(payload);
  } catch (err) {
    return res.status(err.statusCode || 500).json({
      ok: false,
      error: err.message || "Failed to fetch TV series details from TMDb."
    });
  }
});

/**
 * GET /api/tmdb/tv/:tvId/season/:seasonNumber?language=en-US
 */
app.get("/api/tmdb/tv/:tvId/season/:seasonNumber", requireProxyAuth, async (req, res) => {
  const tvIdValidation = validateRequiredPositiveInt(req.params.tvId, "tvId");
  if (!tvIdValidation.valid) {
    return res.status(400).json({ ok: false, error: tvIdValidation.error });
  }

  const seasonValidation = validateRequiredPositiveInt(req.params.seasonNumber, "seasonNumber", 0, 200);
  if (!seasonValidation.valid) {
    return res.status(400).json({ ok: false, error: seasonValidation.error });
  }

  const langValidation = validateLanguage(req.query.language);
  if (!langValidation.valid) {
    return res.status(400).json({ ok: false, error: langValidation.error });
  }

  const tvId = tvIdValidation.value;
  const seasonNumber = seasonValidation.value;
  const language = langValidation.value;

  const cacheKey = `tv_season:${tvId}:${seasonNumber}:${language}`;
  const cached = getCached(cacheKey);
  if (cached) return res.json(cached);

  try {
    const seasonData = await tmdbFetch(`/tv/${tvId}/season/${seasonNumber}`, { language });
    const payload = formatSeasonDetailsPayload(tvId, seasonData, seasonNumber);
    setCached(cacheKey, payload);
    return res.json(payload);
  } catch (err) {
    return res.status(err.statusCode || 500).json({
      ok: false,
      error: err.message || "Failed to fetch season episodes from TMDb."
    });
  }
});

/**
 * GET /api/tmdb/tv/:tvId/season/:seasonNumber/episode/:episodeNumber?language=en-US
 */
app.get(
  "/api/tmdb/tv/:tvId/season/:seasonNumber/episode/:episodeNumber",
  requireProxyAuth,
  async (req, res) => {
    const tvIdValidation = validateRequiredPositiveInt(req.params.tvId, "tvId");
    if (!tvIdValidation.valid) {
      return res.status(400).json({ ok: false, error: tvIdValidation.error });
    }

    const seasonValidation = validateRequiredPositiveInt(req.params.seasonNumber, "seasonNumber", 0, 200);
    if (!seasonValidation.valid) {
      return res.status(400).json({ ok: false, error: seasonValidation.error });
    }

    const epValidation = validateRequiredPositiveInt(req.params.episodeNumber, "episodeNumber", 1, 5000);
    if (!epValidation.valid) {
      return res.status(400).json({ ok: false, error: epValidation.error });
    }

    const langValidation = validateLanguage(req.query.language);
    if (!langValidation.valid) {
      return res.status(400).json({ ok: false, error: langValidation.error });
    }

    const tvId = tvIdValidation.value;
    const seasonNumber = seasonValidation.value;
    const episodeNumber = epValidation.value;
    const language = langValidation.value;

    const cacheKey = `tv_ep:${tvId}:${seasonNumber}:${episodeNumber}:${language}`;
    const cached = getCached(cacheKey);
    if (cached) return res.json(cached);

    try {
      const ep = await tmdbFetch(
        `/tv/${tvId}/season/${seasonNumber}/episode/${episodeNumber}`,
        {
          append_to_response: "credits",
          language
        }
      );

      const guestStars = Array.isArray(ep.guest_stars)
        ? ep.guest_stars.slice(0, 8).map((g) => g.name).filter(Boolean).join(", ")
        : "";
      const director = Array.isArray(ep.crew)
        ? ep.crew.filter((c) => c.job === "Director").map((c) => c.name).filter(Boolean).join(", ")
        : "";

      const payload = {
        ok: true,
        tmdbId: tvId,
        episodeId: ep.id || 0,
        seasonNumber: ep.season_number ?? seasonNumber,
        episodeNumber: ep.episode_number ?? episodeNumber,
        title: ep.name || `Episode ${episodeNumber}`,
        overview: ep.overview || "",
        stillUrl: buildPosterUrl(ep.still_path),
        rating: typeof ep.vote_average === "number" ? Number(ep.vote_average.toFixed(1)) : 0.0,
        durationSec: ep.runtime ? ep.runtime * 60 : 0,
        runtimeMinutes: ep.runtime || 0,
        airDate: ep.air_date || "",
        guestStars,
        director
      };

      setCached(cacheKey, payload);
      return res.json(payload);
    } catch (err) {
      return res.status(err.statusCode || 500).json({
        ok: false,
        error: err.message || "Failed to fetch episode details from TMDb."
      });
    }
  }
);

// ============================================================================
// 12. Smart IPTV Metadata Matching & Enrichment Endpoints
// ============================================================================

async function resolveAndFetchMovieMetadata({ rawTitle, year, tmdbId, language }) {
  const cleaned = cleanIptvTitle(rawTitle);
  const searchTitle = cleaned.cleanTitle || rawTitle;
  const effectiveYear = year || cleaned.extractedYear;

  const cacheKey = `movie_match:${tmdbId}:${searchTitle.toLowerCase()}:${effectiveYear}:${language}`;
  const cached = getCached(cacheKey);
  if (cached) return cached;

  let resolvedMovieId = tmdbId > 0 ? tmdbId : 0;

  if (!resolvedMovieId) {
    const searchData = await tmdbFetch("/search/movie", {
      query: searchTitle,
      year: effectiveYear || undefined,
      include_adult: "false",
      language
    });
    let results = Array.isArray(searchData.results) ? searchData.results : [];
    if (results.length === 0 && effectiveYear) {
      const fallbackSearch = await tmdbFetch("/search/movie", {
        query: searchTitle,
        include_adult: "false",
        language
      });
      results = Array.isArray(fallbackSearch.results) ? fallbackSearch.results : [];
    }
    const best = selectBestTmdbMatch(results, searchTitle, effectiveYear, false);
    if (!best) return null;
    resolvedMovieId = best.id;
  }

  const details = await tmdbFetch(`/movie/${resolvedMovieId}`, {
    append_to_response: "credits,external_ids",
    language
  });

  const payload = formatMovieDetailsPayload(details, searchTitle, effectiveYear);
  setCached(cacheKey, payload);
  return payload;
}

async function resolveAndFetchTvMetadata({ rawTitle, year, tmdbId, language }) {
  const cleaned = cleanIptvTitle(rawTitle);
  const searchTitle = cleaned.cleanTitle || rawTitle;
  const effectiveYear = year || cleaned.extractedYear;

  const cacheKey = `tv_match:${tmdbId}:${searchTitle.toLowerCase()}:${effectiveYear}:${language}`;
  const cached = getCached(cacheKey);
  if (cached) return cached;

  let resolvedTvId = tmdbId > 0 ? tmdbId : 0;

  if (!resolvedTvId) {
    const searchData = await tmdbFetch("/search/tv", {
      query: searchTitle,
      first_air_date_year: effectiveYear || undefined,
      include_adult: "false",
      language
    });
    let results = Array.isArray(searchData.results) ? searchData.results : [];
    if (results.length === 0 && effectiveYear) {
      const fallbackSearch = await tmdbFetch("/search/tv", {
        query: searchTitle,
        include_adult: "false",
        language
      });
      results = Array.isArray(fallbackSearch.results) ? fallbackSearch.results : [];
    }
    const best = selectBestTmdbMatch(results, searchTitle, effectiveYear, true);
    if (!best) return null;
    resolvedTvId = best.id;
  }

  const details = await tmdbFetch(`/tv/${resolvedTvId}`, {
    append_to_response: "credits,external_ids",
    language
  });

  const payload = formatTvDetailsPayload(details, searchTitle, effectiveYear);
  setCached(cacheKey, payload);
  return payload;
}

/**
 * GET /api/tmdb/movie?title=Inception&year=2010&tmdbId=27205&language=en-US
 */
app.get("/api/tmdb/movie", requireProxyAuth, async (req, res) => {
  const rawTitle = sanitizeTitleInput(req.query.title || req.query.query || "");
  if (rawTitle.length > 200) {
    return res.status(400).json({ ok: false, error: "Parameter 'title' must not exceed 200 characters." });
  }

  const yearValidation = validateOptionalYear(req.query.year);
  if (!yearValidation.valid) {
    return res.status(400).json({ ok: false, error: yearValidation.error });
  }

  const tmdbIdValidation = validateOptionalPositiveInt(req.query.tmdbId, "tmdbId");
  if (!tmdbIdValidation.valid) {
    return res.status(400).json({ ok: false, error: tmdbIdValidation.error });
  }

  const langValidation = validateLanguage(req.query.language);
  if (!langValidation.valid) {
    return res.status(400).json({ ok: false, error: langValidation.error });
  }

  if (!rawTitle && tmdbIdValidation.value === 0) {
    return res.status(400).json({
      ok: false,
      error: "Either 'title' or 'tmdbId' query parameter is required."
    });
  }

  try {
    const payload = await resolveAndFetchMovieMetadata({
      rawTitle,
      year: yearValidation.value,
      tmdbId: tmdbIdValidation.value,
      language: langValidation.value
    });

    if (!payload) {
      return res.status(404).json({ ok: false, error: "No matching movie found on TMDb." });
    }
    return res.json(payload);
  } catch (err) {
    return res.status(err.statusCode || 500).json({
      ok: false,
      error: err.message || "Failed to fetch movie metadata from TMDb."
    });
  }
});

/**
 * GET /api/tmdb/tv?title=Breaking+Bad&year=2008&tmdbId=1396&language=en-US
 */
app.get("/api/tmdb/tv", requireProxyAuth, async (req, res) => {
  const rawTitle = sanitizeTitleInput(req.query.title || req.query.query || "");
  if (rawTitle.length > 200) {
    return res.status(400).json({ ok: false, error: "Parameter 'title' must not exceed 200 characters." });
  }

  const yearValidation = validateOptionalYear(req.query.year);
  if (!yearValidation.valid) {
    return res.status(400).json({ ok: false, error: yearValidation.error });
  }

  const tmdbIdValidation = validateOptionalPositiveInt(req.query.tmdbId, "tmdbId");
  if (!tmdbIdValidation.valid) {
    return res.status(400).json({ ok: false, error: tmdbIdValidation.error });
  }

  const langValidation = validateLanguage(req.query.language);
  if (!langValidation.valid) {
    return res.status(400).json({ ok: false, error: langValidation.error });
  }

  if (!rawTitle && tmdbIdValidation.value === 0) {
    return res.status(400).json({
      ok: false,
      error: "Either 'title' or 'tmdbId' query parameter is required."
    });
  }

  try {
    const payload = await resolveAndFetchTvMetadata({
      rawTitle,
      year: yearValidation.value,
      tmdbId: tmdbIdValidation.value,
      language: langValidation.value
    });

    if (!payload) {
      return res.status(404).json({ ok: false, error: "No matching TV series found on TMDb." });
    }
    return res.json(payload);
  } catch (err) {
    return res.status(err.statusCode || 500).json({
      ok: false,
      error: err.message || "Failed to fetch TV series metadata from TMDb."
    });
  }
});

/**
 * Unified Metadata Matching Endpoint (GET & POST /api/tmdb/match)
 * Accepts raw IPTV stream titles (Xtream Codes or M3U), cleans tags, matches Movie or TV Series,
 * and returns complete enriched metadata.
 */
async function handleMetadataMatch(req, res) {
  const source = req.method === "POST" ? req.body || {} : req.query || {};
  const rawTitle = sanitizeTitleInput(source.title || source.name || source.query || "");
  const rawType = String(source.type || "movie").trim().toLowerCase();

  if (rawType !== "movie" && rawType !== "tv" && rawType !== "series") {
    return res.status(400).json({
      ok: false,
      error: "Invalid 'type' parameter. Allowed values: 'movie', 'tv', or 'series'."
    });
  }

  if (rawTitle.length > 200) {
    return res.status(400).json({ ok: false, error: "Parameter 'title' must not exceed 200 characters." });
  }

  const yearValidation = validateOptionalYear(source.year);
  if (!yearValidation.valid) {
    return res.status(400).json({ ok: false, error: yearValidation.error });
  }

  const tmdbIdValidation = validateOptionalPositiveInt(source.tmdbId, "tmdbId");
  if (!tmdbIdValidation.valid) {
    return res.status(400).json({ ok: false, error: tmdbIdValidation.error });
  }

  const langValidation = validateLanguage(source.language);
  if (!langValidation.valid) {
    return res.status(400).json({ ok: false, error: langValidation.error });
  }

  if (!rawTitle && tmdbIdValidation.value === 0) {
    return res.status(400).json({
      ok: false,
      error: "Either 'title' or 'tmdbId' is required for metadata matching."
    });
  }

  const isTv = rawType === "tv" || rawType === "series";
  const cleaned = cleanIptvTitle(rawTitle);

  try {
    const payload = isTv
      ? await resolveAndFetchTvMetadata({
          rawTitle,
          year: yearValidation.value,
          tmdbId: tmdbIdValidation.value,
          language: langValidation.value
        })
      : await resolveAndFetchMovieMetadata({
          rawTitle,
          year: yearValidation.value,
          tmdbId: tmdbIdValidation.value,
          language: langValidation.value
        });

    if (!payload) {
      return res.status(404).json({
        ok: false,
        cleanedQuery: cleaned,
        error: `No matching ${isTv ? "TV series" : "movie"} found on TMDb.`
      });
    }

    return res.json({
      ...payload,
      matchedQuery: cleaned.cleanTitle,
      extractedYear: cleaned.extractedYear
    });
  } catch (err) {
    return res.status(err.statusCode || 500).json({
      ok: false,
      error: err.message || "Failed to match metadata on TMDb."
    });
  }
}

app.get("/api/tmdb/match", requireProxyAuth, handleMetadataMatch);
app.post("/api/tmdb/match", requireProxyAuth, handleMetadataMatch);

// ============================================================================
// 13. Official TMDb User Authentication & Two-Way Synchronization Endpoints
// ============================================================================

function extractTmdbSessionId(req) {
  const headerSession = req.headers["x-tmdb-session-id"];
  if (typeof headerSession === "string" && headerSession.trim()) {
    return headerSession.trim();
  }
  if (req.body && typeof req.body.sessionId === "string" && req.body.sessionId.trim()) {
    return req.body.sessionId.trim();
  }
  if (req.query && typeof req.query.sessionId === "string" && req.query.sessionId.trim()) {
    return req.query.sessionId.trim();
  }
  return "";
}

function buildAccountAvatarUrl(accountData) {
  const tmdbAvatarPath =
    accountData &&
    accountData.avatar &&
    accountData.avatar.tmdb &&
    accountData.avatar.tmdb.avatar_path;
  if (tmdbAvatarPath) {
    return `${TMDB_IMAGE_BASE_W185}${tmdbAvatarPath}`;
  }
  const gravatarHash =
    accountData &&
    accountData.avatar &&
    accountData.avatar.gravatar &&
    accountData.avatar.gravatar.hash;
  if (gravatarHash) {
    return `https://secure.gravatar.com/avatar/${gravatarHash}.jpg?s=185&d=identicon`;
  }
  return "";
}

function formatSyncMediaEntry(raw, mediaType) {
  const isTv = mediaType === "tv";
  const releaseDate = (isTv ? raw.first_air_date : raw.release_date) || "";
  return {
    tmdbId: raw.id || 0,
    mediaType: isTv ? "tv" : "movie",
    title: (isTv ? raw.name || raw.original_name : raw.title || raw.original_title) || "",
    posterUrl: buildPosterUrl(raw.poster_path),
    backdropUrl: buildBackdropUrl(raw.backdrop_path),
    overview: raw.overview || "",
    rating: typeof raw.vote_average === "number" ? Number(raw.vote_average.toFixed(1)) : 0.0,
    releaseYear: releaseDate.slice(0, 4),
    userRating: typeof raw.rating === "number" ? Number(raw.rating.toFixed(1)) : 0.0
  };
}

/**
 * POST /api/tmdb/auth/request-token
 * Body: { "redirectTo": "krastv://tmdb-auth" }
 */
app.post("/api/tmdb/auth/request-token", requireProxyAuth, async (req, res) => {
  const redirectTo =
    req.body && typeof req.body.redirectTo === "string" && req.body.redirectTo.trim()
      ? req.body.redirectTo.trim()
      : "krastv://tmdb-auth";

  try {
    const tokenRes = await tmdbRequest("/authentication/token/new", { method: "GET" });
    const requestToken = (tokenRes && tokenRes.request_token) || "";
    if (!requestToken) {
      return res.status(502).json({
        ok: false,
        error: "Unable to create TMDb authorization token."
      });
    }

    const encodedRedirect = encodeURIComponent(redirectTo);
    const authUrl = `https://www.themoviedb.org/authenticate/${requestToken}?redirect_to=${encodedRedirect}`;

    return res.status(200).json({
      ok: true,
      requestToken,
      expiresAt: tokenRes.expires_at || "",
      authUrl
    });
  } catch (err) {
    return res.status(err.statusCode || 502).json({
      ok: false,
      error: "Unable to start TMDb authorization right now. Please try again."
    });
  }
});

/**
 * POST /api/tmdb/auth/session
 * Body: { "requestToken": "<approved_request_token>" }
 */
app.post("/api/tmdb/auth/session", requireProxyAuth, async (req, res) => {
  const requestToken =
    req.body && typeof req.body.requestToken === "string" ? req.body.requestToken.trim() : "";

  if (!requestToken || requestToken.length < 8) {
    return res.status(400).json({
      ok: false,
      error: "A valid approved TMDb requestToken is required."
    });
  }

  try {
    const sessionRes = await tmdbRequest("/authentication/session/new", {
      method: "POST",
      body: { request_token: requestToken }
    });

    const sessionId = (sessionRes && sessionRes.session_id) || "";
    if (!sessionId) {
      return res.status(401).json({
        ok: false,
        error: "Please approve the authorization request in your browser first, then try again."
      });
    }

    const accountData = await tmdbRequest("/account", {
      method: "GET",
      queryParams: { session_id: sessionId }
    });

    return res.status(200).json({
      ok: true,
      sessionId,
      account: {
        id: accountData.id || 0,
        username: accountData.username || "",
        name: accountData.name || accountData.username || "",
        avatarUrl: buildAccountAvatarUrl(accountData),
        includeAdult: Boolean(accountData.include_adult),
        iso639_1: accountData.iso_639_1 || "en",
        iso3166_1: accountData.iso_3166_1 || "US"
      }
    });
  } catch (err) {
    const status = err.statusCode === 401 || err.statusCode === 403 ? 401 : err.statusCode || 502;
    return res.status(status).json({
      ok: false,
      error:
        status === 401
          ? "Please approve the TMDb sign-in request in your browser first, then tap Complete Sign-In."
          : "Unable to verify TMDb account authorization. Please try again."
    });
  }
});

/**
 * POST /api/tmdb/auth/logout
 * Header: X-TMDb-Session-Id or Body: { "sessionId": "..." }
 */
app.post("/api/tmdb/auth/logout", requireProxyAuth, async (req, res) => {
  const sessionId = extractTmdbSessionId(req);
  if (!sessionId) {
    return res.status(200).json({ ok: true });
  }

  try {
    await tmdbRequest("/authentication/session", {
      method: "DELETE",
      body: { session_id: sessionId }
    });
  } catch (_) {
    // Ignore upstream error during session revocation
  }

  return res.status(200).json({ ok: true });
});

/**
 * GET /api/tmdb/account
 * Header: X-TMDb-Session-Id
 */
app.get("/api/tmdb/account", requireProxyAuth, async (req, res) => {
  const sessionId = extractTmdbSessionId(req);
  if (!sessionId) {
    return res.status(401).json({
      ok: false,
      error: "TMDb session is required. Please connect your TMDb account."
    });
  }

  try {
    const accountData = await tmdbRequest("/account", {
      method: "GET",
      queryParams: { session_id: sessionId }
    });

    return res.status(200).json({
      ok: true,
      account: {
        id: accountData.id || 0,
        username: accountData.username || "",
        name: accountData.name || accountData.username || "",
        avatarUrl: buildAccountAvatarUrl(accountData)
      }
    });
  } catch (err) {
    return res.status(err.statusCode || 502).json({
      ok: false,
      error: "Unable to load TMDb account profile."
    });
  }
});

/**
 * POST /api/tmdb/sync
 * Performs two-way synchronization for Favorites, Watchlist, and Ratings.
 */
app.post("/api/tmdb/sync", requireProxyAuth, async (req, res) => {
  const sessionId = extractTmdbSessionId(req);
  if (!sessionId) {
    return res.status(401).json({
      ok: false,
      error: "Your TMDb session has expired. Please reconnect your TMDb account."
    });
  }

  const body = req.body || {};
  const language = validateLanguage(body.language).valid ? body.language : "en-US";
  let accountId = typeof body.accountId === "number" && body.accountId > 0 ? body.accountId : 0;

  try {
    if (!accountId) {
      const accountData = await tmdbRequest("/account", {
        method: "GET",
        queryParams: { session_id: sessionId }
      });
      accountId = accountData.id || 0;
    }

    if (!accountId) {
      return res.status(401).json({
        ok: false,
        error: "Unable to verify TMDb account session."
      });
    }

    let pushedCount = 0;

    // 1. Push local Favorites -> TMDb
    const localFavorites = Array.isArray(body.favorites) ? body.favorites.slice(0, 60) : [];
    for (const fav of localFavorites) {
      const tmdbId = parseInt(fav && fav.tmdbId, 10);
      const mediaType = fav && fav.mediaType === "tv" ? "tv" : "movie";
      if (tmdbId > 0) {
        try {
          await tmdbRequest(`/account/${accountId}/favorite`, {
            method: "POST",
            queryParams: { session_id: sessionId },
            body: {
              media_type: mediaType,
              media_id: tmdbId,
              favorite: fav.favorite !== false
            }
          });
          pushedCount += 1;
        } catch (_) {
          // Continue syncing remaining items
        }
      }
    }

    // 2. Push local Watchlist -> TMDb
    const localWatchlist = Array.isArray(body.watchlist) ? body.watchlist.slice(0, 60) : [];
    for (const wl of localWatchlist) {
      const tmdbId = parseInt(wl && wl.tmdbId, 10);
      const mediaType = wl && wl.mediaType === "tv" ? "tv" : "movie";
      if (tmdbId > 0) {
        try {
          await tmdbRequest(`/account/${accountId}/watchlist`, {
            method: "POST",
            queryParams: { session_id: sessionId },
            body: {
              media_type: mediaType,
              media_id: tmdbId,
              watchlist: wl.watchlist !== false
            }
          });
          pushedCount += 1;
        } catch (_) {
          // Continue syncing remaining items
        }
      }
    }

    // 3. Push local Ratings -> TMDb
    const localRatings = Array.isArray(body.ratings) ? body.ratings.slice(0, 60) : [];
    for (const rt of localRatings) {
      const tmdbId = parseInt(rt && rt.tmdbId, 10);
      const mediaType = rt && rt.mediaType === "tv" ? "tv" : "movie";
      const rawScore = Number(rt && rt.rating);
      if (tmdbId > 0 && !Number.isNaN(rawScore) && rawScore >= 0.5 && rawScore <= 10.0) {
        const roundedHalfStep = Math.round(rawScore * 2) / 2;
        try {
          await tmdbRequest(`/${mediaType}/${tmdbId}/rating`, {
            method: "POST",
            queryParams: { session_id: sessionId },
            body: { value: roundedHalfStep }
          });
          pushedCount += 1;
        } catch (_) {
          // Continue syncing remaining items
        }
      }
    }

    // 4. Pull remote Favorites, Watchlist, and Ratings from TMDb
    const [
      favMoviesRes,
      favTvRes,
      watchMoviesRes,
      watchTvRes,
      ratedMoviesRes,
      ratedTvRes
    ] = await Promise.all([
      tmdbRequest(`/account/${accountId}/favorite/movies`, {
        method: "GET",
        queryParams: { session_id: sessionId, language, sort_by: "created_at.desc", page: 1 }
      }).catch(() => ({ results: [] })),
      tmdbRequest(`/account/${accountId}/favorite/tv`, {
        method: "GET",
        queryParams: { session_id: sessionId, language, sort_by: "created_at.desc", page: 1 }
      }).catch(() => ({ results: [] })),
      tmdbRequest(`/account/${accountId}/watchlist/movies`, {
        method: "GET",
        queryParams: { session_id: sessionId, language, sort_by: "created_at.desc", page: 1 }
      }).catch(() => ({ results: [] })),
      tmdbRequest(`/account/${accountId}/watchlist/tv`, {
        method: "GET",
        queryParams: { session_id: sessionId, language, sort_by: "created_at.desc", page: 1 }
      }).catch(() => ({ results: [] })),
      tmdbRequest(`/account/${accountId}/rated/movies`, {
        method: "GET",
        queryParams: { session_id: sessionId, language, sort_by: "created_at.desc", page: 1 }
      }).catch(() => ({ results: [] })),
      tmdbRequest(`/account/${accountId}/rated/tv`, {
        method: "GET",
        queryParams: { session_id: sessionId, language, sort_by: "created_at.desc", page: 1 }
      }).catch(() => ({ results: [] }))
    ]);

    const favorites = [
      ...(Array.isArray(favMoviesRes.results)
        ? favMoviesRes.results.map((m) => formatSyncMediaEntry(m, "movie"))
        : []),
      ...(Array.isArray(favTvRes.results)
        ? favTvRes.results.map((t) => formatSyncMediaEntry(t, "tv"))
        : [])
    ];

    const watchlist = [
      ...(Array.isArray(watchMoviesRes.results)
        ? watchMoviesRes.results.map((m) => formatSyncMediaEntry(m, "movie"))
        : []),
      ...(Array.isArray(watchTvRes.results)
        ? watchTvRes.results.map((t) => formatSyncMediaEntry(t, "tv"))
        : [])
    ];

    const ratings = [
      ...(Array.isArray(ratedMoviesRes.results)
        ? ratedMoviesRes.results.map((m) => formatSyncMediaEntry(m, "movie"))
        : []),
      ...(Array.isArray(ratedTvRes.results)
        ? ratedTvRes.results.map((t) => formatSyncMediaEntry(t, "tv"))
        : [])
    ];

    return res.status(200).json({
      ok: true,
      accountId,
      favorites,
      watchlist,
      ratings,
      pushedCount,
      syncedAt: new Date().toISOString()
    });
  } catch (err) {
    const status = err.statusCode || 502;
    return res.status(status).json({
      ok: false,
      error:
        status === 401
          ? "Your TMDb session has expired. Please reconnect your TMDb account."
          : "Unable to complete TMDb synchronization right now. Please try again."
    });
  }
});

/**
 * POST /api/tmdb/account/favorite
 */
app.post("/api/tmdb/account/favorite", requireProxyAuth, async (req, res) => {
  const sessionId = extractTmdbSessionId(req);
  if (!sessionId) {
    return res.status(401).json({ ok: false, error: "TMDb session required." });
  }

  const body = req.body || {};
  const tmdbId = parseInt(body.tmdbId, 10);
  const mediaType = body.mediaType === "tv" || body.mediaType === "series" ? "tv" : "movie";
  const isFavorite = body.favorite !== false;
  let accountId = parseInt(body.accountId, 10) || 0;

  if (!(tmdbId > 0)) {
    return res.status(400).json({ ok: false, error: "Valid tmdbId is required." });
  }

  try {
    if (!accountId) {
      const acc = await tmdbRequest("/account", {
        method: "GET",
        queryParams: { session_id: sessionId }
      });
      accountId = acc.id || 0;
    }

    await tmdbRequest(`/account/${accountId}/favorite`, {
      method: "POST",
      queryParams: { session_id: sessionId },
      body: {
        media_type: mediaType,
        media_id: tmdbId,
        favorite: isFavorite
      }
    });

    return res.status(200).json({ ok: true, tmdbId, mediaType, favorite: isFavorite });
  } catch (err) {
    return res.status(err.statusCode || 502).json({
      ok: false,
      error: "Unable to update TMDb favorite status."
    });
  }
});

/**
 * POST /api/tmdb/account/watchlist
 */
app.post("/api/tmdb/account/watchlist", requireProxyAuth, async (req, res) => {
  const sessionId = extractTmdbSessionId(req);
  if (!sessionId) {
    return res.status(401).json({ ok: false, error: "TMDb session required." });
  }

  const body = req.body || {};
  const tmdbId = parseInt(body.tmdbId, 10);
  const mediaType = body.mediaType === "tv" || body.mediaType === "series" ? "tv" : "movie";
  const inWatchlist = body.watchlist !== false;
  let accountId = parseInt(body.accountId, 10) || 0;

  if (!(tmdbId > 0)) {
    return res.status(400).json({ ok: false, error: "Valid tmdbId is required." });
  }

  try {
    if (!accountId) {
      const acc = await tmdbRequest("/account", {
        method: "GET",
        queryParams: { session_id: sessionId }
      });
      accountId = acc.id || 0;
    }

    await tmdbRequest(`/account/${accountId}/watchlist`, {
      method: "POST",
      queryParams: { session_id: sessionId },
      body: {
        media_type: mediaType,
        media_id: tmdbId,
        watchlist: inWatchlist
      }
    });

    return res.status(200).json({ ok: true, tmdbId, mediaType, watchlist: inWatchlist });
  } catch (err) {
    return res.status(err.statusCode || 502).json({
      ok: false,
      error: "Unable to update TMDb watchlist status."
    });
  }
});

/**
 * POST /api/tmdb/account/rating
 */
app.post("/api/tmdb/account/rating", requireProxyAuth, async (req, res) => {
  const sessionId = extractTmdbSessionId(req);
  if (!sessionId) {
    return res.status(401).json({ ok: false, error: "TMDb session required." });
  }

  const body = req.body || {};
  const tmdbId = parseInt(body.tmdbId, 10);
  const mediaType = body.mediaType === "tv" || body.mediaType === "series" ? "tv" : "movie";
  const ratingVal = Number(body.rating);

  if (!(tmdbId > 0) || Number.isNaN(ratingVal)) {
    return res.status(400).json({ ok: false, error: "Valid tmdbId and rating are required." });
  }

  try {
    if (ratingVal <= 0) {
      await tmdbRequest(`/${mediaType}/${tmdbId}/rating`, {
        method: "DELETE",
        queryParams: { session_id: sessionId }
      });
      return res.status(200).json({ ok: true, tmdbId, mediaType, rating: 0 });
    }

    const clamped = Math.min(10.0, Math.max(0.5, Math.round(ratingVal * 2) / 2));
    await tmdbRequest(`/${mediaType}/${tmdbId}/rating`, {
      method: "POST",
      queryParams: { session_id: sessionId },
      body: { value: clamped }
    });

    return res.status(200).json({ ok: true, tmdbId, mediaType, rating: clamped });
  } catch (err) {
    return res.status(err.statusCode || 502).json({
      ok: false,
      error: "Unable to update TMDb rating."
    });
  }
});

// 404 Handler for unknown routes
app.use((req, res) => {
  res.status(404).json({
    ok: false,
    error: `Route ${req.method} ${req.originalUrl} not found on KRAS TV TMDb Proxy.`
  });
});

// Global Error Handler (for CORS or JSON parse errors)
app.use((err, req, res, _next) => {
  const status = err.statusCode || err.status || 500;
  res.status(status).json({
    ok: false,
    error: err.message || "Internal proxy error."
  });
});

if (require.main === module) {
  app.listen(PORT, "0.0.0.0", () => {
    console.log(`KRAS TV TMDb Proxy v1.0 listening on 0.0.0.0:${PORT}`);
  });
}

module.exports = {
  app,
  cleanIptvTitle,
  selectBestTmdbMatch,
  clearCache,
  ipRequestCounters
};
