package com.example.data.api

import com.example.data.model.PendingTmdbMutation
import com.example.data.model.TmdbAuthErrorType
import com.example.data.model.TmdbAuthResult
import com.example.data.model.TmdbMutationAction
import com.example.data.model.TmdbMutationCategory
import com.example.data.model.TmdbSyncItem
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Direct HTTPS client for official TMDb API v3.
 *
 * Requirements:
 * - Operates entirely client-side directly over HTTPS with api.themoviedb.org.
 * - No Cloudflare Worker, no external proxy or intermediate backend.
 * - Never stores or logs user passwords; passwords exist in memory only during the single validate_with_login request.
 * - Handles all errors cleanly (invalid API key, wrong credentials, no internet connection) without crashing.
 * - Runs all network operations off the main thread.
 */
class TmdbDirectClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    private val detailsCache = ConcurrentHashMap<String, TmdbMediaMetadata>()
    private val searchMatchCache = ConcurrentHashMap<String, TmdbMatchedMediaMetadata>()
    private val negativeLookupCache = ConcurrentHashMap<String, Long>()

    /**
     * Authenticates a user directly with TMDb using the official TMDb API v3 login sequence:
     * 1. GET /authentication/token/new?api_key=KEY -> request_token
     * 2. POST /authentication/token/validate_with_login?api_key=KEY -> validates token with username & password
     * 3. POST /authentication/session/new?api_key=KEY -> session_id
     * 4. GET /account?api_key=KEY&session_id=SESSION -> account id, username, avatar
     */
    suspend fun loginWithCredentials(
        apiKey: String,
        username: String,
        password: CharSequence
    ): TmdbAuthResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanUser = username.trim()

        if (cleanKey.isEmpty()) {
            return@withContext TmdbAuthResult.Error(
                message = "Please enter your TMDb API Key (v3).",
                authErrorType = TmdbAuthErrorType.INVALID_API_KEY
            )
        }
        if (cleanUser.isEmpty()) {
            return@withContext TmdbAuthResult.Error(
                message = "Please enter your TMDb username.",
                authErrorType = TmdbAuthErrorType.WRONG_CREDENTIALS
            )
        }
        if (password.isBlank()) {
            return@withContext TmdbAuthResult.Error(
                message = "Please enter your TMDb password.",
                authErrorType = TmdbAuthErrorType.WRONG_CREDENTIALS
            )
        }

        // --- Step 1: Request Token (GET /authentication/token/new) ---
        val step1Url = "$TMDB_BASE_URL/authentication/token/new?api_key=${URLEncoder.encode(cleanKey, "UTF-8")}"
        val step1Req = Request.Builder()
            .url(step1Url)
            .header("Accept", "application/json")
            .get()
            .build()

        val requestToken: String
        try {
            httpClient.newCall(step1Req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errorObj = parseJsonObjectSafe(body)
                    val statusCode = errorObj?.optInt("status_code", 0) ?: 0
                    val statusMsg = errorObj?.optString("status_message", "").orEmpty()
                    return@withContext if (statusCode == 7 || resp.code == 401) {
                        TmdbAuthResult.Error(
                            message = "Invalid TMDb API Key. Please verify your v3 API key.",
                            statusCode = resp.code,
                            authErrorType = TmdbAuthErrorType.INVALID_API_KEY
                        )
                    } else {
                        TmdbAuthResult.Error(
                            message = statusMsg.ifBlank { "Failed to contact TMDb (HTTP ${resp.code})." },
                            statusCode = resp.code,
                            authErrorType = TmdbAuthErrorType.NETWORK_ERROR
                        )
                    }
                }

                val json = parseJsonObjectSafe(body)
                val success = json?.optBoolean("success", false) ?: false
                requestToken = json?.optString("request_token", "").orEmpty().trim()
                if (!success || requestToken.isEmpty()) {
                    return@withContext TmdbAuthResult.Error(
                        message = json?.optString("status_message", "Failed to obtain TMDb request token.").orEmpty(),
                        statusCode = resp.code,
                        authErrorType = TmdbAuthErrorType.SERVICE_UNAVAILABLE
                    )
                }
            }
        } catch (e: Exception) {
            return@withContext mapNetworkExceptionToAuthError(e)
        }

        // --- Step 2: Validate Token with Login (POST /authentication/token/validate_with_login) ---
        val step2Url = "$TMDB_BASE_URL/authentication/token/validate_with_login?api_key=${URLEncoder.encode(cleanKey, "UTF-8")}"
        val step2Payload = JSONObject().apply {
            put("username", cleanUser)
            put("password", password.toString())
            put("request_token", requestToken)
        }
        val step2Req = Request.Builder()
            .url(step2Url)
            .header("Accept", "application/json")
            .post(step2Payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val validatedToken: String
        try {
            httpClient.newCall(step2Req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errorObj = parseJsonObjectSafe(body)
                    val statusCode = errorObj?.optInt("status_code", 0) ?: 0
                    val statusMsg = errorObj?.optString("status_message", "").orEmpty()

                    return@withContext when {
                        statusCode == 7 -> TmdbAuthResult.Error(
                            message = "Invalid TMDb API Key. Please verify your v3 API key.",
                            statusCode = resp.code,
                            authErrorType = TmdbAuthErrorType.INVALID_API_KEY
                        )
                        statusCode == 30 || statusCode == 32 || statusCode == 33 || resp.code == 401 -> TmdbAuthResult.Error(
                            message = "Wrong TMDb username or password. Please check your credentials.",
                            statusCode = resp.code,
                            authErrorType = TmdbAuthErrorType.WRONG_CREDENTIALS
                        )
                        else -> TmdbAuthResult.Error(
                            message = statusMsg.ifBlank { "Invalid TMDb login credentials (HTTP ${resp.code})." },
                            statusCode = resp.code,
                            authErrorType = TmdbAuthErrorType.WRONG_CREDENTIALS
                        )
                    }
                }

                val json = parseJsonObjectSafe(body)
                val success = json?.optBoolean("success", false) ?: false
                validatedToken = json?.optString("request_token", requestToken).orEmpty().trim().ifEmpty { requestToken }
                if (!success) {
                    return@withContext TmdbAuthResult.Error(
                        message = json?.optString("status_message", "Wrong TMDb username or password.").orEmpty(),
                        statusCode = resp.code,
                        authErrorType = TmdbAuthErrorType.WRONG_CREDENTIALS
                    )
                }
            }
        } catch (e: Exception) {
            return@withContext mapNetworkExceptionToAuthError(e)
        }

        // --- Step 3: Create User Session (POST /authentication/session/new) ---
        val step3Url = "$TMDB_BASE_URL/authentication/session/new?api_key=${URLEncoder.encode(cleanKey, "UTF-8")}"
        val step3Payload = JSONObject().apply {
            put("request_token", validatedToken)
        }
        val step3Req = Request.Builder()
            .url(step3Url)
            .header("Accept", "application/json")
            .post(step3Payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val sessionId: String
        try {
            httpClient.newCall(step3Req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errorObj = parseJsonObjectSafe(body)
                    val statusMsg = errorObj?.optString("status_message", "Failed to create TMDb session.").orEmpty()
                    return@withContext TmdbAuthResult.Error(
                        message = statusMsg,
                        statusCode = resp.code,
                        authErrorType = TmdbAuthErrorType.SERVICE_UNAVAILABLE
                    )
                }

                val json = parseJsonObjectSafe(body)
                val success = json?.optBoolean("success", false) ?: false
                sessionId = json?.optString("session_id", "").orEmpty().trim()
                if (!success || sessionId.isEmpty()) {
                    return@withContext TmdbAuthResult.Error(
                        message = json?.optString("status_message", "Failed to create TMDb user session.").orEmpty(),
                        statusCode = resp.code,
                        authErrorType = TmdbAuthErrorType.SERVICE_UNAVAILABLE
                    )
                }
            }
        } catch (e: Exception) {
            return@withContext mapNetworkExceptionToAuthError(e)
        }

        // --- Step 4: Load Account Profile (GET /account) ---
        val step4Url = "$TMDB_BASE_URL/account?api_key=${URLEncoder.encode(cleanKey, "UTF-8")}&session_id=${URLEncoder.encode(sessionId, "UTF-8")}"
        val step4Req = Request.Builder()
            .url(step4Url)
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            httpClient.newCall(step4Req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext TmdbAuthResult.Error(
                        message = "Connected to TMDb, but failed to load account profile (HTTP ${resp.code}).",
                        statusCode = resp.code,
                        authErrorType = TmdbAuthErrorType.NETWORK_ERROR
                    )
                }

                val json = parseJsonObjectSafe(body)
                    ?: return@withContext TmdbAuthResult.Error(
                        message = "Invalid account response received from TMDb.",
                        statusCode = resp.code,
                        authErrorType = TmdbAuthErrorType.NETWORK_ERROR
                    )

                val accountId = json.optInt("id", 0)
                val accountUsername = json.optString("username", cleanUser).trim().ifBlank { cleanUser }
                val displayName = json.optString("name", accountUsername).trim().ifBlank { accountUsername }

                val avatarObj = json.optJSONObject("avatar")
                val tmdbAvatarPath = avatarObj?.optJSONObject("tmdb")?.optString("avatar_path", "").orEmpty().trim()
                val gravatarHash = avatarObj?.optJSONObject("gravatar")?.optString("hash", "").orEmpty().trim()

                val avatarUrl = when {
                    tmdbAvatarPath.isNotEmpty() -> "https://image.tmdb.org/t/p/w200$tmdbAvatarPath"
                    gravatarHash.isNotEmpty() -> "https://www.gravatar.com/avatar/$gravatarHash?s=200"
                    else -> ""
                }

                return@withContext TmdbAuthResult.Success(
                    apiKey = cleanKey,
                    sessionId = sessionId,
                    accountId = accountId,
                    username = accountUsername,
                    displayName = displayName,
                    avatarUrl = avatarUrl
                )
            }
        } catch (e: Exception) {
            return@withContext mapNetworkExceptionToAuthError(e)
        }
    }

    /**
     * Terminates a user session with TMDb via DELETE /authentication/session.
     */
    suspend fun logout(apiKey: String, sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanSession = sessionId.trim()
        if (cleanKey.isEmpty() || cleanSession.isEmpty()) return@withContext true

        try {
            val url = "$TMDB_BASE_URL/authentication/session?api_key=${URLEncoder.encode(cleanKey, "UTF-8")}"
            val payload = JSONObject().apply {
                put("session_id", cleanSession)
            }
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .delete(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (_: Exception) {
            true // Local session is cleared regardless
        }
    }

    /**
     * Performs direct two-way synchronization of Favorites, Watchlist, and Ratings with TMDb.
     */
    suspend fun performTwoWaySync(
        apiKey: String,
        sessionId: String,
        accountId: Int,
        krasUserId: String,
        localFavorites: List<TmdbSyncItem>,
        localWatchlist: List<TmdbSyncItem>,
        localRatings: List<TmdbSyncItem>,
        pendingMutations: List<PendingTmdbMutation>,
        language: String = "en-US",
        onResolveTmdbId: (suspend (title: String, year: String, isSeries: Boolean) -> Int)? = null
    ): TmdbTwoWaySyncResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanSession = sessionId.trim()
        if (cleanKey.isEmpty() || cleanSession.isEmpty() || accountId <= 0) {
            return@withContext TmdbTwoWaySyncResult(
                ok = false,
                userMessage = "TMDb account is not connected."
            )
        }

        try {
            // 1. Fetch Remote Favorites (Movies & TV)
            val remoteFavMovies = fetchPagedList(
                url = "$TMDB_BASE_URL/account/$accountId/favorite/movies?api_key=$cleanKey&session_id=$cleanSession&language=$language",
                mediaType = "MOVIE",
                isFavorite = true,
                inWatchlist = false
            )
            val remoteFavTv = fetchPagedList(
                url = "$TMDB_BASE_URL/account/$accountId/favorite/tv?api_key=$cleanKey&session_id=$cleanSession&language=$language",
                mediaType = "SERIES",
                isFavorite = true,
                inWatchlist = false
            )
            val remoteFavorites = remoteFavMovies + remoteFavTv

            // 2. Fetch Remote Watchlist (Movies & TV)
            val remoteWlMovies = fetchPagedList(
                url = "$TMDB_BASE_URL/account/$accountId/watchlist/movies?api_key=$cleanKey&session_id=$cleanSession&language=$language",
                mediaType = "MOVIE",
                isFavorite = false,
                inWatchlist = true
            )
            val remoteWlTv = fetchPagedList(
                url = "$TMDB_BASE_URL/account/$accountId/watchlist/tv?api_key=$cleanKey&session_id=$cleanSession&language=$language",
                mediaType = "SERIES",
                isFavorite = false,
                inWatchlist = true
            )
            val remoteWatchlist = remoteWlMovies + remoteWlTv

            // 3. Fetch Remote Rated (Movies & TV)
            val remoteRatedMovies = fetchPagedList(
                url = "$TMDB_BASE_URL/account/$accountId/rated/movies?api_key=$cleanKey&session_id=$cleanSession&language=$language",
                mediaType = "MOVIE",
                isFavorite = false,
                inWatchlist = false,
                isRatingList = true
            )
            val remoteRatedTv = fetchPagedList(
                url = "$TMDB_BASE_URL/account/$accountId/rated/tv?api_key=$cleanKey&session_id=$cleanSession&language=$language",
                mediaType = "SERIES",
                isFavorite = false,
                inWatchlist = false,
                isRatingList = true
            )
            val remoteRatings = remoteRatedMovies + remoteRatedTv

            // 4. Push Pending Local Mutations to TMDb
            var pushedFavorites = 0
            var pushedWatchlist = 0
            var pushedRatings = 0

            for (mutation in pendingMutations) {
                var tmdbId = mutation.tmdbId
                val isSeries = mutation.normalizedMediaType == "SERIES"
                if (tmdbId <= 0 && onResolveTmdbId != null && mutation.title.isNotBlank()) {
                    tmdbId = onResolveTmdbId(mutation.title, mutation.year, isSeries)
                }
                if (tmdbId <= 0) continue

                when (mutation.category) {
                    TmdbMutationCategory.FAVORITE -> {
                        val isFav = mutation.action == TmdbMutationAction.ADD
                        val ok = pushFavoriteDirect(cleanKey, cleanSession, accountId, tmdbId, isSeries, isFav)
                        if (ok) pushedFavorites++
                    }
                    TmdbMutationCategory.WATCHLIST -> {
                        val inWl = mutation.action == TmdbMutationAction.ADD
                        val ok = pushWatchlistDirect(cleanKey, cleanSession, accountId, tmdbId, isSeries, inWl)
                        if (ok) pushedWatchlist++
                    }
                    TmdbMutationCategory.RATING -> {
                        val ok = pushRatingDirect(cleanKey, cleanSession, tmdbId, isSeries, mutation.userRating)
                        if (ok) pushedRatings++
                    }
                }
            }

            TmdbTwoWaySyncResult(
                ok = true,
                favorites = remoteFavorites,
                watchlist = remoteWatchlist,
                ratings = remoteRatings,
                pushedFavorites = pushedFavorites,
                pushedWatchlist = pushedWatchlist,
                pushedRatings = pushedRatings,
                userMessage = "TMDb account synchronized successfully."
            )
        } catch (e: Exception) {
            val isNet = e is UnknownHostException || e is SocketTimeoutException || e is ConnectException
            val userMsg = if (isNet) {
                "Unable to sync with TMDb: No internet connection."
            } else {
                "TMDb sync encountered an issue: ${e.localizedMessage ?: "Unknown error"}"
            }
            TmdbTwoWaySyncResult(
                ok = false,
                userMessage = userMsg
            )
        }
    }

    /**
     * Pushes a Favorite change directly to TMDb.
     */
    /**
     * Pushes a Favorite change directly to TMDb.
     */
    suspend fun pushFavoriteDirect(
        apiKey: String,
        sessionId: String,
        accountId: Int,
        tmdbId: Int,
        isSeries: Boolean,
        favorite: Boolean
    ): Boolean = pushFavoriteDirect(
        apiKey = apiKey,
        sessionId = sessionId,
        accountId = accountId,
        mediaType = if (isSeries) "SERIES" else "MOVIE",
        tmdbId = tmdbId,
        favorite = favorite
    )

    suspend fun pushFavoriteDirect(
        apiKey: String,
        sessionId: String,
        accountId: Int,
        mediaType: String,
        tmdbId: Int,
        favorite: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || sessionId.isBlank() || accountId <= 0 || tmdbId <= 0) return@withContext false
        val isSeries = mediaType.equals("tv", ignoreCase = true) || mediaType.equals("series", ignoreCase = true)
        val url = "$TMDB_BASE_URL/account/$accountId/favorite?api_key=${URLEncoder.encode(apiKey, "UTF-8")}&session_id=${URLEncoder.encode(sessionId, "UTF-8")}"
        val payload = JSONObject().apply {
            put("media_type", if (isSeries) "tv" else "movie")
            put("media_id", tmdbId)
            put("favorite", favorite)
        }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            httpClient.newCall(request).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Pushes a Watchlist change directly to TMDb.
     */
    suspend fun pushWatchlistDirect(
        apiKey: String,
        sessionId: String,
        accountId: Int,
        tmdbId: Int,
        isSeries: Boolean,
        watchlist: Boolean
    ): Boolean = pushWatchlistDirect(
        apiKey = apiKey,
        sessionId = sessionId,
        accountId = accountId,
        mediaType = if (isSeries) "SERIES" else "MOVIE",
        tmdbId = tmdbId,
        watchlist = watchlist
    )

    suspend fun pushWatchlistDirect(
        apiKey: String,
        sessionId: String,
        accountId: Int,
        mediaType: String,
        tmdbId: Int,
        watchlist: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || sessionId.isBlank() || accountId <= 0 || tmdbId <= 0) return@withContext false
        val isSeries = mediaType.equals("tv", ignoreCase = true) || mediaType.equals("series", ignoreCase = true)
        val url = "$TMDB_BASE_URL/account/$accountId/watchlist?api_key=${URLEncoder.encode(apiKey, "UTF-8")}&session_id=${URLEncoder.encode(sessionId, "UTF-8")}"
        val payload = JSONObject().apply {
            put("media_type", if (isSeries) "tv" else "movie")
            put("media_id", tmdbId)
            put("watchlist", watchlist)
        }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            httpClient.newCall(request).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Pushes a User Rating change directly to TMDb.
     */
    suspend fun pushRatingDirect(
        apiKey: String,
        sessionId: String,
        tmdbId: Int,
        isSeries: Boolean,
        rating: Double
    ): Boolean = pushRatingDirect(
        apiKey = apiKey,
        sessionId = sessionId,
        mediaType = if (isSeries) "SERIES" else "MOVIE",
        tmdbId = tmdbId,
        rating = rating
    )

    suspend fun pushRatingDirect(
        apiKey: String,
        sessionId: String,
        mediaType: String,
        tmdbId: Int,
        rating: Double
    ): Boolean = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || sessionId.isBlank() || tmdbId <= 0) return@withContext false
        val isSeries = mediaType.equals("tv", ignoreCase = true) || mediaType.equals("series", ignoreCase = true)
        val typeSlug = if (isSeries) "tv" else "movie"
        val url = "$TMDB_BASE_URL/$typeSlug/$tmdbId/rating?api_key=${URLEncoder.encode(apiKey, "UTF-8")}&session_id=${URLEncoder.encode(sessionId, "UTF-8")}"

        val request = if (rating <= 0.0) {
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .delete("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build()
        } else {
            val clamped = Math.max(0.5, Math.min(10.0, Math.round(rating * 2.0) / 2.0))
            val payload = JSONObject().apply { put("value", clamped) }
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }

        try {
            httpClient.newCall(request).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Fetches full TMDb movie or TV details directly by verified [tmdbId]
     * (`/movie/{id}?append_to_response=credits` or `/tv/{id}?append_to_response=credits`).
     */
    suspend fun fetchMediaDetailsById(
        apiKey: String,
        tmdbId: Int,
        isSeries: Boolean,
        language: String = "en-US"
    ): TmdbMediaMetadata? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty() || tmdbId <= 0) return@withContext null

        val typeKey = if (isSeries) "tv" else "movie"
        val preferArabic = language.startsWith("ar", ignoreCase = true)
        val cacheKey = "$typeKey:$tmdbId:${language.lowercase()}"
        detailsCache[cacheKey]?.let { return@withContext it }

        val queryParams = linkedMapOf(
            "append_to_response" to "credits,videos,similar,recommendations",
            "language" to language,
            "include_video_language" to if (preferArabic) "ar,ar-SA,en,null" else "en,null"
        )
        val request = buildAuthorizedGetRequest(
            path = "/$typeKey/$tmdbId",
            apiKeyOrBearer = cleanKey,
            queryParams = queryParams
        )

        try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string().orEmpty()
                val json = parseJsonObjectSafe(body) ?: return@withContext null
                val parsed = parseDirectMediaDetailsJson(json, isSeries, preferArabic) ?: return@withContext null
                detailsCache[cacheKey] = parsed
                parsed
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetches season episode metadata (titles, synopses, still images, ratings, runtimes)
     * directly from TMDb v3 (`/tv/{id}/season/{seasonNumber}`).
     */
    suspend fun fetchTvSeasonEpisodes(
        apiKey: String,
        tvId: Int,
        seasonNumber: Int,
        language: String = "en-US"
    ): List<TmdbEpisodeMetadata> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty() || tvId <= 0 || seasonNumber < 1) return@withContext emptyList()

        val request = buildAuthorizedGetRequest(
            path = "/tv/$tvId/season/$seasonNumber",
            apiKeyOrBearer = cleanKey,
            queryParams = mapOf("language" to language)
        )
        try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val json = parseJsonObjectSafe(resp.body?.string().orEmpty()) ?: return@withContext emptyList()
                val epArr = json.optJSONArray("episodes") ?: return@withContext emptyList()
                val out = mutableListOf<TmdbEpisodeMetadata>()
                for (i in 0 until epArr.length()) {
                    val ep = epArr.optJSONObject(i) ?: continue
                    val epNum = ep.optInt("episode_number", i + 1)
                    val runtimeMin = ep.optInt("runtime", 0)
                    out.add(
                        TmdbEpisodeMetadata(
                            episodeNumber = epNum,
                            seasonNumber = ep.optInt("season_number", seasonNumber),
                            title = ep.optString("name", "Episode $epNum").trim(),
                            overview = ep.optString("overview", "").trim(),
                            stillUrl = TmdbMetadataMatcher.normalizeTmdbImageUrl(ep.optString("still_path", ""), size = "w500"),
                            rating = ep.optDouble("vote_average", 0.0).let { if (it.isNaN()) 0.0 else it },
                            durationSec = if (runtimeMin > 0) runtimeMin * 60 else 0,
                            airDate = ep.optString("air_date", "").trim()
                        )
                    )
                }
                out
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Fetches season episodes localized in Arabic (`ar-SA`) when [preferArabic] is true,
     * automatically merging English (`en-US`) episode descriptions whenever an Arabic episode
     * overview is missing so no episode description is ever empty if English exists.
     */
    suspend fun fetchTvSeasonEpisodesLocalized(
        apiKey: String,
        tvId: Int,
        seasonNumber: Int,
        preferArabic: Boolean
    ): List<TmdbEpisodeMetadata> = withContext(Dispatchers.IO) {
        if (!preferArabic) {
            return@withContext fetchTvSeasonEpisodes(apiKey, tvId, seasonNumber, TmdbMetadataMatcher.TMDB_LANG_ENGLISH)
        }
        val arabicEps = fetchTvSeasonEpisodes(apiKey, tvId, seasonNumber, TmdbMetadataMatcher.TMDB_LANG_ARABIC)
        val needsEnglishFallback = arabicEps.isEmpty() || arabicEps.any { it.overview.isBlank() }
        if (!needsEnglishFallback) return@withContext arabicEps

        val englishEps = fetchTvSeasonEpisodes(apiKey, tvId, seasonNumber, TmdbMetadataMatcher.TMDB_LANG_ENGLISH)
        if (arabicEps.isEmpty()) return@withContext englishEps
        if (englishEps.isEmpty()) return@withContext arabicEps

        val enByNum = englishEps.associateBy { it.episodeNumber }
        arabicEps.map { arEp ->
            val enEp = enByNum[arEp.episodeNumber]
            val validArOverview = TmdbMetadataMatcher.sanitizeSynopsis(arEp.overview, arEp.title)
            val validEnOverview = TmdbMetadataMatcher.sanitizeSynopsis(enEp?.overview, enEp?.title.orEmpty())
            arEp.copy(
                // Keep original/English episode title when Arabic title is blank or generic
                title = enEp?.title?.takeIf { it.isNotBlank() } ?: arEp.title,
                overview = validArOverview.ifEmpty { validEnOverview },
                stillUrl = arEp.stillUrl.ifBlank { enEp?.stillUrl.orEmpty() },
                rating = if (arEp.rating > 0.0) arEp.rating else (enEp?.rating ?: 0.0),
                durationSec = if (arEp.durationSec > 0) arEp.durationSec else (enEp?.durationSec ?: 0),
                airDate = arEp.airDate.ifBlank { enEp?.airDate.orEmpty() }
            )
        }
    }

    /**
     * Fetches actor biography directly from TMDb `/person/{id}` using `language=ar-SA` when
     * [preferArabic] is true, automatically falling back to `en-US` when Arabic biography is
     * unavailable. Always preserves the original actor name.
     */
    suspend fun fetchPersonBiography(
        apiKey: String,
        personId: Int,
        fallbackName: String = "",
        fallbackCharacter: String = "",
        fallbackProfileUrl: String = "",
        preferArabic: Boolean = true
    ): TmdbPersonBiography? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty() || personId <= 0) return@withContext null

        fun fetchSingleLang(lang: String): JSONObject? {
            val req = buildAuthorizedGetRequest(
                path = "/person/$personId",
                apiKeyOrBearer = cleanKey,
                queryParams = mapOf("language" to lang)
            )
            return try {
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else parseJsonObjectSafe(resp.body?.string().orEmpty())
                }
            } catch (_: Exception) {
                null
            }
        }

        val primaryLang = if (preferArabic) TmdbMetadataMatcher.TMDB_LANG_ARABIC else TmdbMetadataMatcher.TMDB_LANG_ENGLISH
        val primaryJson = fetchSingleLang(primaryLang)
        val primaryBio = TmdbMetadataMatcher.cleanSynopsisFormatting(primaryJson?.optString("biography", ""))
        val enJson = if (preferArabic && (primaryJson == null || primaryBio.isBlank())) {
            fetchSingleLang(TmdbMetadataMatcher.TMDB_LANG_ENGLISH)
        } else {
            null
        }
        val enBio = TmdbMetadataMatcher.cleanSynopsisFormatting(enJson?.optString("biography", ""))
        val source = primaryJson ?: enJson ?: return@withContext null

        // Preserve original actor name (prefer fallbackName / en-US name over localized transliterations)
        val enName = enJson?.optString("name", "")?.trim().orEmpty()
        val rawName = source.optString("name", "").trim()
        val preservedName = fallbackName.trim().ifBlank { enName.ifBlank { rawName } }

        val resolvedBio = primaryBio.ifBlank { enBio }
        val resolvedLang = if (preferArabic && primaryBio.isNotBlank()) {
            TmdbMetadataMatcher.TMDB_LANG_ARABIC
        } else {
            TmdbMetadataMatcher.TMDB_LANG_ENGLISH
        }
        val profile = TmdbMetadataMatcher.normalizeTmdbImageUrl(
            source.optString("profile_path", ""),
            size = "w185"
        ).ifBlank { fallbackProfileUrl }

        TmdbPersonBiography(
            personId = personId,
            name = preservedName,
            character = fallbackCharacter,
            biography = resolvedBio,
            birthday = source.optString("birthday", "").takeIf { it != "null" }.orEmpty().trim(),
            placeOfBirth = source.optString("place_of_birth", "").takeIf { it != "null" }.orEmpty().trim(),
            knownForDepartment = source.optString("known_for_department", "").trim(),
            profileUrl = profile,
            languageTag = resolvedLang,
            cachedAtMs = System.currentTimeMillis()
        )
    }

    /**
     * Searches TMDb candidates directly across cleaned bilingual query variants for both
     * smart auto-matching and manual TMDb match selection.
     */
    suspend fun searchCandidates(
        apiKey: String,
        rawQuery: String,
        year: String = "",
        isSeries: Boolean = false,
        preferArabic: Boolean = false
    ): List<TmdbSearchCandidate> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty() || rawQuery.isBlank()) return@withContext emptyList()

        val cleaned = TmdbMetadataMatcher.cleanIptvTitle(rawQuery)
        val effectiveYear = year.filter { it.isDigit() }.take(4).ifBlank { cleaned.extractedYear }
        val queryVariants = TmdbMetadataMatcher.buildSearchQueries(rawQuery)
            .ifEmpty { listOf(rawQuery.trim()) }
            .take(3)
        val endpoint = if (isSeries) "/search/tv" else "/search/movie"
        val languages = if (preferArabic) listOf(TmdbMetadataMatcher.TMDB_LANG_ARABIC, TmdbMetadataMatcher.TMDB_LANG_ENGLISH) else listOf(TmdbMetadataMatcher.TMDB_LANG_ENGLISH)
        val byId = LinkedHashMap<Int, TmdbSearchCandidate>()

        fun runOne(q: String, lang: String, includeYear: Boolean) {
            val params = LinkedHashMap<String, String>()
            params["query"] = q
            params["language"] = lang
            params["include_adult"] = "false"
            if (includeYear && effectiveYear.length == 4) {
                if (isSeries) params["first_air_date_year"] = effectiveYear else params["year"] = effectiveYear
            }
            val request = buildAuthorizedGetRequest(endpoint, cleanKey, params)
            try {
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return
                    val json = parseJsonObjectSafe(resp.body?.string().orEmpty()) ?: return
                    val results = json.optJSONArray("results") ?: return
                    for (i in 0 until results.length().coerceAtMost(15)) {
                        val obj = results.optJSONObject(i) ?: continue
                        val id = obj.optInt("id", 0)
                        if (id <= 0) continue
                        val title = if (isSeries) obj.optString("name", "").trim() else obj.optString("title", "").trim()
                        val origTitle = if (isSeries) obj.optString("original_name", "").trim() else obj.optString("original_title", "").trim()
                        val displayTitle = title.ifBlank { origTitle }
                        if (displayTitle.isBlank()) continue
                        val rDate = if (isSeries) obj.optString("first_air_date", "") else obj.optString("release_date", "")
                        val candYear = rDate.take(4)
                        val posterUrl = TmdbMetadataMatcher.normalizeTmdbImageUrl(obj.optString("poster_path", ""), size = "w500")
                        val backdropUrl = TmdbMetadataMatcher.normalizeTmdbImageUrl(obj.optString("backdrop_path", ""), size = "w1280")
                        val rating = obj.optDouble("vote_average", 0.0).let { if (it.isNaN()) 0.0 else it }
                        val overview = TmdbMetadataMatcher.cleanSynopsisFormatting(obj.optString("overview", ""))
                        val confidence = TmdbMetadataMatcher.scoreCandidateMatch(
                            queryTitle = rawQuery,
                            queryYear = effectiveYear,
                            candidateTitle = title,
                            candidateOriginalTitle = origTitle,
                            candidateYear = candYear
                        )
                        val candidate = TmdbSearchCandidate(
                            tmdbId = id,
                            title = displayTitle,
                            originalTitle = origTitle,
                            releaseYear = candYear,
                            overview = overview,
                            posterUrl = posterUrl,
                            backdropUrl = backdropUrl,
                            rating = rating,
                            isSeries = isSeries,
                            confidenceScore = confidence
                        )
                        val existing = byId[id]
                        if (existing == null || candidate.confidenceScore > existing.confidenceScore ||
                            (existing.overview.isBlank() && candidate.overview.isNotBlank())
                        ) {
                            byId[id] = if (existing != null) {
                                candidate.copy(
                                    overview = candidate.overview.ifBlank { existing.overview },
                                    posterUrl = candidate.posterUrl.ifBlank { existing.posterUrl },
                                    backdropUrl = candidate.backdropUrl.ifBlank { existing.backdropUrl },
                                    confidenceScore = maxOf(candidate.confidenceScore, existing.confidenceScore)
                                )
                            } else {
                                candidate
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        for (q in queryVariants) {
            for (lang in languages) {
                if (effectiveYear.length == 4) runOne(q, lang, includeYear = true)
                if (byId.values.none { it.confidenceScore >= 0.85 }) runOne(q, lang, includeYear = false)
            }
            if (byId.values.any { it.confidenceScore >= 0.92 }) break
        }

        byId.values.sortedWith(
            compareByDescending<TmdbSearchCandidate> { it.confidenceScore }
                .thenByDescending { it.backdropUrl.isNotBlank() }
                .thenByDescending { it.rating }
        ).take(15)
    }

    /**
     * Tests direct TMDb API metadata authorization (`/configuration`) independently of user account auth.
     * Supports both v3 API keys and v4 Bearer Read Access Tokens. Never logs or returns credentials.
     */
    suspend fun testApiConnectivity(apiKey: String): TmdbDiagnosticCheck = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return@withContext TmdbDiagnosticCheck(
                status = TmdbDiagnosticStatus.NOT_CONFIGURED,
                summary = "No direct API key saved (using KRAS TMDb Proxy)"
            )
        }
        val start = System.currentTimeMillis()
        try {
            val req = buildAuthorizedGetRequest("/configuration", cleanKey)
            httpClient.newCall(req).execute().use { resp ->
                val latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                if (resp.isSuccessful) {
                    val mode = if (isBearerToken(cleanKey)) "v4 Bearer" else "v3 API Key"
                    TmdbDiagnosticCheck(
                        status = TmdbDiagnosticStatus.PASSED,
                        summary = "Connected via $mode (${latency}ms)",
                        httpCode = resp.code,
                        latencyMs = latency
                    )
                } else {
                    val body = resp.body?.string().orEmpty()
                    val apiMsg = parseJsonObjectSafe(body)?.optString("status_message", "").orEmpty()
                    val detail = when (resp.code) {
                        401 -> "Unauthorized (HTTP 401): Invalid TMDb API key or token"
                        429 -> "Rate limited (HTTP 429): Too many requests"
                        else -> "HTTP ${resp.code}${if (apiMsg.isNotBlank()) ": ${TmdbErrorSanitizer.sanitize(apiMsg)}" else ""}"
                    }
                    TmdbDiagnosticCheck(
                        status = TmdbDiagnosticStatus.FAILED,
                        summary = detail,
                        httpCode = resp.code,
                        latencyMs = latency
                    )
                }
            }
        } catch (e: Exception) {
            val latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            TmdbDiagnosticCheck(
                status = TmdbDiagnosticStatus.FAILED,
                summary = TmdbErrorSanitizer.sanitize(e.localizedMessage ?: e.javaClass.simpleName),
                latencyMs = latency
            )
        }
    }

    fun clearMemoryCaches() {
        detailsCache.clear()
        searchMatchCache.clear()
        negativeLookupCache.clear()
        trailerKeyCache.clear()
    }

    private fun isBearerToken(key: String): Boolean {
        val k = key.trim()
        return k.startsWith("eyJ") || k.length > 48
    }

    private fun buildAuthorizedGetRequest(
        path: String,
        apiKeyOrBearer: String,
        queryParams: Map<String, String> = emptyMap()
    ): Request {
        val cleanKey = apiKeyOrBearer.trim()
        val useBearer = isBearerToken(cleanKey)
        val sb = StringBuilder("$TMDB_BASE_URL$path")
        var hasQuery = path.contains("?")
        fun appendParam(k: String, v: String) {
            sb.append(if (hasQuery) '&' else '?')
            hasQuery = true
            val encodedValue = if (k == "append_to_response") {
                URLEncoder.encode(v, "UTF-8").replace("%2C", ",")
            } else {
                URLEncoder.encode(v, "UTF-8")
            }
            sb.append(URLEncoder.encode(k, "UTF-8"))
                .append('=')
                .append(encodedValue)
        }
        if (!useBearer) {
            appendParam("api_key", cleanKey)
        }
        for ((k, v) in queryParams) {
            if (v.isNotBlank()) appendParam(k, v)
        }
        val builder = Request.Builder()
            .url(sb.toString())
            .header("Accept", "application/json")
            .get()
        if (useBearer) {
            builder.header("Authorization", "Bearer $cleanKey")
        }
        return builder.build()
    }

    /**
     * Retrieves accurate TMDb metadata (including real overview, genres, cast, director, poster, backdrop)
     * using [tmdbId] when available, or strict title + release year + media type matching otherwise.
     *
     * Language handling:
     * - When [preferArabic] is true, requests `language=ar` first; if the Arabic overview is empty
     *   or invalid, automatically fetches `language=en-US` by the verified TMDb ID and falls back to English.
     * - Never accepts an unconfident search match (strict similarity + ±1 year tolerance).
     */
    suspend fun fetchAccurateMediaMetadata(
        apiKey: String,
        rawTitle: String,
        year: String = "",
        isSeries: Boolean = false,
        tmdbId: Int = 0,
        preferArabic: Boolean = false
    ): TmdbMediaMetadata? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) return@withContext null

        val cleaned = TmdbMetadataMatcher.cleanIptvTitle(rawTitle)
        val effectiveTitle = cleaned.cleanTitle.ifBlank { rawTitle.trim() }
        val effectiveYear = year.filter { it.isDigit() }.take(4).ifBlank { cleaned.extractedYear }
        val primaryLang = if (preferArabic) TmdbMetadataMatcher.TMDB_LANG_ARABIC else TmdbMetadataMatcher.TMDB_LANG_ENGLISH
        val typeKey = if (isSeries) "tv" else "movie"

        val accurateCacheKey = "accurate:$typeKey:$tmdbId:${effectiveTitle.lowercase()}:$effectiveYear:$primaryLang"
        detailsCache[accurateCacheKey]?.let { return@withContext it }

        val negativeTs = negativeLookupCache[accurateCacheKey]
        if (negativeTs != null && (System.currentTimeMillis() - negativeTs) < NEGATIVE_CACHE_TTL_MS) {
            return@withContext null
        }

        // Resolve verified TMDb ID (direct ID when provided, else strict bilingual candidate search)
        val resolvedId = if (tmdbId > 0) {
            tmdbId
        } else {
            val candidates = searchCandidates(
                apiKey = cleanKey,
                rawQuery = rawTitle,
                year = effectiveYear,
                isSeries = isSeries,
                preferArabic = preferArabic
            )
            candidates.firstOrNull { it.confidenceScore > 0.0 }?.tmdbId ?: 0
        }

        if (resolvedId <= 0) {
            negativeLookupCache[accurateCacheKey] = System.currentTimeMillis()
            return@withContext null
        }

        val primaryDetails = fetchMediaDetailsById(
            apiKey = cleanKey,
            tmdbId = resolvedId,
            isSeries = isSeries,
            language = primaryLang
        )

        val finalMetadata = if (preferArabic) {
            val primaryValidOverview = TmdbMetadataMatcher.sanitizeSynopsis(primaryDetails?.overview, effectiveTitle)
            val hasMissingSeasonOverview = isSeries && primaryDetails?.seasons?.any { it.overview.isBlank() } == true
            val needsEnglishMerge = primaryDetails == null ||
                primaryValidOverview.isEmpty() ||
                primaryDetails.backdropUrl.isBlank() ||
                primaryDetails.trailerYoutubeKey.isBlank() ||
                primaryDetails.castMembers.isEmpty() ||
                primaryDetails.similarItems.isEmpty() ||
                hasMissingSeasonOverview ||
                TmdbMetadataMatcher.containsArabicScript(primaryDetails.title)
            if (!needsEnglishMerge && primaryDetails != null) {
                primaryDetails.copy(
                    title = primaryDetails.originalTitle.ifBlank { primaryDetails.title },
                    overview = primaryValidOverview,
                    genres = TmdbMetadataMatcher.localizeGenres(primaryDetails.genres, preferArabic = true)
                )
            } else {
                val englishDetails = fetchMediaDetailsById(
                    apiKey = cleanKey,
                    tmdbId = resolvedId,
                    isSeries = isSeries,
                    language = TmdbMetadataMatcher.TMDB_LANG_ENGLISH
                )
                mergePreferredWithFallback(primaryDetails, englishDetails, effectiveTitle, preferArabic = true)
            }
        } else {
            primaryDetails?.copy(
                title = primaryDetails.title.ifBlank { primaryDetails.originalTitle },
                overview = TmdbMetadataMatcher.sanitizeSynopsis(primaryDetails.overview, effectiveTitle),
                genres = TmdbMetadataMatcher.localizeGenres(primaryDetails.genres, preferArabic = false)
            )
        }

        if (finalMetadata != null) {
            detailsCache[accurateCacheKey] = finalMetadata
        } else {
            negativeLookupCache[accurateCacheKey] = System.currentTimeMillis()
        }
        finalMetadata
    }

    /**
     * Searches TMDb (/search/movie or /search/tv) by cleaned title and year,
     * strictly verifying candidate title similarity and release year tolerance (±1 year)
     * so an unrelated movie/series is never matched.
     */
    suspend fun searchAndMatchMedia(
        apiKey: String,
        rawTitle: String,
        year: String = "",
        isSeries: Boolean = false,
        language: String = "en-US"
    ): TmdbMatchedMediaMetadata? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty() || rawTitle.isBlank()) return@withContext null

        val cleaned = TmdbMetadataMatcher.cleanIptvTitle(rawTitle)
        val cleanedTitle = cleaned.cleanTitle.ifBlank { cleanTitleForSearch(rawTitle) }
        if (cleanedTitle.isBlank()) return@withContext null

        val cleanYear = year.filter { it.isDigit() }.take(4).ifBlank { cleaned.extractedYear }
        val typeKey = if (isSeries) "tv" else "movie"
        val cacheKey = "search:$typeKey:${cleanedTitle.lowercase()}:$cleanYear:${language.lowercase()}"
        searchMatchCache[cacheKey]?.let { return@withContext it }

        val negativeTs = negativeLookupCache[cacheKey]
        if (negativeTs != null && (System.currentTimeMillis() - negativeTs) < NEGATIVE_CACHE_TTL_MS) {
            return@withContext null
        }

        // First search with year constraint if available; if empty, retry without query year
        // (strict year tolerance is still enforced during candidate scoring)
        var best = executeStrictSearch(cleanKey, cleanedTitle, cleanYear, isSeries, language, includeYearParam = cleanYear.length == 4)
        if (best == null && cleanYear.length == 4) {
            best = executeStrictSearch(cleanKey, cleanedTitle, cleanYear, isSeries, language, includeYearParam = false)
        }

        if (best != null) {
            searchMatchCache[cacheKey] = best
        } else {
            negativeLookupCache[cacheKey] = System.currentTimeMillis()
        }
        best
    }

    private fun executeStrictSearch(
        cleanKey: String,
        cleanedTitle: String,
        expectedYear: String,
        isSeries: Boolean,
        language: String,
        includeYearParam: Boolean
    ): TmdbMatchedMediaMetadata? {
        val endpoint = if (isSeries) "search/tv" else "search/movie"
        val urlBuilder = StringBuilder("$TMDB_BASE_URL/$endpoint?api_key=${URLEncoder.encode(cleanKey, "UTF-8")}")
            .append("&query=${URLEncoder.encode(cleanedTitle, "UTF-8")}")
            .append("&language=${URLEncoder.encode(language, "UTF-8")}")
            .append("&include_adult=false")

        if (includeYearParam && expectedYear.length == 4) {
            if (isSeries) {
                urlBuilder.append("&first_air_date_year=$expectedYear")
            } else {
                urlBuilder.append("&year=$expectedYear")
            }
        }

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                val json = parseJsonObjectSafe(body) ?: return null
                val results = json.optJSONArray("results") ?: return null
                if (results.length() == 0) return null

                var bestScore = 0.0
                var bestMatch: TmdbMatchedMediaMetadata? = null

                for (i in 0 until results.length().coerceAtMost(10)) {
                    val item = results.optJSONObject(i) ?: continue
                    val tmdbId = item.optInt("id", 0)
                    if (tmdbId <= 0) continue

                    val title = if (isSeries) {
                        item.optString("name", "").trim()
                    } else {
                        item.optString("title", "").trim()
                    }
                    val originalTitle = if (isSeries) {
                        item.optString("original_name", "").trim()
                    } else {
                        item.optString("original_title", "").trim()
                    }
                    val displayTitle = title.ifBlank { originalTitle }
                    val releaseDate = if (isSeries) {
                        item.optString("first_air_date", "")
                    } else {
                        item.optString("release_date", "")
                    }
                    val matchedYear = releaseDate.take(4)

                    val score = TmdbMetadataMatcher.scoreCandidateMatch(
                        queryTitle = cleanedTitle,
                        queryYear = expectedYear,
                        candidateTitle = title,
                        candidateOriginalTitle = originalTitle,
                        candidateYear = matchedYear
                    )
                    if (score <= 0.0) continue

                    val overview = TmdbMetadataMatcher.sanitizeSynopsis(item.optString("overview", ""), displayTitle)
                    // Small tie-breaker for candidates that have a verified synopsis
                    val adjustedScore = score + if (overview.isNotEmpty()) 0.02 else 0.0

                    if (adjustedScore > bestScore) {
                        bestScore = adjustedScore
                        val posterPath = item.optString("poster_path", "").takeIf { it != "null" }.orEmpty()
                        val backdropPath = item.optString("backdrop_path", "").takeIf { it != "null" }.orEmpty()
                        val voteAverage = item.optDouble("vote_average", 0.0).let { if (it.isNaN()) 0.0 else it }

                        bestMatch = TmdbMatchedMediaMetadata(
                            tmdbId = tmdbId,
                            title = displayTitle,
                            releaseYear = matchedYear,
                            overview = overview,
                            posterUrl = if (posterPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else "",
                            backdropUrl = if (backdropPath.isNotBlank()) "https://image.tmdb.org/t/p/w780$backdropPath" else "",
                            rating = voteAverage,
                            genres = "",
                            cast = "",
                            director = ""
                        )
                    }
                }
                bestMatch
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDirectMediaDetailsJson(json: JSONObject, isSeries: Boolean, preferArabic: Boolean = false): TmdbMediaMetadata? {
        val tmdbId = json.optInt("id", 0)
        val title = if (isSeries) {
            json.optString("name", "").trim().ifBlank { json.optString("original_name", "").trim() }
        } else {
            json.optString("title", "").trim().ifBlank { json.optString("original_title", "").trim() }
        }
        val originalTitle = if (isSeries) {
            json.optString("original_name", "").trim()
        } else {
            json.optString("original_title", "").trim()
        }
        if (tmdbId <= 0 && title.isEmpty()) return null

        val releaseDate = if (isSeries) {
            json.optString("first_air_date", "").trim()
        } else {
            json.optString("release_date", "").trim()
        }
        val releaseYear = releaseDate.take(4)

        val posterPath = json.optString("poster_path", "").takeIf { it != "null" && it.isNotBlank() }.orEmpty()
        val backdropPath = json.optString("backdrop_path", "").takeIf { it != "null" && it.isNotBlank() }.orEmpty()
        val posterUrl = if (posterPath.isNotEmpty()) "https://image.tmdb.org/t/p/w500$posterPath" else ""
        val backdropUrl = if (backdropPath.isNotEmpty()) "https://image.tmdb.org/t/p/w1280$backdropPath" else ""

        val genresArr = json.optJSONArray("genres")
        val genresList = mutableListOf<String>()
        if (genresArr != null) {
            for (i in 0 until genresArr.length()) {
                val g = genresArr.optJSONObject(i)?.optString("name", "")?.trim().orEmpty()
                if (g.isNotEmpty()) genresList.add(g)
            }
        }

        val credits = json.optJSONObject("credits")
        val castArr = credits?.optJSONArray("cast")
        val castMembers = mutableListOf<TmdbCastMember>()
        val castList = mutableListOf<String>()
        if (castArr != null) {
            for (i in 0 until castArr.length().coerceAtMost(12)) {
                val cObj = castArr.optJSONObject(i) ?: continue
                // Preserve original actor name (TMDb `original_name` keeps the actor's original Latin/native name)
                val name = cObj.optString("original_name", "").trim()
                    .ifBlank { cObj.optString("name", "").trim() }
                if (name.isNotEmpty()) {
                    val profileUrl = TmdbMetadataMatcher.normalizeTmdbImageUrl(
                        cObj.optString("profile_path", ""),
                        size = "w185"
                    )
                    castMembers.add(
                        TmdbCastMember(
                            id = cObj.optInt("id", 0),
                            name = name,
                            character = cObj.optString("character", "").trim(),
                            profileUrl = profileUrl
                        )
                    )
                    if (castList.size < 10) castList.add(name)
                }
            }
        }

        var director = ""
        if (isSeries) {
            val createdBy = json.optJSONArray("created_by")
            if (createdBy != null && createdBy.length() > 0) {
                val creators = mutableListOf<String>()
                for (i in 0 until createdBy.length().coerceAtMost(3)) {
                    val name = createdBy.optJSONObject(i)?.optString("name", "")?.trim().orEmpty()
                    if (name.isNotEmpty()) creators.add(name)
                }
                director = creators.joinToString(", ")
            }
        }
        if (director.isEmpty()) {
            val crewArr = credits?.optJSONArray("crew")
            if (crewArr != null) {
                for (i in 0 until crewArr.length()) {
                    val member = crewArr.optJSONObject(i) ?: continue
                    if (member.optString("job", "").equals("Director", ignoreCase = true)) {
                        director = member.optString("name", "").trim()
                        if (director.isNotEmpty()) break
                    }
                }
            }
        }

        val runtimeMinutes = if (isSeries) {
            val epRunTimes = json.optJSONArray("episode_run_time")
            val fromArr = if (epRunTimes != null && epRunTimes.length() > 0) epRunTimes.optInt(0, 0) else 0
            if (fromArr > 0) fromArr else json.optJSONObject("last_episode_to_air")?.optInt("runtime", 0) ?: 0
        } else {
            json.optInt("runtime", 0)
        }

        val seasonsList = mutableListOf<TmdbSeasonSummary>()
        if (isSeries) {
            val seasonsArr = json.optJSONArray("seasons")
            if (seasonsArr != null) {
                for (i in 0 until seasonsArr.length()) {
                    val sObj = seasonsArr.optJSONObject(i) ?: continue
                    val sNum = sObj.optInt("season_number", 0)
                    if (sNum >= 1) {
                        val sPoster = TmdbMetadataMatcher.normalizeTmdbImageUrl(sObj.optString("poster_path", ""), size = "w500")
                        seasonsList.add(
                            TmdbSeasonSummary(
                                seasonNumber = sNum,
                                name = sObj.optString("name", "Season $sNum").trim(),
                                episodeCount = sObj.optInt("episode_count", 0),
                                overview = sObj.optString("overview", "").trim(),
                                posterUrl = sPoster,
                                airDate = sObj.optString("air_date", "").trim()
                            )
                        )
                    }
                }
            }
        }

        // Official YouTube trailer key from appended videos (prefer Arabic audio/subtitles when preferArabic is enabled)
        var trailerYoutubeKey = ""
        var trailerLanguage = ""
        val videosArr = json.optJSONObject("videos")?.optJSONArray("results")
        if (videosArr != null) {
            var bestScore = -1
            for (i in 0 until videosArr.length()) {
                val obj = videosArr.optJSONObject(i) ?: continue
                val site = obj.optString("site", "")
                val key = obj.optString("key", "").trim()
                if (!site.equals("YouTube", ignoreCase = true) || key.isBlank()) continue
                val type = obj.optString("type", "")
                val official = obj.optBoolean("official", false)
                val isoLang = obj.optString("iso_639_1", "").lowercase()
                val videoName = obj.optString("name", "").lowercase()
                val isArabicSubOrDub = isoLang == "ar" ||
                    videoName.contains("مترجم") ||
                    videoName.contains("عربي") ||
                    videoName.contains("arabic")
                val baseScore = when {
                    type.equals("Trailer", ignoreCase = true) && official -> 100
                    type.equals("Trailer", ignoreCase = true) -> 80
                    type.equals("Teaser", ignoreCase = true) && official -> 60
                    type.equals("Teaser", ignoreCase = true) -> 40
                    else -> 10
                }
                val langBonus = if (preferArabic) {
                    when {
                        isoLang == "ar" -> 50
                        isArabicSubOrDub -> 40
                        isoLang == "en" -> 15
                        else -> 0
                    }
                } else {
                    when {
                        isoLang == "en" -> 30
                        else -> 0
                    }
                }
                val score = baseScore + langBonus
                if (score > bestScore) {
                    bestScore = score
                    trailerYoutubeKey = key
                    trailerLanguage = when {
                        isArabicSubOrDub -> "ar"
                        isoLang.isNotBlank() -> isoLang
                        else -> "en"
                    }
                }
            }
        }

        // Similar & recommended titles (preserve original/English titles)
        val similarItems = mutableListOf<TmdbSimilarItem>()
        val seenSimilar = HashSet<Int>()
        fun collectSimilar(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                if (similarItems.size >= 12) return
                val s = arr.optJSONObject(i) ?: continue
                val sid = s.optInt("id", 0)
                if (sid <= 0 || !seenSimilar.add(sid)) continue
                val sLocalizedTitle = if (isSeries) {
                    s.optString("name", "").trim()
                } else {
                    s.optString("title", "").trim()
                }
                val sOrigTitle = if (isSeries) {
                    s.optString("original_name", "").trim()
                } else {
                    s.optString("original_title", "").trim()
                }
                val sTitle = if (preferArabic) {
                    sOrigTitle.ifBlank { sLocalizedTitle }
                } else {
                    sLocalizedTitle.ifBlank { sOrigTitle }
                }
                if (sTitle.isBlank()) continue
                val sDate = if (isSeries) s.optString("first_air_date", "") else s.optString("release_date", "")
                similarItems.add(
                    TmdbSimilarItem(
                        tmdbId = sid,
                        title = sTitle,
                        originalTitle = sOrigTitle,
                        posterUrl = TmdbMetadataMatcher.normalizeTmdbImageUrl(s.optString("poster_path", ""), size = "w500"),
                        backdropUrl = TmdbMetadataMatcher.normalizeTmdbImageUrl(s.optString("backdrop_path", ""), size = "w1280"),
                        releaseYear = sDate.take(4),
                        rating = s.optDouble("vote_average", 0.0).let { if (it.isNaN()) 0.0 else it },
                        isSeries = isSeries
                    )
                )
            }
        }
        collectSimilar(json.optJSONObject("recommendations")?.optJSONArray("results"))
        collectSimilar(json.optJSONObject("similar")?.optJSONArray("results"))

        val rawOverview = json.optString("overview", "").trim()
        val rating = json.optDouble("vote_average", 0.0).let { if (it.isNaN()) 0.0 else it }
        val resolvedGenres = TmdbMetadataMatcher.localizeGenres(
            genresList.joinToString(if (preferArabic) "، " else ", "),
            preferArabic = preferArabic
        )
        val preservedTitle = if (preferArabic) {
            originalTitle.ifBlank { title }
        } else {
            title.ifBlank { originalTitle }
        }

        return TmdbMediaMetadata(
            tmdbId = tmdbId,
            title = preservedTitle,
            overview = rawOverview,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            rating = rating,
            releaseYear = releaseYear,
            releaseDate = releaseDate,
            genres = resolvedGenres,
            cast = castList.joinToString(", "),
            director = director,
            runtimeMinutes = runtimeMinutes,
            seasons = seasonsList,
            originalTitle = originalTitle,
            castMembers = castMembers,
            trailerYoutubeKey = trailerYoutubeKey,
            trailerLanguage = trailerLanguage,
            similarItems = similarItems,
            cachedAtMs = System.currentTimeMillis()
        )
    }

    private fun mergePreferredWithFallback(
        preferred: TmdbMediaMetadata?,
        fallback: TmdbMediaMetadata?,
        mediaTitle: String,
        preferArabic: Boolean = false
    ): TmdbMediaMetadata? {
        if (preferred == null) {
            return fallback?.copy(
                title = fallback.title.ifBlank { fallback.originalTitle },
                overview = TmdbMetadataMatcher.sanitizeSynopsis(fallback.overview, mediaTitle),
                genres = TmdbMetadataMatcher.localizeGenres(fallback.genres, preferArabic = preferArabic)
            )
        }
        if (fallback == null) {
            return preferred.copy(
                title = if (preferArabic) preferred.originalTitle.ifBlank { preferred.title } else preferred.title.ifBlank { preferred.originalTitle },
                overview = TmdbMetadataMatcher.sanitizeSynopsis(preferred.overview, mediaTitle),
                genres = TmdbMetadataMatcher.localizeGenres(preferred.genres, preferArabic = preferArabic)
            )
        }
        val preferredOverview = TmdbMetadataMatcher.sanitizeSynopsis(preferred.overview, mediaTitle)
        val fallbackOverview = TmdbMetadataMatcher.sanitizeSynopsis(fallback.overview, mediaTitle)

        // Keep original/English movie & series titles while localizing overview, genres, and seasons
        val preservedTitle = fallback.title.ifBlank {
            preferred.originalTitle.ifBlank { fallback.originalTitle.ifBlank { preferred.title } }
        }

        // Merge seasons per season number so missing Arabic season descriptions fall back to English
        val fallbackSeasonsByNum = fallback.seasons.associateBy { it.seasonNumber }
        val mergedSeasons = if (preferred.seasons.isNotEmpty()) {
            preferred.seasons.map { prefSeason ->
                val enSeason = fallbackSeasonsByNum[prefSeason.seasonNumber]
                val prefSeasonOverview = TmdbMetadataMatcher.sanitizeSynopsis(prefSeason.overview, prefSeason.name)
                val enSeasonOverview = enSeason?.let { TmdbMetadataMatcher.sanitizeSynopsis(it.overview, it.name) }.orEmpty()
                prefSeason.copy(
                    name = prefSeason.name.ifBlank { enSeason?.name ?: "Season ${prefSeason.seasonNumber}" },
                    overview = prefSeasonOverview.ifEmpty { enSeasonOverview },
                    posterUrl = prefSeason.posterUrl.ifBlank { enSeason?.posterUrl.orEmpty() },
                    airDate = prefSeason.airDate.ifBlank { enSeason?.airDate.orEmpty() },
                    episodeCount = prefSeason.episodeCount.coerceAtLeast(enSeason?.episodeCount ?: 0)
                )
            }
        } else {
            fallback.seasons
        }

        // Preserve original actor names from English/original credits
        val mergedCastMembers = if (fallback.castMembers.isNotEmpty()) {
            fallback.castMembers
        } else {
            preferred.castMembers
        }
        val mergedCast = fallback.cast.ifBlank { preferred.cast }
        val mergedDirector = fallback.director.ifBlank { preferred.director }

        val mergedGenres = TmdbMetadataMatcher.localizeGenres(
            preferred.genres.ifBlank { fallback.genres },
            preferArabic = preferArabic
        )

        // Prefer Arabic trailer when preferArabic is enabled and an Arabic trailer exists; fall back to English
        val usePreferredTrailer = preferred.trailerYoutubeKey.isNotBlank() &&
            (!preferArabic || preferred.trailerLanguage == "ar" || fallback.trailerYoutubeKey.isBlank())
        val mergedTrailerKey = if (usePreferredTrailer) preferred.trailerYoutubeKey else fallback.trailerYoutubeKey
        val mergedTrailerLang = if (usePreferredTrailer) preferred.trailerLanguage else fallback.trailerLanguage

        return preferred.copy(
            title = preservedTitle,
            overview = preferredOverview.ifEmpty { fallbackOverview },
            posterUrl = preferred.posterUrl.ifBlank { fallback.posterUrl },
            backdropUrl = preferred.backdropUrl.ifBlank { fallback.backdropUrl },
            genres = mergedGenres,
            cast = mergedCast,
            castMembers = mergedCastMembers,
            director = mergedDirector,
            releaseYear = preferred.releaseYear.ifBlank { fallback.releaseYear },
            releaseDate = preferred.releaseDate.ifBlank { fallback.releaseDate },
            rating = if (preferred.rating > 0.0) preferred.rating else fallback.rating,
            runtimeMinutes = if (preferred.runtimeMinutes > 0) preferred.runtimeMinutes else fallback.runtimeMinutes,
            seasons = mergedSeasons,
            originalTitle = preferred.originalTitle.ifBlank { fallback.originalTitle },
            trailerYoutubeKey = mergedTrailerKey,
            trailerLanguage = mergedTrailerLang,
            similarItems = if (fallback.similarItems.isNotEmpty()) fallback.similarItems else preferred.similarItems
        )
    }

    /**
     * Cleans titles by removing IPTV prefixes, bracketed qualities, release tags, and years.
     */
    private fun cleanTitleForSearch(raw: String): String {
        return TmdbMetadataMatcher.cleanIptvTitle(raw).cleanTitle
    }

    private suspend fun fetchPagedList(
        url: String,
        mediaType: String,
        isFavorite: Boolean,
        inWatchlist: Boolean,
        isRatingList: Boolean = false
    ): List<TmdbSyncItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<TmdbSyncItem>()
        var page = 1
        var totalPages = 1
        val maxPagesToFetch = 5 // Fetch up to first 5 pages (100 items) to prevent high bandwidth/latency

        while (page <= totalPages && page <= maxPagesToFetch) {
            val pageUrl = if (url.contains("?")) "$url&page=$page" else "$url?page=$page"
            val req = Request.Builder()
                .url(pageUrl)
                .header("Accept", "application/json")
                .get()
                .build()

            try {
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext items
                    val body = resp.body?.string().orEmpty()
                    val json = parseJsonObjectSafe(body) ?: return@withContext items

                    totalPages = json.optInt("total_pages", 1)
                    val results = json.optJSONArray("results") ?: return@withContext items
                    val isTv = mediaType.equals("SERIES", ignoreCase = true) || mediaType.equals("tv", ignoreCase = true)

                    for (i in 0 until results.length()) {
                        val obj = results.optJSONObject(i) ?: continue
                        val tmdbId = obj.optInt("id", 0)
                        if (tmdbId <= 0) continue

                        val title = if (isTv) {
                            obj.optString("name", "").ifBlank { obj.optString("original_name", "") }
                        } else {
                            obj.optString("title", "").ifBlank { obj.optString("original_title", "") }
                        }
                        val releaseDate = if (isTv) {
                            obj.optString("first_air_date", "")
                        } else {
                            obj.optString("release_date", "")
                        }
                        val year = releaseDate.take(4)
                        val posterPath = obj.optString("poster_path", "")
                        val posterUrl = if (posterPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else ""
                        val rating = obj.optDouble("vote_average", 0.0)
                        val userRating = if (isRatingList) obj.optDouble("rating", 0.0) else 0.0

                        items.add(
                            TmdbSyncItem(
                                tmdbId = tmdbId,
                                mediaType = if (isTv) "SERIES" else "MOVIE",
                                title = title,
                                posterUrl = posterUrl,
                                releaseYear = year,
                                rating = rating,
                                userRating = userRating,
                                favorite = isFavorite,
                                watchlist = inWatchlist
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                break
            }
            page++
        }

        items
    }

    private val trailerKeyCache = ConcurrentHashMap<String, String>()

    /**
     * Fetches the official YouTube video key for a movie or TV series directly from TMDb v3
     * (/movie/{id}/videos or /tv/{id}/videos). Returns null when unavailable or offline.
     * Never downloads or extracts raw media streams.
     */
    suspend fun fetchOfficialYoutubeTrailerKey(
        apiKey: String,
        tmdbId: Int,
        isSeries: Boolean,
        preferArabic: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty() || tmdbId <= 0) return@withContext null

        val langTag = if (preferArabic) TmdbMetadataMatcher.TMDB_LANG_ARABIC else TmdbMetadataMatcher.TMDB_LANG_ENGLISH
        val cacheKey = "${if (isSeries) "tv" else "movie"}_${tmdbId}_$langTag"
        trailerKeyCache[cacheKey]?.let { cached ->
            return@withContext cached.ifEmpty { null }
        }

        val mediaSegment = if (isSeries) "tv" else "movie"
        val request = buildAuthorizedGetRequest(
            path = "/$mediaSegment/$tmdbId/videos",
            apiKeyOrBearer = cleanKey,
            queryParams = mapOf(
                "language" to langTag,
                "include_video_language" to if (preferArabic) "ar,ar-SA,en,null" else "en,null"
            )
        )

        val foundKey = try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use ""
                val json = parseJsonObjectSafe(resp.body?.string().orEmpty()) ?: return@use ""
                val results = json.optJSONArray("results") ?: return@use ""
                var bestKey = ""
                var bestScore = -1
                for (i in 0 until results.length()) {
                    val obj = results.optJSONObject(i) ?: continue
                    val site = obj.optString("site", "")
                    val key = obj.optString("key", "").trim()
                    if (!site.equals("YouTube", ignoreCase = true) || key.isBlank()) continue
                    val type = obj.optString("type", "")
                    val official = obj.optBoolean("official", false)
                    val isoLang = obj.optString("iso_639_1", "").lowercase()
                    val vName = obj.optString("name", "")
                    val isArabicVideo = isoLang == "ar" ||
                        vName.contains("مترجم") ||
                        vName.contains("عربي") ||
                        vName.contains("arabic", ignoreCase = true)
                    val baseScore = when {
                        type.equals("Trailer", ignoreCase = true) && official -> 100
                        type.equals("Trailer", ignoreCase = true) -> 80
                        type.equals("Teaser", ignoreCase = true) && official -> 60
                        type.equals("Teaser", ignoreCase = true) -> 40
                        else -> 10
                    }
                    val langBonus = when {
                        preferArabic && isArabicVideo -> 50
                        preferArabic && isoLang == "en" -> 15
                        !preferArabic && isoLang == "en" -> 30
                        else -> 0
                    }
                    val score = baseScore + langBonus
                    if (score > bestScore) {
                        bestScore = score
                        bestKey = key
                    }
                }
                bestKey
            }
        } catch (_: Exception) {
            ""
        }

        trailerKeyCache[cacheKey] = foundKey
        foundKey.ifEmpty { null }
    }

    companion object {
        private const val TMDB_BASE_URL = "https://api.themoviedb.org/3"
        private const val NEGATIVE_CACHE_TTL_MS = 10 * 60 * 1000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun parseJsonObjectSafe(raw: String): JSONObject? {
            if (raw.isBlank()) return null
            return try {
                JSONObject(raw)
            } catch (_: Exception) {
                null
            }
        }

        private fun mapNetworkExceptionToAuthError(e: Exception): TmdbAuthResult.Error {
            val isOffline = e is UnknownHostException || e is SocketTimeoutException || e is ConnectException
            return if (isOffline) {
                TmdbAuthResult.Error(
                    message = "No internet connection. Please check your network and try again.",
                    authErrorType = TmdbAuthErrorType.NO_INTERNET
                )
            } else {
                TmdbAuthResult.Error(
                    message = "Network error connecting to TMDb: ${e.localizedMessage ?: "Unknown error"}",
                    authErrorType = TmdbAuthErrorType.NETWORK_ERROR
                )
            }
        }
    }
}

/**
 * Matched metadata returned by searchAndMatchMedia.
 */
data class TmdbMatchedMediaMetadata(
    val tmdbId: Int,
    val title: String,
    val releaseYear: String = "",
    val overview: String = "",
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val rating: Double = 0.0,
    val genres: String = "",
    val cast: String = "",
    val director: String = ""
)

