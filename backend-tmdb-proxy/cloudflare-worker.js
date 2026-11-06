/**
 * KRAS TV — Production Cloudflare Worker Backend
 * URL: https://kras-tmdb-proxy.asdking01.workers.dev
 *
 * Features:
 * 1. Preserves existing working TMDb connectivity test endpoint:
 *    - GET  /api/tmdb/test (and /health, /api/tmdb/health, /api/tmdb/connectivity-test)
 * 2. Automatic Movie, TV Series, Season, and Episode Metadata Enrichment:
 *    - GET  /api/tmdb/movie?title=...&year=...&tmdbId=...&language=...
 *    - GET  /api/tmdb/tv?title=...&year=...&tmdbId=...&language=...
 *    - GET  /api/tmdb/tv/:tvId/season/:seasonNumber?language=...
 *    - GET  /api/tmdb/match & POST /api/tmdb/match
 *    - GET  /api/tmdb/search/movie & GET /api/tmdb/search/tv
 * 3. Official TMDb User Account Authorization & Two-Way Synchronization:
 *    - POST /api/tmdb/auth/request-token (also supports GET)
 *    - POST /api/tmdb/auth/session
 *    - POST /api/tmdb/auth/logout
 *    - GET  /api/tmdb/account
 *    - POST /api/tmdb/sync
 *    - POST /api/tmdb/account/favorite
 *    - POST /api/tmdb/account/watchlist
 *    - POST /api/tmdb/account/rating
 * 4. Optional Cloudflare D1 Database Persistence (`env.DB`):
 *    - Works immediately with zero database bindings (using encrypted client X-TMDb-Session-Id header
 *      and live TMDb Account API v3 storage).
 *    - If a Cloudflare D1 binding named `DB` is attached, also persists connected account metadata
 *      and sync audit state in D1 (see migrations/0001_tmdb_account_sync.sql).
 */

const TMDB_BASE = "https://api.themoviedb.org/3";
const IMG_W500 = "https://image.tmdb.org/t/p/w500";
const IMG_W1280 = "https://image.tmdb.org/t/p/w1280";
const IMG_W185 = "https://image.tmdb.org/t/p/w185";

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
  "Access-Control-Allow-Headers":
    "Accept, Content-Type, Authorization, X-KRAS-Proxy-Token, X-TMDb-Session-Id, X-Client-App",
  "X-Content-Type-Options": "nosniff"
};

function jsonResponse(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      ...CORS_HEADERS
    }
  });
}

function cleanIptvTitle(rawTitle = "") {
  let working = String(rawTitle || "").trim();
  if (!working) return { cleanTitle: "", extractedYear: "" };

  let extractedYear = "";
  const bracketYear = working.match(/[(\[](19\d{2}|20[0-3]\d)[)\]]/);
  if (bracketYear) extractedYear = bracketYear[1];

  working = working
    .replace(
      /^(?:[A-Z]{2,4}\s*\|\s*|[A-Z]{2,3}\s+-\s+|(?:EN|US|UK|AR|FR|DE|ES|IT|TR|RU|NL|PT|BR|IN|CA|AU|VIP|VOD)\s*:\s*)+/i,
      ""
    )
    .replace(/\b(?:S\d{1,2}\s*E\d{1,3}|S\d{1,2}|Season\s*\d{1,2}|Episode\s*\d{1,3})\b.*$/i, "")
    .replace(/\[[^\]]*\]|\([^)]*\)/g, " ")
    .replace(
      /\b(?:8K|4K|UHD|2160p|1080p|FHD|Full\s*HD|720p|HD|480p|SD|HEVC|H\.?265|H\.?264|x264|x265|HDR10\+?|HDR|DV|Dolby\s*Vision|Atmos|50\s*FPS|60\s*FPS|BluRay|WEB-?DL|WEBRip|BRRip|CAM|MULTI|SUB|DUB|VOSTFR)\b/gi,
      " "
    );

  const trimmed = working.trim().replace(/[-|:.]+$/, "").trim();
  if (!extractedYear) {
    const trailingMatch = trimmed.match(/\b(19\d{2}|20[0-3]\d)\s*$/);
    if (trailingMatch) {
      const candidate = trailingMatch[1];
      const withoutYear = trimmed.slice(0, -candidate.length).trim();
      if (withoutYear) {
        extractedYear = candidate;
        working = withoutYear;
      }
    }
  } else if (trimmed.endsWith(extractedYear)) {
    working = trimmed.slice(0, -extractedYear.length);
  }

  const cleanTitle = working
    .replace(/\s{2,}/g, " ")
    .trim()
    .replace(/^[-|:._]+|[-|:._]+$/g, "")
    .trim();
  return { cleanTitle: cleanTitle || String(rawTitle || "").trim(), extractedYear };
}

