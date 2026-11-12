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
     * Searches TMDb (/search/movie or /search/tv) by cleaned title and year,
     * returning matched metadata if found. Used for syncing and matching IPTV items.
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

        val cleanedTitle = cleanTitleForSearch(rawTitle)
        if (cleanedTitle.isBlank()) return@withContext null

        val endpoint = if (isSeries) "search/tv" else "search/movie"
        val cleanYear = year.filter { it.isDigit() }.take(4)

        val urlBuilder = StringBuilder("$TMDB_BASE_URL/$endpoint?api_key=${URLEncoder.encode(cleanKey, "UTF-8")}")
            .append("&query=${URLEncoder.encode(cleanedTitle, "UTF-8")}")
            .append("&language=${URLEncoder.encode(language, "UTF-8")}")
            .append("&include_adult=false")

        if (cleanYear.length == 4) {
            if (isSeries) {
                urlBuilder.append("&first_air_date_year=$cleanYear")
            } else {
                urlBuilder.append("&year=$cleanYear")
            }
        }

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string().orEmpty()
                val json = parseJsonObjectSafe(body) ?: return@withContext null
                val results = json.optJSONArray("results") ?: return@withContext null
                if (results.length() == 0) return@withContext null

                // Pick the best match (first result)
                val first = results.optJSONObject(0) ?: return@withContext null
                val tmdbId = first.optInt("id", 0)
                if (tmdbId <= 0) return@withContext null

                val title = if (isSeries) {
                    first.optString("name", "").ifBlank { first.optString("original_name", "") }
                } else {
                    first.optString("title", "").ifBlank { first.optString("original_title", "") }
                }
                val releaseDate = if (isSeries) {
                    first.optString("first_air_date", "")
                } else {
                    first.optString("release_date", "")
                }
                val matchedYear = releaseDate.take(4)
                val overview = first.optString("overview", "")
                val posterPath = first.optString("poster_path", "")
                val backdropPath = first.optString("backdrop_path", "")
                val voteAverage = first.optDouble("vote_average", 0.0)

                val posterUrl = if (posterPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else ""
                val backdropUrl = if (backdropPath.isNotBlank()) "https://image.tmdb.org/t/p/w780$backdropPath" else ""

                TmdbMatchedMediaMetadata(
                    tmdbId = tmdbId,
                    title = title,
                    releaseYear = matchedYear,
                    overview = overview,
                    posterUrl = posterUrl,
                    backdropUrl = backdropUrl,
                    rating = voteAverage,
                    genres = "",
                    cast = "",
                    director = ""
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Cleans titles by removing bracketed qualities, release tags, and prefixes.
     */
    private fun cleanTitleForSearch(raw: String): String {
        var s = raw.trim()
        // Remove prefixes like "AR |", "EN:", "FR -", etc.
        s = s.replace(Regex("^(?:[A-Za-z]{2,3}\\s*[:|\\-]\\s*)+", RegexOption.IGNORE_CASE), "")
        // Remove common resolution & quality tags in brackets or parentheses
        s = s.replace(Regex("[\\[\\(]?(?:4K|UHD|FHD|HD|SD|1080p|720p|480p|BluRay|WEB-DL|HDR|HEVC|x264|x265)[\\]\\)]?", RegexOption.IGNORE_CASE), "")
        // Remove 4-digit years in parentheses like (2024)
        s = s.replace(Regex("\\(\\d{4}\\)"), "")
        // Clean multiple spaces and non-word edge chars
        s = s.replace(Regex("\\s+"), " ").trim { it <= ' ' || it == '|' || it == '-' || it == ':' }
        return s
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

    companion object {
        private const val TMDB_BASE_URL = "https://api.themoviedb.org/3"
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

