/**
 * KRAS TV — Secure Server-Side TMDb Proxy
 *
 * Security Architecture:
 * - Reads TMDB_API_KEY exclusively from the server environment (process.env.TMDB_API_KEY).
 * - Supports both TMDb v3 API keys (32-char hex) and TMDb v4 Bearer Read Access Tokens (JWT).
 * - Never exposes TMDB_API_KEY in HTTP responses, headers, or error messages.
 * - Caches TMDb responses in memory (TTL 6 hours) to minimize latency and respect TMDb rate limits
 *   during large Xtream Codes and M3U playlist imports.
 */

require("dotenv").config();
const express = require("express");
const cors = require("cors");

const app = express();
app.use(cors());
app.use(express.json());

const PORT = process.env.PORT || 8080;
const TMDB_BASE_URL = "https://api.themoviedb.org/3";
const TMDB_IMAGE_BASE_W500 = "https://image.tmdb.org/t/p/w500";
const TMDB_IMAGE_BASE_ORIGINAL = "https://image.tmdb.org/t/p/w1280";

// Simple bounded in-memory TTL cache (max 2,000 entries, 6-hour TTL)
const CACHE_TTL_MS = 6 * 60 * 60 * 1000;
const MAX_CACHE_ENTRIES = 2000;
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

function getTmdbKey() {
  const raw = (process.env.TMDB_API_KEY || "").trim();
  if (!raw || raw === "your_server_side_tmdb_api_key_here") {
    return null;
  }
  return raw;
}

async function tmdbFetch(path, queryParams = {}) {
  const apiKey = getTmdbKey();
  if (!apiKey) {
    const err = new Error("TMDB_API_KEY is not configured on the backend server.");
    err.statusCode = 503;
    throw err;
  }

  const url = new URL(`${TMDB_BASE_URL}${path}`);
  const headers = { Accept: "application/json" };

  // Support both v4 JWT Bearer tokens and v3 api_key query param
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

  const response = await fetch(url.toString(), { headers });
  if (!response.ok) {
    const err = new Error(`TMDb upstream returned HTTP ${response.status}`);
    err.statusCode = response.status;
    throw err;
  }
  return response.json();
}

function buildPosterUrl(path) {
  return path ? `${TMDB_IMAGE_BASE_W500}${path}` : "";
}

function buildBackdropUrl(path) {
  return path ? `${TMDB_IMAGE_BASE_ORIGINAL}${path}` : "";
}

/**
 * 1. Health & Live TMDb Connectivity Verification Endpoint
 * GET /api/tmdb/health
 */
app.get("/api/tmdb/health", async (req, res) => {
  const apiKey = getTmdbKey();
  if (!apiKey) {
    return res.status(503).json({
      status: "unconfigured",
      tmdbConnected: false,
      message: "TMDB_API_KEY environment variable is not set on the server."
    });
  }

  try {
    const config = await tmdbFetch("/configuration");
    const hasImagesConfig = Boolean(config && config.images && config.images.secure_base_url);
    return res.json({
      status: "ok",
      tmdbConnected: hasImagesConfig,
      message: "Connected to TMDb API via secure server-side proxy."
    });
  } catch (err) {
    return res.status(err.statusCode || 502).json({
      status: "error",
      tmdbConnected: false,
      message: err.message || "Failed to reach TMDb API."
    });
  }
});

/**
 * 2. Movie Metadata Lookup (Search by title/year or direct tmdbId + Details & Credits)
 * GET /api/tmdb/movie?title=Inception&year=2010&tmdbId=27205&language=en-US
 */