async function tmdbRequest(env, path, { method = "GET", params = {}, body = null } = {}) {
  const apiKey = (env.TMDB_API_KEY || "").trim();
  if (!apiKey) {
    const err = new Error("TMDB_API_KEY is not configured in Cloudflare Worker secrets.");
    err.status = 503;
    throw err;
  }

  const url = new URL(`${TMDB_BASE}${path}`);
  const headers = {
    Accept: "application/json",
    "User-Agent": "KRAS-TV-Cloudflare-Worker/2.1"
  };

  if (body !== null && body !== undefined) {
    headers["Content-Type"] = "application/json;charset=utf-8";
  }

  // Supports both TMDb v3 API Key (32-char hex) and v4 JWT Read Access Token (eyJ...)
  if (apiKey.startsWith("eyJ")) {
    headers["Authorization"] = `Bearer ${apiKey}`;
  } else {
    url.searchParams.set("api_key", apiKey);
  }

  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && String(v).trim() !== "") {
      url.searchParams.set(k, String(v).trim());
    }
  }

  const res = await fetch(url.toString(), {
    method,
    headers,
    body: body !== null && body !== undefined ? JSON.stringify(body) : undefined
  });

  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error((data && data.status_message) || `TMDb HTTP ${res.status}`);
    err.status = res.status;
    throw err;
  }
  return data;
}

function buildAvatarUrl(accountData) {
  const tmdbPath = accountData?.avatar?.tmdb?.avatar_path;
  if (tmdbPath) return `${IMG_W185}${tmdbPath}`;
  const hash = accountData?.avatar?.gravatar?.hash;
  if (hash) return `https://secure.gravatar.com/avatar/${hash}.jpg?s=185&d=identicon`;
  return "";
}

function formatSyncEntry(raw, mediaType) {
  const isTv = mediaType === "tv";
  const releaseDate = (isTv ? raw.first_air_date : raw.release_date) || "";
  return {
    tmdbId: raw.id || 0,
    mediaType: isTv ? "tv" : "movie",
    title: (isTv ? raw.name || raw.original_name : raw.title || raw.original_title) || "",
    posterUrl: raw.poster_path ? `${IMG_W500}${raw.poster_path}` : "",
    backdropUrl: raw.backdrop_path ? `${IMG_W1280}${raw.backdrop_path}` : "",
    overview: raw.overview || "",
    rating: typeof raw.vote_average === "number" ? Number(raw.vote_average.toFixed(1)) : 0.0,
    releaseYear: releaseDate.slice(0, 4),
    userRating: typeof raw.rating === "number" ? Number(raw.rating.toFixed(1)) : 0.0
  };
}

/**
 * Optional Cloudflare D1 persistence helper (runs only if `env.DB` D1 binding is configured).
 */
