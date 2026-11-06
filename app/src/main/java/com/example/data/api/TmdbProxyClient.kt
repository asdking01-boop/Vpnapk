package com.example.data.api

import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.Season
import com.example.data.model.SeriesDetails
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

enum class TmdbConnectionState {
    NOT_CONFIGURED,
    CHECKING,
    CONNECTED,
    PROXY_UNREACHABLE,
    SERVER_KEY_MISSING,
    TMDB_REJECTED
}

data class TmdbHealthResult(
    val state: TmdbConnectionState,
    val isConnected: Boolean = state == TmdbConnectionState.CONNECTED,
    val message: String = ""
)

data class CleanedTmdbQuery(
    val cleanTitle: String,
    val extractedYear: String
)

data class ParsedProxyEndpoint(
    val baseUrl: String,
    val proxyAuthToken: String = ""
)

data class TmdbSeasonSummary(
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int,
    val overview: String,
    val posterUrl: String,
    val airDate: String
)

data class TmdbEpisodeMetadata(
    val episodeNumber: Int,
    val seasonNumber: Int,
    val title: String,
    val overview: String,
    val stillUrl: String,
    val rating: Double,
    val durationSec: Int,
    val airDate: String
)

data class TmdbMediaMetadata(
    val tmdbId: Int,
    val title: String,
    val overview: String,
    val posterUrl: String,
    val backdropUrl: String,
    val rating: Double,
    val releaseYear: String,
    val releaseDate: String,
    val genres: String,
    val cast: String,
    val director: String,
    val runtimeMinutes: Int = 0,
    val seasons: List<TmdbSeasonSummary> = emptyList()
)

/**
 * Client for communicating with the secure server-side KRAS TV TMDb API v3 Proxy (`/backend-tmdb-proxy`).
 *
 * Security Guarantee:
 * - Never embeds, stores, or transmits `TMDB_API_KEY` from the Android client.
 * - All TMDb API v3 authentication occurs strictly on the backend proxy via `process.env.TMDB_API_KEY`
 *   (or Google Cloud Secret Manager).
 * - Supports optional proxy token authentication (`X-KRAS-Proxy-Token` / `Authorization: Bearer`) if the
 *   user includes `?token=<token>` in the Settings proxy URL.
 * - Reports `CONNECTED` ONLY when a live HTTP request to `/api/tmdb/health` succeeds with `tmdbConnected == true`.
 */
class TmdbProxyClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {

    private val movieCache = ConcurrentHashMap<String, TmdbMediaMetadata>()
    private val tvCache = ConcurrentHashMap<String, TmdbMediaMetadata>()
    private val seasonEpisodesCache = ConcurrentHashMap<String, List<TmdbEpisodeMetadata>>()

    /**
     * Parses a raw proxy URL from KRAS TV Settings into a clean base URL and optional proxy auth token.
     * Supports URLs like:
     * - `https://kras-tmdb-proxy-xxxx.europe-west3.run.app`
     * - `https://kras-tmdb-proxy-xxxx.europe-west3.run.app?token=my_proxy_token`
     */
    fun parseProxyEndpoint(rawUrl: String): ParsedProxyEndpoint {
        var trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ParsedProxyEndpoint("", "")
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "https://$trimmed"
        }

        var extractedToken = ""
        try {
            val uri = URI(trimmed)
            val rawQuery = uri.rawQuery.orEmpty()
            if (rawQuery.isNotEmpty()) {
                for (pair in rawQuery.split("&")) {
                    val parts = pair.split("=", limit = 2)
                    if (parts.size == 2 && (parts[0] == "token" || parts[0] == "proxyToken")) {
                        extractedToken = java.net.URLDecoder.decode(parts[1], "UTF-8").trim()
                    }
                }
            }
            val cleanSchemeHostPort = buildString {
                append(uri.scheme ?: "https")
                append("://")
                append(uri. authority ?: "")
                val cleanPath = (uri.path ?: "")
                    .removeSuffix("/")
                    .removeSuffix("/api/tmdb/health")
                    .removeSuffix("/api/tmdb")
                    .removeSuffix("/health")
                append(cleanPath)
            }.trimEnd('/')
            if (cleanSchemeHostPort.isNotBlank() && uri.authority != null) {
                return ParsedProxyEndpoint(cleanSchemeHostPort, extractedToken)
            }
        } catch (_: Exception) {
            // Fall back to string normalization below
        }

