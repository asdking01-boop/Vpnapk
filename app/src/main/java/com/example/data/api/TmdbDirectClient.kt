package com.example.data.api

import com.example.data.model.CastMember
import com.example.data.model.PendingTmdbMutation
import com.example.data.model.SimilarMedia
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

    data class TmdbCompleteDetails(
        val tmdbId: Int,
        val title: String,
        val originalTitle: String = "",
        val overview: String = "",
        val posterUrl: String = "",
        val backdropUrl: String = "",
        val rating: Double = 0.0,
        val releaseYear: String = "",
        val releaseDate: String = "",
        val duration: String = "",
        val genres: List<String> = emptyList(),
        val director: String = "",
        val cast: List<CastMember> = emptyList(),
        val youtubeTrailerKey: String = "",
        val similar: List<SimilarMedia> = emptyList(),
        val seasons: List<TmdbSeasonSummary> = emptyList()
    )

    /**
     * Cleans an IPTV title by removing prefixes ("AR |", "EN:", etc.), quality badges ("4K", "FHD", "HD", etc.),
     * years in parens "(2024)", and bracketed tags like "[Dubbed]", "(Sub)", returning clean title and extracted year.
     */
    fun cleanMediaTitle(rawTitle: String): Pair<String, String> {
        var text = rawTitle.trim()

        // 1. Remove prefixes like "AR |", "EN:", "FR -", "TR |", "VIP |", "OSN |", "NETFLIX |", etc.
        text = text.replace(Regex("""^(?:[A-Z]{2,3}|VIP|OSN|NETFLIX|BEIN|SHAHID)\s*[|:\-]\s*""", RegexOption.IGNORE_CASE), "")

        // 2. Extract year if present in parens or brackets: (2024), [2023]
        val yearMatch = Regex("""[\[\(](19\d{2}|20\d{2})[\]\)]""").find(text)
        val extractedYear = yearMatch?.groupValues?.get(1) ?: ""
        text = text.replace(Regex("""[\[\(](19\d{2}|20\d{2})[\]\)]"""), "")

        // 3. Remove quality tags: 4K, UHD, FHD, HD, SD, 1080p, 720p, 2160p, HEVC, H.265, H.264, HDR, 10bit
        text = text.replace(Regex("""\b(?:4K|UHD|FHD|HD|SD|1080p|720p|2160p|HEVC|H\.?265|H\.?264|HDR(?:10\+?)?|10bit|50\s*fps|60\s*fps)\b""", RegexOption.IGNORE_CASE), "")

        // 4. Remove tags like [Dubbed], (Sub), (Arabic), [Multi], [Eng], etc.
        text = text.replace(Regex("""[\[\(](?:dubbed|subbed?|arabic|english|french|hindi|multi(?:-audio)?|translated|original|bluray|web-?dl|remux)[\]\)]""", RegexOption.IGNORE_CASE), "")

        // 5. Remove any leftover square brackets like [xyz]
        text = text.replace(Regex("""\[[^\]]*\]"""), "")

        // 6. Remove symbols, emojis, and trim
        text = text.replace(Regex("""[\p{So}\p{Sk}]"""), "")
        text = text.replace(Regex("""\s+"""), " ").trim()
        text = text.trimEnd('-', '|', ':', ' ')

        return Pair(text.ifBlank { rawTitle.trim() }, extractedYear)
    }

    /**
     * Computes similarity between two titles using Dice bigram coefficient and token overlap.
     */
    fun calculateTitleSimilarity(s1: String, s2: String): Double {
        val a = s1.lowercase().replace(PUNCTUATION_REGEX, " ").replace(Regex("\\s+"), " ").trim()
        val b = s2.lowercase().replace(PUNCTUATION_REGEX, " ").replace(Regex("\\s+"), " ").trim()
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0

        val wordsA = a.split(" ").filter { it.length > 1 }.toSet()
        val wordsB = b.split(" ").filter { it.length > 1 }.toSet()
        if (wordsA.isNotEmpty() && wordsB.isNotEmpty()) {
            val intersection = wordsA.intersect(wordsB).size
            val tokenSim = (2.0 * intersection) / (wordsA.size + wordsB.size)
            if (tokenSim >= 0.75) return tokenSim
        }

        val bigramsA = (0 until a.length - 1).map { a.substring(it, it + 2) }
        val bigramsB = (0 until b.length - 1).map { b.substring(it, it + 2) }
        if (bigramsA.isEmpty() || bigramsB.isEmpty()) {
            return if (a.contains(b) || b.contains(a)) 0.7 else 0.0
        }
        val matches = bigramsA.intersect(bigramsB.toSet()).size
        return (2.0 * matches) / (bigramsA.size + bigramsB.size)
    }

    /**
     * Searches TMDb for matching title and year, enforcing title similarity >= 0.60
     * and year within +/- 1 year. Returns matched tmdbId or -1 if no match found.
     */
    suspend fun findTmdbMatch(
        apiKey: String,
        rawTitle: String,
        year: String = "",
        isSeries: Boolean = false
    ): Int = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) return@withContext 0

        val (cleanTitle, parsedYear) = cleanMediaTitle(rawTitle)
        val targetYear = year.trim().takeIf { it.length == 4 } ?: parsedYear
        if (cleanTitle.isBlank()) return@withContext -1

        val typeSlug = if (isSeries) "tv" else "movie"
        val encodedQuery = URLEncoder.encode(cleanTitle, "UTF-8")
        val yearParam = if (targetYear.length == 4) {
            if (isSeries) "&first_air_date_year=$targetYear" else "&year=$targetYear"
        } else ""

        val url = "$TMDB_BASE_URL/search/$typeSlug?api_key=$cleanKey&query=$encodedQuery$yearParam&page=1"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            var response = httpClient.newCall(request).execute()
            if (response.code == 429) {
                response.close()
                delay(1500L)
                response = httpClient.newCall(request).execute()
            }

            val body = response.use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }
            if (body.isBlank()) return@withContext -1

            val json = parseJsonObjectSafe(body) ?: return@withContext -1
            val results = json.optJSONArray("results") ?: return@withContext -1
            if (results.length() == 0) return@withContext -1

            var bestMatchId = -1
            var bestScore = 0.0

            val targetYearInt = targetYear.toIntOrNull()

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val id = item.optInt("id", 0)
                if (id <= 0) continue

                val itemTitle = item.optString("title", item.optString("name", "")).trim()
                val origTitle = item.optString("original_title", item.optString("original_name", "")).trim()
                val releaseDate = item.optString("release_date", item.optString("first_air_date", "")).trim()
                val itemYearInt = releaseDate.take(4).toIntOrNull()

                // Year within 1 year check
                if (targetYearInt != null && itemYearInt != null) {
                    if (Math.abs(targetYearInt - itemYearInt) > 1) {
                        continue // Reject if year deviates by more than 1 year
                    }
                }

                val simTitle = calculateTitleSimilarity(cleanTitle, itemTitle)
                val simOrig = calculateTitleSimilarity(cleanTitle, origTitle)
                val maxSim = maxOf(simTitle, simOrig)

                if (maxSim >= 0.60 && maxSim > bestScore) {
                    bestScore = maxSim
                    bestMatchId = id
                }
            }

            bestMatchId
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * Fetches complete media details including credits (cast & director), videos (YouTube trailer),
     * and similar media. If language is Arabic and overview is empty, falls back to English overview.
     */
    suspend fun fetchCompleteMediaDetails(
        apiKey: String,
        tmdbId: Int,
        isSeries: Boolean,
        language: String = "en-US"
    ): TmdbCompleteDetails? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty() || tmdbId <= 0) return@withContext null

        val typeSlug = if (isSeries) "tv" else "movie"
        val url = "$TMDB_BASE_URL/$typeSlug/$tmdbId?api_key=$cleanKey&language=$language&append_to_response=credits,videos,similar"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            var response = httpClient.newCall(request).execute()
            if (response.code == 429) {
                response.close()
                delay(1500L)
                response = httpClient.newCall(request).execute()
            }

            val body = response.use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }
            if (body.isBlank()) return@withContext null

            val json = parseJsonObjectSafe(body) ?: return@withContext null
            var parsed = parseCompleteDetailsJson(json, isSeries)

            // Fallback for Arabic: if overview is empty, fetch English overview
            if (language.startsWith("ar", ignoreCase = true) && parsed?.overview.isNullOrBlank()) {
                val enUrl = "$TMDB_BASE_URL/$typeSlug/$tmdbId?api_key=$cleanKey&language=en-US"
                val enReq = Request.Builder().url(enUrl).header("Accept", "application/json").get().build()
                try {
                    val enBody = httpClient.newCall(enReq).execute().use { resp ->
                        if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
                    }
                    val enJson = parseJsonObjectSafe(enBody)
                    val enOverview = enJson?.optString("overview", "").orEmpty().trim()
                    if (enOverview.isNotBlank() && parsed != null) {
                        parsed = parsed.copy(overview = enOverview)
                    }
                } catch (_: Exception) {
                }
            }

            parsed
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCompleteDetailsJson(json: JSONObject, isSeries: Boolean): TmdbCompleteDetails? {
        val tmdbId = json.optInt("id", 0)
        val title = json.optString("title", json.optString("name", "")).trim()
        val origTitle = json.optString("original_title", json.optString("original_name", "")).trim()
        if (tmdbId <= 0 && title.isEmpty()) return null

        val posterPath = json.optString("poster_path", "").trim()
        val backdropPath = json.optString("backdrop_path", "").trim()
        val posterUrl = if (posterPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else ""
        val backdropUrl = if (backdropPath.isNotBlank()) "https://image.tmdb.org/t/p/w780$backdropPath" else ""

        val releaseDate = json.optString("release_date", json.optString("first_air_date", "")).trim()
        val releaseYear = releaseDate.take(4)

        val genresList = mutableListOf<String>()
        val genresArr = json.optJSONArray("genres")
        if (genresArr != null) {
            for (i in 0 until genresArr.length()) {
                val g = genresArr.optJSONObject(i)?.optString("name", "").orEmpty().trim()
                if (g.isNotEmpty()) genresList.add(g)
            }
        }

        val credits = json.optJSONObject("credits")
        val castList = mutableListOf<CastMember>()
        val castArr = credits?.optJSONArray("cast")
        if (castArr != null) {
            for (i in 0 until minOf(15, castArr.length())) {
                val cObj = castArr.optJSONObject(i) ?: continue
                val cId = cObj.optInt("id", 0)
                val cName = cObj.optString("name", "").trim()
                val cChar = cObj.optString("character", "").trim()
                val cProfile = cObj.optString("profile_path", "").trim()
                val profileUrl = if (cProfile.isNotEmpty()) "https://image.tmdb.org/t/p/w185$cProfile" else ""
                if (cName.isNotEmpty()) {
                    castList.add(CastMember(cId, cName, cChar, profileUrl))
                }
            }
        }

        var director = ""
        val crewArr = credits?.optJSONArray("crew")
        if (crewArr != null) {
            for (i in 0 until crewArr.length()) {
                val crewObj = crewArr.optJSONObject(i) ?: continue
                if (crewObj.optString("job", "").equals("Director", ignoreCase = true)) {
                    director = crewObj.optString("name", "").trim()
                    break
                }
            }
        }

        // Extract YouTube trailer key
        var youtubeTrailerKey = ""
        val videos = json.optJSONObject("videos")
        val videoResults = videos?.optJSONArray("results")
        if (videoResults != null) {
            for (i in 0 until videoResults.length()) {
                val vObj = videoResults.optJSONObject(i) ?: continue
                val site = vObj.optString("site", "")
                val type = vObj.optString("type", "")
                val key = vObj.optString("key", "")
                if (site.equals("YouTube", ignoreCase = true) && key.isNotBlank()) {
                    if (type.equals("Trailer", ignoreCase = true)) {
                        youtubeTrailerKey = key
                        break
                    } else if (youtubeTrailerKey.isEmpty() && type.equals("Teaser", ignoreCase = true)) {
                        youtubeTrailerKey = key
                    }
                }
            }
        }

        // Extract similar media
        val similarList = mutableListOf<SimilarMedia>()
        val similarObj = json.optJSONObject("similar")
        val similarResults = similarObj?.optJSONArray("results")
        if (similarResults != null) {
            for (i in 0 until minOf(12, similarResults.length())) {
                val sObj = similarResults.optJSONObject(i) ?: continue
                val sId = sObj.optInt("id", 0)
                if (sId <= 0) continue
                val sTitle = sObj.optString("title", sObj.optString("name", "")).trim()
                val sPoster = sObj.optString("poster_path", "").trim()
                val sBackdrop = sObj.optString("backdrop_path", "").trim()
                val sDate = sObj.optString("release_date", sObj.optString("first_air_date", "")).trim()
                val sRating = sObj.optDouble("vote_average", 0.0).let { if (it.isNaN()) 0.0 else it }
                val sOverview = sObj.optString("overview", "").trim()
                similarList.add(
                    SimilarMedia(
                        tmdbId = sId,
                        title = sTitle,
                        posterUrl = if (sPoster.isNotEmpty()) "https://image.tmdb.org/t/p/w500$sPoster" else "",
                        backdropUrl = if (sBackdrop.isNotEmpty()) "https://image.tmdb.org/t/p/w780$sBackdrop" else "",
                        rating = sRating,
                        releaseYear = sDate.take(4),
                        overview = sOverview,
                        isSeries = isSeries
                    )
                )
            }
        }

        val runtimeMinutes = json.optInt("runtime", 0)
        val duration = if (runtimeMinutes > 0) "${runtimeMinutes}m" else ""

        val seasons = mutableListOf<TmdbSeasonSummary>()
        val seasonsArr = json.optJSONArray("seasons")
        if (seasonsArr != null) {
            for (i in 0 until seasonsArr.length()) {
                val sObj = seasonsArr.optJSONObject(i) ?: continue
                val sNum = sObj.optInt("season_number", 0)
                if (sNum >= 1) {
                    val sPoster = sObj.optString("poster_path", "").trim()
                    seasons.add(
                        TmdbSeasonSummary(
                            seasonNumber = sNum,
                            name = sObj.optString("name", "Season $sNum"),
                            episodeCount = sObj.optInt("episode_count", 0),
                            overview = sObj.optString("overview", ""),
                            posterUrl = if (sPoster.isNotEmpty()) "https://image.tmdb.org/t/p/w500$sPoster" else "",
                            airDate = sObj.optString("air_date", "")
                        )
                    )
                }
            }
        }

        return TmdbCompleteDetails(
            tmdbId = tmdbId,
            title = title,
            originalTitle = origTitle,
            overview = json.optString("overview", "").trim(),
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            rating = json.optDouble("vote_average", 0.0).let { if (it.isNaN()) 0.0 else it },
            releaseYear = releaseYear,
            releaseDate = releaseDate,
            duration = duration,
            genres = genresList,
            director = director,
            cast = castList,
            youtubeTrailerKey = youtubeTrailerKey,
            similar = similarList,
            seasons = seasons
        )
    }

    /**
     * Searches TMDb directly for a title and year, evaluating confidence score to match IPTV media accurately.
     */
    suspend fun searchAndMatchMedia(
        apiKey: String,
        rawTitle: String,
        year: String = "",
        isSeries: Boolean = false,
        language: String = "en-US"
    ): TmdbMediaMetadata? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) return@withContext null

        val matchedId = findTmdbMatch(cleanKey, rawTitle, year, isSeries)
        if (matchedId > 0) {
            return@withContext fetchMediaDetails(cleanKey, matchedId, isSeries, language)
        }
        null
    }

    /**
     * Fetches detailed media metadata by TMDb ID.
     */
    suspend fun fetchMediaDetails(
        apiKey: String,
        tmdbId: Int,
        isSeries: Boolean,
        language: String = "en-US"
    ): TmdbMediaMetadata? = withContext(Dispatchers.IO) {
        val complete = fetchCompleteMediaDetails(apiKey, tmdbId, isSeries, language) ?: return@withContext null
        TmdbMediaMetadata(
            tmdbId = complete.tmdbId,
            title = complete.title,
            overview = complete.overview,
            posterUrl = complete.posterUrl,
            backdropUrl = complete.backdropUrl,
            rating = complete.rating,
            releaseYear = complete.releaseYear,
            releaseDate = complete.releaseDate,
            genres = complete.genres.joinToString(", "),
            cast = complete.cast.take(6).joinToString(", ") { it.name },
            director = complete.director,
            runtimeMinutes = complete.duration.replace("m", "").trim().toIntOrNull() ?: 0,
            seasons = complete.seasons
        )
    }

    private fun fetchPagedList(
        url: String,
        mediaType: String,
        isFavorite: Boolean,
        inWatchlist: Boolean,
        isRatingList: Boolean = false
    ): List<TmdbSyncItem> {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()

        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string().orEmpty()
            val json = parseJsonObjectSafe(body) ?: return emptyList()
            val results = json.optJSONArray("results") ?: return emptyList()
            val items = ArrayList<TmdbSyncItem>(results.length())

            for (i in 0 until results.length()) {
                val obj = results.optJSONObject(i) ?: continue
                val tmdbId = obj.optInt("id", 0)
                if (tmdbId <= 0) continue

                val title = obj.optString("title", obj.optString("name", "")).trim()
                val posterPath = obj.optString("poster_path", "").trim()
                val posterUrl = if (posterPath.isNotEmpty()) "https://image.tmdb.org/t/p/w500$posterPath" else ""
                val releaseDate = obj.optString("release_date", obj.optString("first_air_date", "")).trim()
                val userRating = if (isRatingList) obj.optDouble("rating", 0.0).let { if (it.isNaN()) 0.0 else it } else 0.0
                val tmdbRating = obj.optDouble("vote_average", 0.0).let { if (it.isNaN()) 0.0 else it }

                items.add(
                    TmdbSyncItem(
                        tmdbId = tmdbId,
                        mediaType = mediaType,
                        title = title,
                        posterUrl = posterUrl,
                        releaseYear = releaseDate.take(4),
                        rating = tmdbRating,
                        userRating = userRating,
                        favorite = isFavorite,
                        watchlist = inWatchlist
                    )
                )
            }
            return items
        }
    }

    private fun mapNetworkExceptionToAuthError(e: Exception): TmdbAuthResult.Error {
        return when (e) {
            is UnknownHostException,
            is SocketTimeoutException,
            is ConnectException -> TmdbAuthResult.Error(
                message = "No internet connection. Please check your network and try again.",
                authErrorType = TmdbAuthErrorType.NO_INTERNET
            )
            is IOException -> TmdbAuthResult.Error(
                message = "Network error connecting to TMDb. Please try again.",
                authErrorType = TmdbAuthErrorType.NETWORK_ERROR
            )
            else -> TmdbAuthResult.Error(
                message = e.localizedMessage ?: "Unexpected error during TMDb authentication.",
                authErrorType = TmdbAuthErrorType.SERVICE_UNAVAILABLE
            )
        }
    }

    private fun parseJsonObjectSafe(raw: String): JSONObject? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        return try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            null
        }
    }

    private fun hasWordOverlap(a: String, b: String): Boolean {
        val wordsA = a.split(" ").filter { it.length > 2 }.toSet()
        val wordsB = b.split(" ").filter { it.length > 2 }.toSet()
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false
        val intersection = wordsA.intersect(wordsB)
        return intersection.size >= minOf(wordsA.size, wordsB.size)
    }

    companion object {
        const val TMDB_BASE_URL = "https://api.themoviedb.org/3"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val PUNCTUATION_REGEX = Regex("[^a-zA-Z0-9\\s]")
    }
}