async function persistAccountSessionToD1(env, { accountId, username, displayName, avatarUrl }) {
  if (!env || !env.DB || typeof env.DB.prepare !== "function") return;
  try {
    await env.DB.prepare(
      `INSERT INTO tmdb_accounts (account_id, username, display_name, avatar_url, updated_at)
       VALUES (?1, ?2, ?3, ?4, datetime('now'))
       ON CONFLICT(account_id) DO UPDATE SET
         username = excluded.username,
         display_name = excluded.display_name,
         avatar_url = excluded.avatar_url,
         updated_at = datetime('now')`
    )
      .bind(accountId, username, displayName, avatarUrl)
      .run();
  } catch (_) {
    // Non-fatal if D1 table is not migrated
  }
}

async function persistSyncSnapshotToD1(env, { accountId, favoritesCount, watchlistCount, ratingsCount }) {
  if (!env || !env.DB || typeof env.DB.prepare !== "function") return;
  try {
    await env.DB.prepare(
      `UPDATE tmdb_accounts
       SET favorites_count = ?2,
           watchlist_count = ?3,
           ratings_count = ?4,
           last_synced_at = datetime('now'),
           updated_at = datetime('now')
       WHERE account_id = ?1`
    )
      .bind(accountId, favoritesCount, watchlistCount, ratingsCount)
      .run();
  } catch (_) {
    // Non-fatal if D1 table is not migrated
  }
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";

    try {
      // =========================================================================
      // 1. Health & Existing TMDb Test Endpoint (/api/tmdb/test & /api/tmdb/health)
      // =========================================================================
      if (
        request.method === "GET" &&
        (path === "/api/tmdb/test" ||
          path === "/test" ||
          path === "/health" ||
          path === "/api/tmdb/health" ||
          path === "/api/tmdb/connectivity-test")
      ) {
        if (!(env.TMDB_API_KEY || "").trim()) {
          return jsonResponse(
            {
              ok: false,
              status: "unconfigured",
              service: "kras-tv-tmdb-proxy",
              tmdbApiVersion: "v3",
              tmdbConnected: false,
              message: "TMDB_API_KEY secret is not configured in Cloudflare Worker."
            },
            503
          );
        }
        const cfg = await tmdbRequest(env, "/configuration");
        const ok = Boolean(cfg?.images?.secure_base_url);
        return jsonResponse({
          ok,
          success: ok,
          status: ok ? "connected" : "degraded",
          service: "kras-tv-tmdb-proxy",
          tmdbApiVersion: "v3",
          tmdbConnected: ok,
          accountSyncSupported: true,
          imageBaseUrl: cfg?.images?.secure_base_url || "https://image.tmdb.org/t/p/",
          message: "Connected to TMDb API v3 via secure Cloudflare Worker."
        });
      }

      // =========================================================================
      // 2. Movie Metadata Enrichment: GET /api/tmdb/movie
      // =========================================================================
      if (request.method === "GET" && path === "/api/tmdb/movie") {
        const rawTitle = (url.searchParams.get("title") || url.searchParams.get("query") || "").trim();
        const yearParam = (url.searchParams.get("year") || "").trim();
        const tmdbIdParam = parseInt(url.searchParams.get("tmdbId") || "0", 10) || 0;
        const language = (url.searchParams.get("language") || "en-US").trim();

        const cleaned = cleanIptvTitle(rawTitle);
        const searchTitle = cleaned.cleanTitle || rawTitle;
        const effectiveYear = yearParam || cleaned.extractedYear;

        let movieId = tmdbIdParam;
        if (!movieId) {
          const search = await tmdbRequest(env, "/search/movie", {
            params: {
              query: searchTitle,
              year: effectiveYear || undefined,
              include_adult: "false",
              language
            }
          });
          const first = Array.isArray(search.results) && search.results[0];
          if (!first) return jsonResponse({ ok: false, error: "No matching movie found." }, 404);
          movieId = first.id;
        }

        const d = await tmdbRequest(env, `/movie/${movieId}`, {
          params: { append_to_response: "credits,external_ids", language }
        });
        const genres = Array.isArray(d.genres) ? d.genres.map((g) => g.name).filter(Boolean) : [];
        const castList = Array.isArray(d.credits?.cast)
          ? d.credits.cast.slice(0, 10).map((c) => ({
              id: c.id || 0,
              name: c.name || "",
              character: c.character || "",
              profileUrl: c.profile_path ? `${IMG_W185}${c.profile_path}` : ""
            }))
          : [];
        const directors = Array.isArray(d.credits?.crew)
          ? d.credits.crew.filter((c) => c.job === "Director").map((c) => c.name).filter(Boolean)
          : [];
        const releaseYear = (d.release_date || "").slice(0, 4) || effectiveYear;

        return jsonResponse({
          ok: true,
          mediaType: "movie",
          tmdbId: d.id || 0,
          title: d.title || d.original_title || searchTitle,
          overview: d.overview || "",
          posterUrl: d.poster_path ? `${IMG_W500}${d.poster_path}` : "",
          backdropUrl: d.backdrop_path ? `${IMG_W1280}${d.backdrop_path}` : "",
          rating: typeof d.vote_average === "number" ? Number(d.vote_average.toFixed(1)) : 0.0,
          releaseYear,
          year: releaseYear,
          releaseDate: d.release_date || "",
          genres: genres.join(", "),
          cast: castList.map((c) => c.name).join(", "),
          castList,
          director: directors.join(", "),
          runtimeMinutes: d.runtime || 0
        });
      }

      // =========================================================================
      // 3. TV Series Metadata Enrichment: GET /api/tmdb/tv
      // =========================================================================
      if (request.method === "GET" && path === "/api/tmdb/tv") {
        const rawTitle = (url.searchParams.get("title") || url.searchParams.get("query") || "").trim();
        const yearParam = (url.searchParams.get("year") || "").trim();
        const tmdbIdParam = parseInt(url.searchParams.get("tmdbId") || "0", 10) || 0;
        const language = (url.searchParams.get("language") || "en-US").trim();

        const cleaned = cleanIptvTitle(rawTitle);
        const searchTitle = cleaned.cleanTitle || rawTitle;
        const effectiveYear = yearParam || cleaned.extractedYear;

        let tvId = tmdbIdParam;
        if (!tvId) {
          const search = await tmdbRequest(env, "/search/tv", {
            params: {
              query: searchTitle,
              first_air_date_year: effectiveYear || undefined,
              include_adult: "false",
              language
            }
          });
          const first = Array.isArray(search.results) && search.results[0];
          if (!first) return jsonResponse({ ok: false, error: "No matching TV series found." }, 404);
          tvId = first.id;
        }

        const d = await tmdbRequest(env, `/tv/${tvId}`, {
          params: { append_to_response: "credits,external_ids", language }
        });
        const genres = Array.isArray(d.genres) ? d.genres.map((g) => g.name).filter(Boolean) : [];
        const castList = Array.isArray(d.credits?.cast)
          ? d.credits.cast.slice(0, 10).map((c) => ({
              id: c.id || 0,
              name: c.name || "",
              character: c.character || "",
              profileUrl: c.profile_path ? `${IMG_W185}${c.profile_path}` : ""
            }))
          : [];
        const creators = Array.isArray(d.created_by) ? d.created_by.map((c) => c.name).filter(Boolean) : [];
        const seasons = Array.isArray(d.seasons)
          ? d.seasons
              .filter((s) => typeof s.season_number === "number" && s.season_number >= 1)
              .map((s) => ({
                seasonNumber: s.season_number,
                name: s.name || `Season ${s.season_number}`,
                episodeCount: s.episode_count || 0,
                overview: s.overview || "",
                posterUrl: s.poster_path ? `${IMG_W500}${s.poster_path}` : "",
                airDate: s.air_date || ""
              }))
          : [];
        const releaseYear = (d.first_air_date || "").slice(0, 4) || effectiveYear;

        return jsonResponse({
          ok: true,
          mediaType: "tv",
          tmdbId: d.id || 0,
          title: d.name || d.original_name || searchTitle,
          overview: d.overview || "",
          posterUrl: d.poster_path ? `${IMG_W500}${d.poster_path}` : "",
          backdropUrl: d.backdrop_path ? `${IMG_W1280}${d.backdrop_path}` : "",
          rating: typeof d.vote_average === "number" ? Number(d.vote_average.toFixed(1)) : 0.0,
          releaseYear,
          year: releaseYear,
          releaseDate: d.first_air_date || "",
          genres: genres.join(", "),
          cast: castList.map((c) => c.name).join(", "),
          castList,
          director: creators.join(", "),
          numberOfSeasons: d.number_of_seasons || seasons.length,
          seasons
        });
      }

      // =========================================================================
      // 4. TV Season Episodes: GET /api/tmdb/tv/:tvId/season/:seasonNumber
      // =========================================================================
      const seasonMatch = path.match(/^\/api\/tmdb\/tv\/(\d+)\/season\/(\d+)$/);
      if (request.method === "GET" && seasonMatch) {
        const tvId = parseInt(seasonMatch[1], 10);
        const seasonNumber = parseInt(seasonMatch[2], 10);
        const language = (url.searchParams.get("language") || "en-US").trim();
        const s = await tmdbRequest(env, `/tv/${tvId}/season/${seasonNumber}`, { params: { language } });
        const episodes = Array.isArray(s.episodes)
          ? s.episodes.map((ep) => ({
              id: ep.id || 0,
              episodeNumber: ep.episode_number || 1,
              seasonNumber: ep.season_number || seasonNumber,
              title: ep.name || `Episode ${ep.episode_number || 1}`,
              overview: ep.overview || "",
              stillUrl: ep.still_path ? `${IMG_W500}${ep.still_path}` : "",
              rating: typeof ep.vote_average === "number" ? Number(ep.vote_average.toFixed(1)) : 0.0,
              durationSec: ep.runtime ? ep.runtime * 60 : 0,
              airDate: ep.air_date || ""
            }))
          : [];
        return jsonResponse({
          ok: true,
          tmdbId: tvId,
          seasonNumber,
          name: s.name || `Season ${seasonNumber}`,
          overview: s.overview || "",
          posterUrl: s.poster_path ? `${IMG_W500}${s.poster_path}` : "",
          episodeCount: episodes.length,
          episodes
        });
      }

      // =========================================================================
      // 5. TMDb Account Auth Step 1: POST (or GET) /api/tmdb/auth/request-token
      // =========================================================================
      if (
        (request.method === "POST" || request.method === "GET") &&
        path === "/api/tmdb/auth/request-token"
      ) {
        let redirectTo = url.searchParams.get("redirectTo") || "krastv://tmdb-auth";
        if (request.method === "POST") {
          const body = await request.json().catch(() => ({}));
          if (body && typeof body.redirectTo === "string" && body.redirectTo.trim()) {
            redirectTo = body.redirectTo.trim();
          }
        }
        const tokenRes = await tmdbRequest(env, "/authentication/token/new");
        const requestToken = tokenRes.request_token || "";
        if (!requestToken) {
          return jsonResponse({ ok: false, error: "Failed to obtain TMDb request_token." }, 502);
        }
        const authUrl = `https://www.themoviedb.org/authenticate/${requestToken}?redirect_to=${encodeURIComponent(
          redirectTo
        )}`;
        return jsonResponse({
          ok: true,
          requestToken,
          request_token: requestToken,
          expiresAt: tokenRes.expires_at || "",
          authUrl
        });
      }

      // =========================================================================
      // 6. TMDb Account Auth Step 2: POST /api/tmdb/auth/session
      // =========================================================================
      if (request.method === "POST" && path === "/api/tmdb/auth/session") {
        const body = await request.json().catch(() => ({}));
        const requestToken = (body.requestToken || body.request_token || "").trim();
        if (!requestToken) {
          return jsonResponse(
            { ok: false, error: "A valid approved TMDb requestToken is required." },
            400
          );
        }
        const sessionRes = await tmdbRequest(env, "/authentication/session/new", {
          method: "POST",
          body: { request_token: requestToken }
        });
        const sessionId = sessionRes.session_id || "";
        if (!sessionId) {
          return jsonResponse(
            {
              ok: false,
              error: "Please approve the TMDb authorization request in your browser first, then try again."
            },
            401
          );
        }

        const acc = await tmdbRequest(env, "/account", { params: { session_id: sessionId } });
        const accountId = acc.id || 0;
        const username = acc.username || "";
        const displayName = acc.name || acc.username || "";
        const avatarUrl = buildAvatarUrl(acc);

        await persistAccountSessionToD1(env, { accountId, username, displayName, avatarUrl });

        return jsonResponse({
          ok: true,
          sessionId,
          accountId,
          username,
          name: displayName,
          avatarUrl,
          account: {
            id: accountId,
            username,
            name: displayName,
            avatarUrl
          }
        });
      }

      // =========================================================================
      // 7. TMDb Account Profile: GET /api/tmdb/account
      // =========================================================================
      if (request.method === "GET" && path === "/api/tmdb/account") {
        const sessionId = (
          request.headers.get("X-TMDb-Session-Id") ||
          url.searchParams.get("sessionId") ||
          ""
        ).trim();
        if (!sessionId) {
          return jsonResponse({ ok: false, error: "TMDb session required." }, 401);
        }
        const acc = await tmdbRequest(env, "/account", { params: { session_id: sessionId } });
        const accountId = acc.id || 0;
        const username = acc.username || "";
        const displayName = acc.name || acc.username || "";
        const avatarUrl = buildAvatarUrl(acc);
        return jsonResponse({
          ok: true,
          accountId,
          username,
          name: displayName,
          avatarUrl,
          account: {
            id: accountId,
            username,
            name: displayName,
            avatarUrl
          }
        });
      }

      // =========================================================================
      // 8. TMDb Account Logout: POST /api/tmdb/auth/logout
      // =========================================================================
      if (request.method === "POST" && path === "/api/tmdb/auth/logout") {
        const body = await request.json().catch(() => ({}));
        const sessionId = (request.headers.get("X-TMDb-Session-Id") || body.sessionId || "").trim();
        if (sessionId) {
          await tmdbRequest(env, "/authentication/session", {
            method: "DELETE",
            body: { session_id: sessionId }
          }).catch(() => {});
        }
        return jsonResponse({ ok: true });
      }

      // =========================================================================
      // 9. TMDb Two-Way Sync: POST /api/tmdb/sync
      // =========================================================================
      if (request.method === "POST" && path === "/api/tmdb/sync") {
        const body = await request.json().catch(() => ({}));
        const sessionId = (request.headers.get("X-TMDb-Session-Id") || body.sessionId || "").trim();
        if (!sessionId) {
          return jsonResponse(
            { ok: false, error: "Your TMDb session has expired. Please reconnect your TMDb account." },
            401
          );
        }
        const language = (body.language || "en-US").trim();
        let accountId = parseInt(body.accountId, 10) || 0;
        if (!accountId) {
          const acc = await tmdbRequest(env, "/account", { params: { session_id: sessionId } });
          accountId = acc.id || 0;
        }

        let pushedFavorites = 0;
        let pushedWatchlist = 0;
        let pushedRatings = 0;

        for (const fav of (body.favorites || []).slice(0, 60)) {
          const mediaId = parseInt(fav?.tmdbId || fav?.mediaId, 10) || 0;
          if (mediaId > 0) {
            await tmdbRequest(env, `/account/${accountId}/favorite`, {
              method: "POST",
              params: { session_id: sessionId },
              body: {
                media_type: fav.mediaType === "tv" || fav.mediaType === "series" ? "tv" : "movie",
                media_id: mediaId,
                favorite: fav.favorite !== false
              }
            }).catch(() => {});
            pushedFavorites++;
          }
        }

        for (const wl of (body.watchlist || []).slice(0, 60)) {
          const mediaId = parseInt(wl?.tmdbId || wl?.mediaId, 10) || 0;
          if (mediaId > 0) {
            await tmdbRequest(env, `/account/${accountId}/watchlist`, {
              method: "POST",
              params: { session_id: sessionId },
              body: {
                media_type: wl.mediaType === "tv" || wl.mediaType === "series" ? "tv" : "movie",
                media_id: mediaId,
                watchlist: wl.watchlist !== false
              }
            }).catch(() => {});
            pushedWatchlist++;
          }
        }

        for (const rt of (body.ratings || []).slice(0, 60)) {
          const mediaId = parseInt(rt?.tmdbId || rt?.mediaId, 10) || 0;
          const rawScore = Number(rt?.userRating || rt?.rating || 0);
          const val = Math.round(rawScore * 2) / 2;
          if (mediaId > 0 && val >= 0.5 && val <= 10.0) {
            const mType = rt.mediaType === "tv" || rt.mediaType === "series" ? "tv" : "movie";
            await tmdbRequest(env, `/${mType}/${mediaId}/rating`, {
              method: "POST",
              params: { session_id: sessionId },
              body: { value: val }
            }).catch(() => {});
            pushedRatings++;
          }
        }

        const [favMovies, favTv, wlMovies, wlTv, ratedMovies, ratedTv] = await Promise.all([
          tmdbRequest(env, `/account/${accountId}/favorite/movies`, {
            params: { session_id: sessionId, language, sort_by: "created_at.desc" }
          }).catch(() => ({ results: [] })),
          tmdbRequest(env, `/account/${accountId}/favorite/tv`, {
            params: { session_id: sessionId, language, sort_by: "created_at.desc" }
          }).catch(() => ({ results: [] })),
          tmdbRequest(env, `/account/${accountId}/watchlist/movies`, {
            params: { session_id: sessionId, language, sort_by: "created_at.desc" }
          }).catch(() => ({ results: [] })),
          tmdbRequest(env, `/account/${accountId}/watchlist/tv`, {
            params: { session_id: sessionId, language, sort_by: "created_at.desc" }
          }).catch(() => ({ results: [] })),
          tmdbRequest(env, `/account/${accountId}/rated/movies`, {
            params: { session_id: sessionId, language, sort_by: "created_at.desc" }
          }).catch(() => ({ results: [] })),
          tmdbRequest(env, `/account/${accountId}/rated/tv`, {
            params: { session_id: sessionId, language, sort_by: "created_at.desc" }
          }).catch(() => ({ results: [] }))
        ]);

        const favorites = [
          ...(favMovies.results || []).map((m) => formatSyncEntry(m, "movie")),
          ...(favTv.results || []).map((t) => formatSyncEntry(t, "tv"))
        ];
        const watchlist = [
          ...(wlMovies.results || []).map((m) => formatSyncEntry(m, "movie")),
          ...(wlTv.results || []).map((t) => formatSyncEntry(t, "tv"))
        ];
        const ratings = [
          ...(ratedMovies.results || []).map((m) => formatSyncEntry(m, "movie")),
          ...(ratedTv.results || []).map((t) => formatSyncEntry(t, "tv"))
        ];

        await persistSyncSnapshotToD1(env, {
          accountId,
          favoritesCount: favorites.length,
          watchlistCount: watchlist.length,
          ratingsCount: ratings.length
        });

        return jsonResponse({
          ok: true,
          accountId,
          favorites,
          watchlist,
          ratings,
          pushedFavorites,
          pushedWatchlist,
          pushedRatings,
          pushedCount: pushedFavorites + pushedWatchlist + pushedRatings,
          syncedAt: new Date().toISOString()
        });
      }

      // =========================================================================
      // 10. Single Item Push: /api/tmdb/account/favorite, /watchlist, /rating
      // =========================================================================
      if (request.method === "POST" && path === "/api/tmdb/account/favorite") {
        const body = await request.json().catch(() => ({}));
        const sessionId = (request.headers.get("X-TMDb-Session-Id") || body.sessionId || "").trim();
        const mediaId = parseInt(body.tmdbId || body.mediaId, 10) || 0;
        if (!sessionId || mediaId <= 0) {
          return jsonResponse({ ok: false, error: "Valid sessionId and tmdbId are required." }, 400);
        }
        let accountId = parseInt(body.accountId, 10) || 0;
        if (!accountId) {
          const acc = await tmdbRequest(env, "/account", { params: { session_id: sessionId } });
          accountId = acc.id || 0;
        }
        await tmdbRequest(env, `/account/${accountId}/favorite`, {
          method: "POST",
          params: { session_id: sessionId },
          body: {
            media_type: body.mediaType === "tv" || body.mediaType === "series" ? "tv" : "movie",
            media_id: mediaId,
            favorite: body.favorite !== false
          }
        });
        return jsonResponse({ ok: true, tmdbId: mediaId, favorite: body.favorite !== false });
      }

      if (request.method === "POST" && path === "/api/tmdb/account/watchlist") {
        const body = await request.json().catch(() => ({}));
        const sessionId = (request.headers.get("X-TMDb-Session-Id") || body.sessionId || "").trim();
        const mediaId = parseInt(body.tmdbId || body.mediaId, 10) || 0;
        if (!sessionId || mediaId <= 0) {
          return jsonResponse({ ok: false, error: "Valid sessionId and tmdbId are required." }, 400);
        }
        let accountId = parseInt(body.accountId, 10) || 0;
        if (!accountId) {
          const acc = await tmdbRequest(env, "/account", { params: { session_id: sessionId } });
          accountId = acc.id || 0;
        }
        await tmdbRequest(env, `/account/${accountId}/watchlist`, {
          method: "POST",
          params: { session_id: sessionId },
          body: {
            media_type: body.mediaType === "tv" || body.mediaType === "series" ? "tv" : "movie",
            media_id: mediaId,
            watchlist: body.watchlist !== false
          }
        });
        return jsonResponse({ ok: true, tmdbId: mediaId, watchlist: body.watchlist !== false });
      }

      if (request.method === "POST" && path === "/api/tmdb/account/rating") {
        const body = await request.json().catch(() => ({}));
        const sessionId = (request.headers.get("X-TMDb-Session-Id") || body.sessionId || "").trim();
        const mediaType = body.mediaType === "tv" || body.mediaType === "series" ? "tv" : "movie";
        const mediaId = parseInt(body.tmdbId || body.mediaId, 10) || 0;
        const rating = Number(body.rating);
        if (!sessionId || mediaId <= 0 || Number.isNaN(rating)) {
          return jsonResponse({ ok: false, error: "Valid sessionId, tmdbId, and rating are required." }, 400);
        }
        if (rating <= 0) {
          await tmdbRequest(env, `/${mediaType}/${mediaId}/rating`, {
            method: "DELETE",
            params: { session_id: sessionId }
          });
        } else {
          await tmdbRequest(env, `/${mediaType}/${mediaId}/rating`, {
            method: "POST",
            params: { session_id: sessionId },
            body: { value: Math.min(10.0, Math.max(0.5, Math.round(rating * 2) / 2)) }
          });
        }
        return jsonResponse({ ok: true, tmdbId: mediaId, rating });
      }

      return jsonResponse({ ok: false, error: `Route ${request.method} ${path} not found.` }, 404);
    } catch (err) {
      return jsonResponse(
        { ok: false, error: err.message || "Unexpected proxy error." },
        err.status || 502
      );
    }
  }
};
