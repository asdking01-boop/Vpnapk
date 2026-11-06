/**
 * KRAS TV — Cloudflare Worker Backend for TMDb Metadata Enrichment + Account & Two-Way Sync
 *
 * Required Cloudflare Worker Secret (set via `wrangler secret put TMDB_API_KEY` or Cloudflare Dashboard):
 *   - TMDB_API_KEY : Your TMDb v3 API Key (32-char hex) or v4 Read Access Token (eyJ...)
 *
 * Supported Endpoints:
 *   - GET  /health, /api/tmdb/health, /api/tmdb/connectivity-test
 *   - GET  /api/tmdb/movie?title=...&year=...&tmdbId=...&language=...
 *   - GET  /api/tmdb/tv?title=...&year=...&tmdbId=...&language=...
 *   - GET  /api/tmdb/tv/:tvId/season/:seasonNumber?language=...
 *   - POST /api/tmdb/auth/request-token
 *   - POST /api/tmdb/auth/session
 *   - POST /api/tmdb/auth/logout
 *   - GET  /api/tmdb/account
 *   - POST /api/tmdb/sync
 *   - POST /api/tmdb/account/favorite
 *   - POST /api/tmdb/account/watchlist
 *   - POST /api/tmdb/account/rating
 */

const TMDB_BASE = "https://api.themoviedb.org/3";
const IMG_W500 = "https://image.tmdb.org/t/p/w500";
const IMG_W1280 = "https://image.tmdb.org/t/p/w1280";
const IMG_W185 = "https://image.tmdb.org/t/p/w185";

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
  "Access-Control-Allow-Headers": "Accept, Content-Type, Authorization, X-KRAS-Proxy-Token, X-TMDb-Session-Id, X-Client-App",
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
    .replace(/^(?:[A-Z]{2,4}\s*\|\s*|[A-Z]{2,3}\s+-\s+|(?:EN|US|UK|AR|FR|DE|ES|IT|TR|RU|NL|PT|BR|IN|CA|AU|VIP|VOD)\s*:\s*)+/i, "")
    .replace(/\b(?:S\d{1,2}\s*E\d{1,3}|S\d{1,2}|Season\s*\d{1,2}|Episode\s*\d{1,3})\b.*$/i, "")
    .replace(/\[[^\]]*\]|\([^)]*\)/g, " ")
    .replace(/\b(?:8K|4K|UHD|2160p|1080p|FHD|Full\s*HD|720p|HD|480p|SD|HEVC|H\.?265|H\.?264|x264|x265|HDR10\+?|HDR|DV|Dolby\s*Vision|Atmos|50\s*FPS|60\s*FPS|BluRay|WEB-?DL|WEBRip|BRRip|CAM|MULTI|SUB|DUB|VOSTFR)\b/gi, " ");

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

  const cleanTitle = working.replace(/\s{2,}/g, " ").trim().replace(/^[-|:._]+|[-|:._]+$/g, "").trim();
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
    "User-Agent": "KRAS-TV-Cloudflare-Worker/2.0"
  };

  if (body !== null && body !== undefined) {
    headers["Content-Type"] = "application/json;charset=utf-8";
  }

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

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";

    try {
      // 1. Health & Connectivity
      if (
        request.method === "GET" &&
        (path === "/health" || path === "/api/tmdb/health" || path === "/api/tmdb/connectivity-test")
      ) {
        if (!(env.TMDB_API_KEY || "").trim()) {
          return jsonResponse(
            {
              ok: false,
              status: "unconfigured",
              service: "kras-tv-tmdb-proxy",
              tmdbApiVersion: "v3",
              tmdbConnected: false,
              message: "TMDB_API_KEY secret is not configured."
            },
            503
          );
        }
        const cfg = await tmdbRequest(env, "/configuration");
        const ok = Boolean(cfg?.images?.secure_base_url);
        return jsonResponse({
          ok,
          status: ok ? "connected" : "degraded",
          service: "kras-tv-tmdb-proxy",
          tmdbApiVersion: "v3",
          tmdbConnected: ok,
          imageBaseUrl: cfg?.images?.secure_base_url || "https://image.tmdb.org/t/p/",
          message: "Connected to TMDb API v3 via secure Cloudflare Worker."
        });
      }

      // 2. Movie Metadata Enrichment: GET /api/tmdb/movie
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
            params: { query: searchTitle, year: effectiveYear || undefined, include_adult: "false", language }
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
          genres: genres.join(", "),
          cast: castList.map((c) => c.name).join(", "),
          castList,
          director: directors.join(", "),
          runtimeMinutes: d.runtime || 0
        });
      }

      // 3. TV Series Metadata Enrichment: GET /api/tmdb/tv
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
          genres: genres.join(", "),
          cast: castList.map((c) => c.name).join(", "),
          castList,
          director: creators.join(", "),
          numberOfSeasons: d.number_of_seasons || seasons.length,
          seasons
        });
      }

      // 4. TV Season Episodes: GET /api/tmdb/tv/:tvId/season/:seasonNumber
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

      // 5. TMDb Account Auth: POST /api/tmdb/auth/request-token
      if (request.method === "POST" && path === "/api/tmdb/auth/request-token") {
        const body = await request.json().catch(() => ({}));
        const redirectTo = (body.redirectTo || "krastv://tmdb-auth").trim();
        const tokenRes = await tmdbRequest(env, "/authentication/token/new");
        const requestToken = tokenRes.request_token || "";
        const authUrl = `https://www.themoviedb.org/authenticate/${requestToken}?redirect_to=${encodeURIComponent(redirectTo)}`;
        return jsonResponse({
          ok: true,
          requestToken,
          expiresAt: tokenRes.expires_at || "",
          authUrl
        });
      }

      // 6. TMDb Account Auth: POST /api/tmdb/auth/session
      if (request.method === "POST" && path === "/api/tmdb/auth/session") {
        const body = await request.json().catch(() => ({}));
        const requestToken = (body.requestToken || "").trim();
        if (!requestToken) {
          return jsonResponse({ ok: false, error: "requestToken is required." }, 400);
        }
        const sessionRes = await tmdbRequest(env, "/authentication/session/new", {
          method: "POST",
          body: { request_token: requestToken }
        });
        const sessionId = sessionRes.session_id || "";
        const acc = await tmdbRequest(env, "/account", { params: { session_id: sessionId } });
        return jsonResponse({
          ok: true,
          sessionId,
          account: {
            id: acc.id || 0,
            username: acc.username || "",
            name: acc.name || acc.username || "",
            avatarUrl: buildAvatarUrl(acc)
          }
        });
      }

      // 7. TMDb Account Logout: POST /api/tmdb/auth/logout
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

      // 8. TMDb Two-Way Sync: POST /api/tmdb/sync
      if (request.method === "POST" && path === "/api/tmdb/sync") {
        const body = await request.json().catch(() => ({}));
        const sessionId = (request.headers.get("X-TMDb-Session-Id") || body.sessionId || "").trim();
        if (!sessionId) {
          return jsonResponse({ ok: false, error: "TMDb session required." }, 401);
        }
        const language = (body.language || "en-US").trim();
        let accountId = parseInt(body.accountId, 10) || 0;
        if (!accountId) {
          const acc = await tmdbRequest(env, "/account", { params: { session_id: sessionId } });
          accountId = acc.id || 0;
        }

        let pushedCount = 0;
        for (const fav of (body.favorites || []).slice(0, 60)) {
          if (fav?.tmdbId > 0) {
            await tmdbRequest(env, `/account/${accountId}/favorite`, {
              method: "POST",
              params: { session_id: sessionId },
              body: {
                media_type: fav.mediaType === "tv" ? "tv" : "movie",
                media_id: fav.tmdbId,
                favorite: fav.favorite !== false
              }
            }).catch(() => {});
            pushedCount++;
          }
        }
        for (const wl of (body.watchlist || []).slice(0, 60)) {
          if (wl?.tmdbId > 0) {
            await tmdbRequest(env, `/account/${accountId}/watchlist`, {
              method: "POST",
              params: { session_id: sessionId },
              body: {
                media_type: wl.mediaType === "tv" ? "tv" : "movie",
                media_id: wl.tmdbId,
                watchlist: wl.watchlist !== false
              }
            }).catch(() => {});
            pushedCount++;
          }
        }
        for (const rt of (body.ratings || []).slice(0, 60)) {
          const val = Math.round(Number(rt?.rating || 0) * 2) / 2;
          if (rt?.tmdbId > 0 && val >= 0.5 && val <= 10.0) {
            const mType = rt.mediaType === "tv" ? "tv" : "movie";
            await tmdbRequest(env, `/${mType}/${rt.tmdbId}/rating`, {
              method: "POST",
              params: { session_id: sessionId },
              body: { value: val }
            }).catch(() => {});
            pushedCount++;
          }
        }

        const [favMovies, favTv, wlMovies, wlTv, ratedMovies, ratedTv] = await Promise.all([
          tmdbRequest(env, `/account/${accountId}/favorite/movies`, { params: { session_id: sessionId, language } }).catch(() => ({ results: [] })),
          tmdbRequest(env, `/account/${accountId}/favorite/tv`, { params: { session_id: sessionId, language } }).catch(() => ({ results: [] })),
          tmdbRequest(env, `/account/${accountId}/watchlist/movies`, { params: { session_id: sessionId, language } }).catch(() => ({ results: [] })),
          tmdbRequest(env, `/account/${accountId}/watchlist/tv`, { params: { session_id: sessionId, language } }).catch(() => ({ results: [] })),
          tmdbRequest(env, `/account/${accountId}/rated/movies`, { params: { session_id: sessionId, language } }).catch(() => ({ results: [] })),
          tmdbRequest(env, `/account/${accountId}/rated/tv`, { params: { session_id: sessionId, language } }).catch(() => ({ results: [] }))
        ]);

        return jsonResponse({
          ok: true,
          accountId,
          favorites: [
            ...(favMovies.results || []).map((m) => formatSyncEntry(m, "movie")),
            ...(favTv.results || []).map((t) => formatSyncEntry(t, "tv"))
          ],
          watchlist: [
            ...(wlMovies.results || []).map((m) => formatSyncEntry(m, "movie")),
            ...(wlTv.results || []).map((t) => formatSyncEntry(t, "tv"))
          ],
          ratings: [
            ...(ratedMovies.results || []).map((m) => formatSyncEntry(m, "movie")),
            ...(ratedTv.results || []).map((t) => formatSyncEntry(t, "tv"))
          ],
          pushedCount,
          syncedAt: new Date().toISOString()
        });
      }

      // 9. Single Item Push: /api/tmdb/account/favorite, /watchlist, /rating
      if (request.method === "POST" && path === "/api/tmdb/account/favorite") {
        const body = await request.json().catch(() => ({}));
        const sessionId = (request.headers.get("X-TMDb-Session-Id") || body.sessionId || "").trim();
        let accountId = parseInt(body.accountId, 10) || 0;
        if (!accountId) {
          const acc = await tmdbRequest(env, "/account", { params: { session_id: sessionId } });
          accountId = acc.id || 0;
        }
        await tmdbRequest(env, `/account/${accountId}/favorite`, {
          method: "POST",
          params: { session_id: sessionId },
          body: {
            media_type: body.mediaType === "tv" ? "tv" : "movie",
            media_id: parseInt(body.tmdbId, 10),
            favorite: body.favorite !== false
          }
        });
        return jsonResponse({ ok: true });
      }

      if (request.method === "POST" && path === "/api/tmdb/account/watchlist") {
        const body = await request.json().catch(() => ({}));
        const sessionId = (request.headers.get("X-TMDb-Session-Id") || body.sessionId || "").trim();
        let accountId = parseInt(body.accountId, 10) || 0;
        if (!accountId) {
          const acc = await tmdbRequest(env, "/account", { params: { session_id: sessionId } });
          accountId = acc.id || 0;
        }
        await tmdbRequest(env, `/account/${accountId}/watchlist`, {
          method: "POST",
          params: { session_id: sessionId },
          body: {
            media_type: body.mediaType === "tv" ? "tv" : "movie",
            media_id: parseInt(body.tmdbId, 10),
            watchlist: body.watchlist !== false
          }
        });
        return jsonResponse({ ok: true });
      }

      if (request.method === "POST" && path === "/api/tmdb/account/rating") {
        const body = await request.json().catch(() => ({}));
        const sessionId = (request.headers.get("X-TMDb-Session-Id") || body.sessionId || "").trim();
        const mediaType = body.mediaType === "tv" ? "tv" : "movie";
        const tmdbId = parseInt(body.tmdbId, 10);
        const rating = Number(body.rating);
        if (rating <= 0) {
          await tmdbRequest(env, `/${mediaType}/${tmdbId}/rating`, {
            method: "DELETE",
            params: { session_id: sessionId }
          });
        } else {
          await tmdbRequest(env, `/${mediaType}/${tmdbId}/rating`, {
            method: "POST",
            params: { session_id: sessionId },
            body: { value: Math.min(10.0, Math.max(0.5, Math.round(rating * 2) / 2)) }
          });
        }
        return jsonResponse({ ok: true });
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