app.get("/api/tmdb/movie", async (req, res) => {
  const title = (req.query.title || "").trim();
  const year = (req.query.year || "").trim();
  const language = (req.query.language || "en-US").trim();
  const tmdbIdParam = parseInt(req.query.tmdbId || "0", 10);

  if (!title && !(tmdbIdParam > 0)) {
    return res.status(400).json({ error: "Either 'title' or 'tmdbId' query parameter is required." });
  }

  const cacheKey = `movie:${tmdbIdParam}:${title.toLowerCase()}:${year}:${language}`;
  const cached = getCached(cacheKey);
  if (cached) {
    return res.json(cached);
  }

  try {
    let movieId = tmdbIdParam > 0 ? tmdbIdParam : 0;

    if (!movieId) {
      const searchData = await tmdbFetch("/search/movie", {
        query: title,
        year: year || undefined,
        include_adult: "false",
        language
      });
      let results = Array.isArray(searchData.results) ? searchData.results : [];
      if (results.length === 0 && year) {
        // Retry without strict year filter if provider year was inaccurate
        const fallbackSearch = await tmdbFetch("/search/movie", {
          query: title,
          include_adult: "false",
          language
        });
        results = Array.isArray(fallbackSearch.results) ? fallbackSearch.results : [];
      }
      if (results.length === 0) {
        return res.status(404).json({ error: "No matching movie found on TMDb." });
      }
      movieId = results[0].id;
    }

    const details = await tmdbFetch(`/movie/${movieId}`, {
      append_to_response: "credits",
      language
    });

    const genres = Array.isArray(details.genres)
      ? details.genres.map((g) => g.name).filter(Boolean).join(", ")
      : "";
    const castList = details.credits && Array.isArray(details.credits.cast)
      ? details.credits.cast.slice(0, 8).map((c) => c.name).filter(Boolean).join(", ")
      : "";
    const directorList = details.credits && Array.isArray(details.credits.crew)
      ? details.credits.crew
          .filter((c) => c.job === "Director")
          .map((c) => c.name)
          .filter(Boolean)
          .join(", ")
      : "";
    const releaseDate = details.release_date || "";
    const releaseYear = releaseDate.length >= 4 ? releaseDate.substring(0, 4) : year;

    const payload = {
      tmdbId: details.id || movieId,
      title: details.title || details.original_title || title,
      overview: details.overview || "",
      posterUrl: buildPosterUrl(details.poster_path),
      backdropUrl: buildBackdropUrl(details.backdrop_path),
      rating: typeof details.vote_average === "number" ? Number(details.vote_average.toFixed(1)) : 0.0,
      releaseYear: releaseYear || "",
      releaseDate,
      genres,
      cast: castList,
      director: directorList,
      runtimeMinutes: details.runtime || 0
    };

    setCached(cacheKey, payload);
    return res.json(payload);
  } catch (err) {
    return res.status(err.statusCode || 500).json({
      error: err.message || "Failed to fetch movie metadata from TMDb."
    });
  }
});

/**
 * 3. TV Series Metadata Lookup (Search by title/year or direct tmdbId + Details, Credits & Seasons)
 * GET /api/tmdb/tv?title=Breaking+Bad&year=2008&tmdbId=1396&language=en-US
 */