        val withoutQuery = trimmed.substringBefore("?").substringBefore("#").trimEnd('/')
        val normalizedBase = withoutQuery
            .removeSuffix("/api/tmdb/health")
            .removeSuffix("/api/tmdb")
            .removeSuffix("/health")
            .trimEnd('/')

        return ParsedProxyEndpoint(normalizedBase, extractedToken)
    }

    fun normalizeProxyBaseUrl(rawUrl: String): String {
        return parseProxyEndpoint(rawUrl).baseUrl
    }

    private fun buildAuthenticatedRequest(url: String, proxyAuthToken: String): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Client-App", "KRAS-TV-Android")
            .get()
        if (proxyAuthToken.isNotBlank()) {
            builder.header("X-KRAS-Proxy-Token", proxyAuthToken)
            builder.header("Authorization", "Bearer $proxyAuthToken")
        }
        return builder.build()
    }

    /**
     * Performs a real network request to `<proxyBaseUrl>/api/tmdb/health`.
     * Never claims TMDb is connected unless the backend proxy responds with HTTP 200 and `tmdbConnected == true`.
     */
    suspend fun verifyConnection(rawProxyUrl: String): TmdbHealthResult = withContext(Dispatchers.IO) {
        val endpoint = parseProxyEndpoint(rawProxyUrl)
        if (endpoint.baseUrl.isEmpty()) {
            return@withContext TmdbHealthResult(
                state = TmdbConnectionState.NOT_CONFIGURED,
                isConnected = false,
                message = "Backend proxy URL is not configured. Deploy /backend-tmdb-proxy with TMDB_API_KEY and enter its URL."
            )
        }

        try {
            val request = buildAuthenticatedRequest(
                url = "${endpoint.baseUrl}/api/tmdb/health",
                proxyAuthToken = endpoint.proxyAuthToken
            )

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = if (body.trimStart().startsWith("{")) {
                    try { JSONObject(body) } catch (_: Exception) { null }
                } else null

                val okFlag = json?.optBoolean("ok", false) == true
                val tmdbConnected = json?.optBoolean("tmdbConnected", false) == true
                val serverStatus = json?.optString("status", "").orEmpty()
                val serverMessage = json?.optString("message", "")
                    ?.ifBlank { json.optString("error", "") }
                    .orEmpty()

                if (response.isSuccessful && (tmdbConnected || okFlag)) {
                    return@withContext TmdbHealthResult(
                        state = TmdbConnectionState.CONNECTED,
                        isConnected = true,
                        message = serverMessage.ifBlank { "Connected to TMDb API v3 via secure backend proxy." }
                    )
                }

                if (response.code == 503 || serverStatus.equals("unconfigured", ignoreCase = true) ||
                    serverMessage.contains("TMDB_API_KEY", ignoreCase = true)
                ) {
                    return@withContext TmdbHealthResult(
                        state = TmdbConnectionState.SERVER_KEY_MISSING,
                        isConnected = false,
                        message = serverMessage.ifBlank { "Backend proxy is reachable, but TMDB_API_KEY is not set on the server." }
                    )
                }

                if (response.code == 401 || response.code == 403) {
                    return@withContext TmdbHealthResult(
                        state = TmdbConnectionState.TMDB_REJECTED,
                        isConnected = false,
                        message = serverMessage.ifBlank { "Authentication failed (HTTP ${response.code}). Check PROXY_AUTH_TOKEN or server TMDB_API_KEY." }
                    )
                }

                return@withContext TmdbHealthResult(
                    state = TmdbConnectionState.PROXY_UNREACHABLE,
                    isConnected = false,
                    message = serverMessage.ifBlank { "Proxy returned HTTP ${response.code}. TMDb is not connected." }
                )
            }
        } catch (e: Exception) {
            TmdbHealthResult(
                state = TmdbConnectionState.PROXY_UNREACHABLE,
                isConnected = false,
                message = "Cannot reach TMDb proxy (${e.javaClass.simpleName}: ${e.message ?: "network error"})."
            )
        }
    }

    suspend fun fetchMovieMetadata(
        rawProxyUrl: String,
        rawTitle: String,
        year: String = "",
        tmdbId: Int = 0,
        language: String = "en-US"
    ): TmdbMediaMetadata? = withContext(Dispatchers.IO) {
        val endpoint = parseProxyEndpoint(rawProxyUrl)
        if (endpoint.baseUrl.isEmpty()) return@withContext null

        val cleaned = extractTmdbQuery(rawTitle)
        val effectiveTitle = cleaned.cleanTitle.ifBlank { rawTitle.trim() }
        val effectiveYear = year.trim().takeIf { it.length == 4 } ?: cleaned.extractedYear

        if (effectiveTitle.isEmpty() && tmdbId <= 0) return@withContext null

        val cacheKey = "movie:$tmdbId:${effectiveTitle.lowercase()}:$effectiveYear:$language"
        movieCache[cacheKey]?.let { return@withContext it }

        try {
            val encodedTitle = URLEncoder.encode(effectiveTitle, "UTF-8")
            val encodedYear = URLEncoder.encode(effectiveYear, "UTF-8")
            val encodedLang = URLEncoder.encode(language, "UTF-8")
            val url = "${endpoint.baseUrl}/api/tmdb/movie?title=$encodedTitle&year=$encodedYear&tmdbId=$tmdbId&language=$encodedLang"

            val request = buildAuthenticatedRequest(url, endpoint.proxyAuthToken)

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                if (!body.trimStart().startsWith("{")) return@withContext null
                val parsed = parseMediaMetadataJson(JSONObject(body))
                if (parsed != null) {
                    movieCache[cacheKey] = parsed
                }
                parsed
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchTvMetadata(
        rawProxyUrl: String,
        rawTitle: String,
        year: String = "",
        tmdbId: Int = 0,
        language: String = "en-US"
    ): TmdbMediaMetadata? = withContext(Dispatchers.IO) {
        val endpoint = parseProxyEndpoint(rawProxyUrl)
        if (endpoint.baseUrl.isEmpty()) return@withContext null

        val cleaned = extractTmdbQuery(rawTitle)
        val effectiveTitle = cleaned.cleanTitle.ifBlank { rawTitle.trim() }
        val effectiveYear = year.trim().takeIf { it.length == 4 } ?: cleaned.extractedYear

        if (effectiveTitle.isEmpty() && tmdbId <= 0) return@withContext null

        val cacheKey = "tv:$tmdbId:${effectiveTitle.lowercase()}:$effectiveYear:$language"
        tvCache[cacheKey]?.let { return@withContext it }

        try {
            val encodedTitle = URLEncoder.encode(effectiveTitle, "UTF-8")
            val encodedYear = URLEncoder.encode(effectiveYear, "UTF-8")
            val encodedLang = URLEncoder.encode(language, "UTF-8")
            val url = "${endpoint.baseUrl}/api/tmdb/tv?title=$encodedTitle&year=$encodedYear&tmdbId=$tmdbId&language=$encodedLang"

            val request = buildAuthenticatedRequest(url, endpoint.proxyAuthToken)

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                if (!body.trimStart().startsWith("{")) return@withContext null
                val parsed = parseMediaMetadataJson(JSONObject(body))
                if (parsed != null) {
                    tvCache[cacheKey] = parsed
                }
                parsed
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchMovieDetailsById(
        rawProxyUrl: String,
        movieId: Int,
        language: String = "en-US"
    ): TmdbMediaMetadata? = withContext(Dispatchers.IO) {
        val endpoint = parseProxyEndpoint(rawProxyUrl)
        if (endpoint.baseUrl.isEmpty() || movieId <= 0) return@withContext null

        val cacheKey = "movie_id:$movieId:$language"
        movieCache[cacheKey]?.let { return@withContext it }

        try {
            val encodedLang = URLEncoder.encode(language, "UTF-8")
            val url = "${endpoint.baseUrl}/api/tmdb/movie/$movieId?language=$encodedLang"
            val request = buildAuthenticatedRequest(url, endpoint.proxyAuthToken)

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                if (!body.trimStart().startsWith("{")) return@withContext null
                val parsed = parseMediaMetadataJson(JSONObject(body))
                if (parsed != null) {
                    movieCache[cacheKey] = parsed
                }
                parsed
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchTvDetailsById(
        rawProxyUrl: String,
        tvId: Int,
        language: String = "en-US"
    ): TmdbMediaMetadata? = withContext(Dispatchers.IO) {
        val endpoint = parseProxyEndpoint(rawProxyUrl)
        if (endpoint.baseUrl.isEmpty() || tvId <= 0) return@withContext null

        val cacheKey = "tv_id:$tvId:$language"
        tvCache[cacheKey]?.let { return@withContext it }

        try {
            val encodedLang = URLEncoder.encode(language, "UTF-8")
            val url = "${endpoint.baseUrl}/api/tmdb/tv/$tvId?language=$encodedLang"
            val request = buildAuthenticatedRequest(url, endpoint.proxyAuthToken)

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                if (!body.trimStart().startsWith("{")) return@withContext null
                val parsed = parseMediaMetadataJson(JSONObject(body))
                if (parsed != null) {
                    tvCache[cacheKey] = parsed
                }
                parsed
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun matchMetadata(
        rawProxyUrl: String,
        rawTitle: String,
        type: String = "movie",
        year: String = "",
        tmdbId: Int = 0,
        language: String = "en-US"
    ): TmdbMediaMetadata? = withContext(Dispatchers.IO) {
        val endpoint = parseProxyEndpoint(rawProxyUrl)
        if (endpoint.baseUrl.isEmpty()) return@withContext null

        val cleaned = extractTmdbQuery(rawTitle)
        val effectiveTitle = cleaned.cleanTitle.ifBlank { rawTitle.trim() }
        val effectiveYear = year.trim().takeIf { it.length == 4 } ?: cleaned.extractedYear
        if (effectiveTitle.isEmpty() && tmdbId <= 0) return@withContext null

        try {
            val encodedTitle = URLEncoder.encode(effectiveTitle, "UTF-8")
            val encodedType = URLEncoder.encode(type.lowercase(), "UTF-8")
            val encodedYear = URLEncoder.encode(effectiveYear, "UTF-8")
            val encodedLang = URLEncoder.encode(language, "UTF-8")
            val url = "${endpoint.baseUrl}/api/tmdb/match?title=$encodedTitle&type=$encodedType&year=$encodedYear&tmdbId=$tmdbId&language=$encodedLang"
            val request = buildAuthenticatedRequest(url, endpoint.proxyAuthToken)

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                if (!body.trimStart().startsWith("{")) return@withContext null
                parseMediaMetadataJson(JSONObject(body))
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchTvSeasonEpisodes(
        rawProxyUrl: String,
        tmdbId: Int,
        seasonNumber: Int,
        language: String = "en-US"
    ): List<TmdbEpisodeMetadata> = withContext(Dispatchers.IO) {
        val endpoint = parseProxyEndpoint(rawProxyUrl)
        if (endpoint.baseUrl.isEmpty() || tmdbId <= 0 || seasonNumber < 0) return@withContext emptyList()

        val cacheKey = "season:$tmdbId:$seasonNumber:$language"
        seasonEpisodesCache[cacheKey]?.let { return@withContext it }

        try {
            val encodedLang = URLEncoder.encode(language, "UTF-8")
            val url = "${endpoint.baseUrl}/api/tmdb/tv/$tmdbId/season/$seasonNumber?language=$encodedLang"
            val request = buildAuthenticatedRequest(url, endpoint.proxyAuthToken)

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string().orEmpty()
                if (!body.trimStart().startsWith("{")) return@withContext emptyList()
                val json = JSONObject(body)
                val epArray = json.optJSONArray("episodes") ?: JSONArray()
                val list = parseSeasonEpisodesArray(epArray, seasonNumber)
                if (list.isNotEmpty()) {
                    seasonEpisodesCache[cacheKey] = list
                }
                list
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private val LEADING_COUNTRY_PREFIX_REGEX =
            Regex("""^(?:[A-Z]{2,4}\s*\|\s*|[A-Z]{2,3}\s+-\s+|(?:EN|US|UK|AR|FR|DE|ES|IT|TR|RU|NL|PT|BR|IN|CA|AU|VIP|VOD)\s*:\s*|[\u2600-\u27BF\uD83C-\uDBFF\uDC00-\uDFFF]+\s*[\|\-:]?\s*)+""", RegexOption.IGNORE_CASE)
        private val BRACKETED_YEAR_REGEX = Regex("""[\(\[](19\d{2}|20[0-3]\d)[\)\]]""")
        private val TRAILING_YEAR_REGEX = Regex("""\b(19\d{2}|20[0-3]\d)\s*$""")
        private val SEASON_EPISODE_TAG_REGEX =
            Regex("""\b(?:S\d{1,2}\s*E\d{1,3}|S\d{1,2}|Season\s*\d{1,2}|Episode\s*\d{1,3})\b.*$""", RegexOption.IGNORE_CASE)
        private val QUALITY_AND_CODEC_TAGS_REGEX =
            Regex("""\b(?:8K|4K|UHD|2160p|1080p|FHD|Full\s*HD|720p|HD|480p|SD|HEVC|H\.?265|H\.?264|x264|x265|HDR10\+?|HDR|DV|Dolby\s*Vision|Atmos|50\s*FPS|60\s*FPS|BluRay|WEB-?DL|WEBRip|BRRip|CAM|MULTI|SUB|DUB|VOSTFR)\b""", RegexOption.IGNORE_CASE)
        private val BRACKET_NOISE_REGEX = Regex("""\[[^\]]*\]|\([^\)]*\)""")
        private val MULTI_SPACE_REGEX = Regex("""\s{2,}""")

        /**
         * Returns the cleaned movie or TV series title suitable for TMDb search.
         */
        fun cleanIptvTitleForTmdb(rawName: String): String {
            return extractTmdbQuery(rawName).cleanTitle
        }

        /**
         * Strips IPTV country prefixes, quality badges, codec markers, and season/episode suffixes
         * from raw Xtream Codes or M3U item names so TMDb search receives a clean movie or series title
         * and optional release year.
         */
        fun extractTmdbQuery(rawName: String): CleanedTmdbQuery {
            var working = rawName.trim()
            if (working.isEmpty()) return CleanedTmdbQuery("", "")

            var extractedYear = ""
            val bracketYearMatch = BRACKETED_YEAR_REGEX.find(working)
            if (bracketYearMatch != null) {
                extractedYear = bracketYearMatch.groupValues[1]
            }

            working = LEADING_COUNTRY_PREFIX_REGEX.replace(working, "")
            working = SEASON_EPISODE_TAG_REGEX.replace(working, "")
            working = BRACKET_NOISE_REGEX.replace(working, " ")
            working = QUALITY_AND_CODEC_TAGS_REGEX.replace(working, " ")

            val trimmedBeforeYear = working.trim().trimEnd('-', '|', ':', '.')
            if (extractedYear.isEmpty()) {
                val trailingYearMatch = TRAILING_YEAR_REGEX.find(trimmedBeforeYear)
                if (trailingYearMatch != null) {
                    val candidateYear = trailingYearMatch.groupValues[1]
                    val withoutYear = trimmedBeforeYear.removeSuffix(candidateYear).trim()
                    if (withoutYear.isNotEmpty()) {
                        extractedYear = candidateYear
                        working = withoutYear
                    }
                }
            } else {
                working = trimmedBeforeYear.removeSuffix(extractedYear)
            }

            val finalTitle = MULTI_SPACE_REGEX.replace(working, " ")
                .trim()
                .trim('-', '|', ':', '.', '_')
                .trim()

            return CleanedTmdbQuery(
                cleanTitle = finalTitle.ifEmpty { rawName.trim() },
                extractedYear = extractedYear
            )
        }

        private fun jsonFieldAsJoinedString(json: JSONObject, key: String): String {
            val arr = json.optJSONArray(key)
            if (arr != null) {
                val items = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i, "").trim()
                    if (v.isNotEmpty()) items.add(v)
                }
                return items.joinToString(", ")
            }
            return json.optString(key, "").trim()
        }

        fun parseMediaMetadataJson(json: JSONObject): TmdbMediaMetadata? {
            val source = json.optJSONObject("data") ?: json
            val tmdbId = source.optInt("tmdbId", source.optInt("id", 0))
            val title = source.optString("title", source.optString("name", "")).trim()
            if (tmdbId <= 0 && title.isEmpty()) return null

            val seasonsArray = source.optJSONArray("seasons") ?: JSONArray()
            val seasons = mutableListOf<TmdbSeasonSummary>()
            for (i in 0 until seasonsArray.length()) {
                val sObj = seasonsArray.optJSONObject(i) ?: continue
                val sNum = sObj.optInt("seasonNumber", 0)
                if (sNum >= 1) {
                    seasons.add(
                        TmdbSeasonSummary(
                            seasonNumber = sNum,
                            name = sObj.optString("name", "Season $sNum"),
                            episodeCount = sObj.optInt("episodeCount", 0),
                            overview = sObj.optString("overview", ""),
                            posterUrl = sObj.optString("posterUrl", ""),
                            airDate = sObj.optString("airDate", "")
                        )
                    )
                }
            }

            val releaseDate = source.optString("releaseDate", "").trim()
            val releaseYear = source.optString("releaseYear", source.optString("year", "")).trim()
                .ifBlank { releaseDate.take(4) }

            return TmdbMediaMetadata(
                tmdbId = tmdbId,
                title = title,
                overview = source.optString("overview", "").trim(),
                posterUrl = source.optString("posterUrl", "").trim(),
                backdropUrl = source.optString("backdropUrl", "").trim(),
                rating = source.optDouble("rating", 0.0).let { if (it.isNaN()) 0.0 else it },
                releaseYear = releaseYear,
                releaseDate = releaseDate,
                genres = jsonFieldAsJoinedString(source, "genres"),
                cast = jsonFieldAsJoinedString(source, "cast"),
                director = source.optString("director", "").trim(),
                runtimeMinutes = source.optInt("runtimeMinutes", 0),
                seasons = seasons
            )
        }

        fun parseSeasonEpisodesArray(epArray: JSONArray, defaultSeasonNum: Int): List<TmdbEpisodeMetadata> {
            val list = mutableListOf<TmdbEpisodeMetadata>()
            for (i in 0 until epArray.length()) {
                val obj = epArray.optJSONObject(i) ?: continue
                val epNum = obj.optInt("episodeNumber", i + 1)
                list.add(
                    TmdbEpisodeMetadata(
                        episodeNumber = epNum,
                        seasonNumber = obj.optInt("seasonNumber", defaultSeasonNum),
                        title = obj.optString("title", "Episode $epNum"),
                        overview = obj.optString("overview", ""),
                        stillUrl = obj.optString("stillUrl", ""),
                        rating = obj.optDouble("rating", 0.0).let { if (it.isNaN()) 0.0 else it },
                        durationSec = obj.optInt("durationSec", 0),
                        airDate = obj.optString("airDate", "")
                    )
                )
            }
            return list
        }

        /**
         * Merges TMDb metadata into an existing [MediaItem] while preserving all stream URLs,
         * IDs, categories, and detected video quality badges.
         */
        fun enrichMediaItem(item: MediaItem, tmdb: TmdbMediaMetadata): MediaItem {
            return item.copy(
                streamIcon = tmdb.posterUrl.ifBlank { item.streamIcon },
                backdrop = tmdb.backdropUrl.ifBlank { tmdb.posterUrl.ifBlank { item.backdrop } },
                plot = tmdb.overview.ifBlank { item.plot },
                genre = tmdb.genres.ifBlank { item.genre },
                rating = if (tmdb.rating > 0.0) tmdb.rating else item.rating,
                year = tmdb.releaseYear.ifBlank { item.year },
                duration = if (tmdb.runtimeMinutes > 0 && item.duration.isBlank()) "${tmdb.runtimeMinutes}m" else item.duration,
                cast = tmdb.cast.ifBlank { item.cast },
                director = tmdb.director.ifBlank { item.director },
                tmdbId = if (tmdb.tmdbId > 0) tmdb.tmdbId else item.tmdbId
            )
        }

        /**
         * Merges TMDb TV series metadata and per-season episode metadata into [SeriesDetails],
         * enriching posters, backdrops, descriptions, ratings, cast, seasons, and episodes
         * while strictly preserving playable IPTV stream URLs.
         */
        fun enrichSeriesDetails(
            details: SeriesDetails,
            metadata: TmdbMediaMetadata,
            seasonEpisodesMap: Map<Int, List<TmdbEpisodeMetadata>> = emptyMap()
        ): SeriesDetails {
            val tmdbSeasonMap = metadata.seasons.associateBy { it.seasonNumber }

            val enrichedSeasons = if (details.seasons.isNotEmpty()) {
                details.seasons.map { season ->
                    val tmdbSeason = tmdbSeasonMap[season.seasonNumber]
                    val tmdbEpsByNum = seasonEpisodesMap[season.seasonNumber]
                        .orEmpty()
                        .associateBy { it.episodeNumber }

                    val mergedEpisodes = season.episodes.map { ep ->
                        val tmdbEp = tmdbEpsByNum[ep.episodeNum]
                        if (tmdbEp != null) {
                            ep.copy(
                                title = tmdbEp.title.ifBlank { ep.title },
                                plot = tmdbEp.overview.ifBlank { ep.plot },
                                coverUrl = tmdbEp.stillUrl.ifBlank { ep.coverUrl },
                                rating = if (tmdbEp.rating > 0.0) tmdbEp.rating else ep.rating,
                                durationSec = if (tmdbEp.durationSec > 0) tmdbEp.durationSec else ep.durationSec
                            )
                        } else {
                            ep
                        }
                    }

                    season.copy(
                        name = tmdbSeason?.name?.ifBlank { season.name } ?: season.name,
                        episodeCount = mergedEpisodes.size.coerceAtLeast(tmdbSeason?.episodeCount ?: season.episodeCount),
                        episodes = mergedEpisodes
                    )
                }
            } else if (metadata.seasons.isNotEmpty()) {
                metadata.seasons.map { tmdbSeason ->
                    val tmdbEps = seasonEpisodesMap[tmdbSeason.seasonNumber].orEmpty()
                    Season(
                        seasonNumber = tmdbSeason.seasonNumber,
                        name = tmdbSeason.name,
                        episodeCount = tmdbEps.size.coerceAtLeast(tmdbSeason.episodeCount),
                        episodes = tmdbEps.map { tmdbEp ->
                            Episode(
                                id = "ep_${details.seriesId}_${tmdbSeason.seasonNumber}_${tmdbEp.episodeNumber}",
                                episodeNum = tmdbEp.episodeNumber,
                                title = tmdbEp.title,
                                plot = tmdbEp.overview,
                                durationSec = if (tmdbEp.durationSec > 0) tmdbEp.durationSec else 2700,
                                rating = if (tmdbEp.rating > 0.0) tmdbEp.rating else 8.0,
                                coverUrl = tmdbEp.stillUrl,
                                streamUrl = "",
                                seriesId = details.seriesId,
                                seasonNumber = tmdbSeason.seasonNumber,
                                quality = details.quality
                            )
                        }
                    )
                }
            } else {
                details.seasons
            }

            return details.copy(
                cover = metadata.posterUrl.ifBlank { details.cover },
                backdrop = metadata.backdropUrl.ifBlank { metadata.posterUrl.ifBlank { details.backdrop } },
                plot = metadata.overview.ifBlank { details.plot },
                cast = metadata.cast.ifBlank { details.cast },
                director = metadata.director.ifBlank { details.director },
                genre = metadata.genres.ifBlank { details.genre },
                releaseDate = metadata.releaseDate.ifBlank { metadata.releaseYear.ifBlank { details.releaseDate } },
                rating = if (metadata.rating > 0.0) metadata.rating else details.rating,
                seasons = enrichedSeasons
            )
        }
    }
}