app.get("/api/tmdb/tv", async (req, res) => {
  const title = (req.query.title || "").trim();
  const year = (req.query.year || "").trim();
  const language = (req.query.language || "en-US").trim();
  const tmdbIdParam = parseInt(req.query.tmdbId || "0", 10);

  if (!title && !(tmdbIdParam > 0)) {
    return res.status(400).json({ error: "Either 'title' or 'tmdbId' query parameter is required." });
  }

  const cacheKey = `tv:${tmdbIdParam}:${title.toLowerCase()}:${year}:${language}`;
  const cached = getCached(cacheKey);
  if (cached) {
    return res.json(cached);
  }

  try {
    let tvId = tmdbIdParam > 0 ? tmdbIdParam : 0;

    if (!tvId) {
      const searchData = await tmdbFetch("/search/tv", {
        query: title,
        first_air_date_year: year || undefined,
        include_adult: "false",
        language
      });
      let results = Array.isArray(searchData.results) ? searchData.results : [];
      if (results.length === 0 && year) {
        const fallbackSearch = await tmdbFetch("/search/tv", {
          query: title,
          include_adult: "false",
          language
        });
        results = Array.isArray(fallbackSearch.results) ? fallbackSearch.results : [];
      }
      if (results.length === 0) {
        return res.status(404).json({ error: "No matching TV series found on TMDb." });
      }
      tvId = results[0].id;
    }

    const details = await tmdbFetch(`/tv/${tvId}`, {
      append_to_response: "credits",
      language
    });

    const genres = Array.isArray(details.genres)
      ? details.genres.map((g) => g.name).filter(Boolean).join(", ")
      : "";
    const castList = details.credits && Array.isArray(details.credits.cast)
      ? details.credits.cast.slice(0, 8).map((c) => c.name).filter(Boolean).join(", ")
      : "";
    const creators = Array.isArray(details.created_by)
      ? details.created_by.map((c) => c.name).filter(Boolean).join(", ")
      : "";
    const firstAirDate = details.first_air_date || "";
    const releaseYear = firstAirDate.length >= 4 ? firstAirDate.substring(0, 4) : year;

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

    const payload = {
      tmdbId: details.id || tvId,
      title: details.name || details.original_name || title,
      overview: details.overview || "",
      posterUrl: buildPosterUrl(details.poster_path),
      backdropUrl: buildBackdropUrl(details.backdrop_path),
      rating: typeof details.vote_average === "number" ? Number(details.vote_average.toFixed(1)) : 0.0,
      releaseYear: releaseYear || "",
      releaseDate: firstAirDate,
      genres,
      cast: castList,
      director: creators,
      seasons
    };

    setCached(cacheKey, payload);
    return res.json(payload);
  } catch (err) {
    return res.status(err.statusCode || 500).json({
      error: err.message || "Failed to fetch TV series metadata from TMDb."
    });
  }
});

/**
 * 4. TV Season Episodes Metadata Lookup
 * GET /api/tmdb/tv/:tvId/season/:seasonNumber?language=en-US
 */
app.get("/api/tmdb/tv/:tvId/season/:seasonNumber", async (req, res) => {
  const tvId = parseInt(req.params.tvId || "0", 10);
  const seasonNumber = parseInt(req.params.seasonNumber || "1", 10);
  const language = (req.query.language || "en-US").trim();

  if (!(tvId > 0)) {
    return res.status(400).json({ error: "Valid tvId is required." });
  }

  const cacheKey = `tv_season:${tvId}:${seasonNumber}:${language}`;
  const cached = getCached(cacheKey);
  if (cached) {
    return res.json(cached);
  }

  try {
    const seasonData = await tmdbFetch(`/tv/${tvId}/season/${seasonNumber}`, {
      language
    });

    const episodes = Array.isArray(seasonData.episodes)
      ? seasonData.episodes.map((ep) => ({
          episodeNumber: ep.episode_number || 1,
          seasonNumber: ep.season_number || seasonNumber,
          title: ep.name || `Episode ${ep.episode_number || 1}`,
          overview: ep.overview || "",
          stillUrl: buildPosterUrl(ep.still_path),
          rating: typeof ep.vote_average === "number" ? Number(ep.vote_average.toFixed(1)) : 0.0,
          durationSec: ep.runtime ? ep.runtime * 60 : 0,
          airDate: ep.air_date || ""
        }))
      : [];

    const payload = {
      tmdbId: tvId,
      seasonNumber: seasonData.season_number || seasonNumber,
      name: seasonData.name || `Season ${seasonNumber}`,
      overview: seasonData.overview || "",
      posterUrl: buildPosterUrl(seasonData.poster_path),
      episodes
    };

    setCached(cacheKey, payload);
    return res.json(payload);
  } catch (err) {
    return res.status(err.statusCode || 500).json({
      error: err.message || "Failed to fetch season episodes from TMDb."
    });
  }
});

if (require.main === module) {
  app.listen(PORT, () => {
    console.log(`KRAS TV TMDb Proxy listening on port ${PORT}`);
  });
}

module.exports = app;
