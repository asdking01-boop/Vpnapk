package com.example.data.api

import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.PendingTmdbMutation
import com.example.data.model.Season
import com.example.data.model.SeriesDetails
import com.example.data.model.TmdbSyncItem
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

enum class TmdbConnectionState {
    NOT_CONFIGURED,
    CHECKING,
    CONNECTED,
    PROXY_UNREACHABLE,
    HTTP_ERROR,
    SERVER_KEY_MISSING,
    TMDB_REJECTED
}

enum class TmdbErrorCategory {
    NONE,
    UNCONFIGURED,
    NETWORK_ERROR,
    HTTP_ERROR,
    MISSING_AUTH_ENDPOINT
}

data class TmdbHealthResult(
    val state: TmdbConnectionState,
    val isConnected: Boolean = state == TmdbConnectionState.CONNECTED,
    val message: String = "",
    val httpStatusCode: Int = 0,
    val errorCategory: TmdbErrorCategory = if (state == TmdbConnectionState.CONNECTED) TmdbErrorCategory.NONE else TmdbErrorCategory.UNCONFIGURED,
    val testedUrl: String = ""
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

data class TmdbRequestTokenResult(
    val ok: Boolean,
    val requestToken: String = "",
    val authSessionId: String = "",
    val authUrl: String = "",
    val expiresAt: String = "",
    val stateNonce: String = "",
    val endpointNotDeployed: Boolean = false,
    val httpStatusCode: Int = 0,
    val errorCategory: TmdbErrorCategory = TmdbErrorCategory.NONE,
    val authErrorType: com.example.data.model.TmdbAuthErrorType = com.example.data.model.TmdbAuthErrorType.NONE,
    val requestUrl: String = "",
    val userMessage: String = ""
)

data class TmdbSessionResult(
    val ok: Boolean,
    val verified: Boolean = false,
    val sessionId: String = "", // Opaque installation-bound client token issued by Worker (never raw TMDb session_id)
    val accountId: Int = 0,
    val username: String = "",
    val displayName: String = "",
    val avatarUrl: String = "",
    val endpointNotDeployed: Boolean = false,
    val httpStatusCode: Int = 0,
    val errorCategory: TmdbErrorCategory = TmdbErrorCategory.NONE,
    val authErrorType: com.example.data.model.TmdbAuthErrorType = com.example.data.model.TmdbAuthErrorType.NONE,
    val requestUrl: String = "",
    val userMessage: String = ""
)

data class TmdbTwoWaySyncResult(
    val ok: Boolean,
    val favorites: List<TmdbSyncItem> = emptyList(),
    val watchlist: List<TmdbSyncItem> = emptyList(),
    val ratings: List<TmdbSyncItem> = emptyList(),
    val pushedFavorites: Int = 0,
    val pushedWatchlist: Int = 0,
    val pushedRatings: Int = 0,
    val endpointNotDeployed: Boolean = false,
    val httpStatusCode: Int = 0,
    val errorCategory: TmdbErrorCategory = TmdbErrorCategory.NONE,
    val requestUrl: String = "",
    val userMessage: String = ""
)

/**
 * Client for communicating with the secure server-side KRAS TV TMDb API v3 Backend Proxy.
 *
 * Security Guarantee:
 * - Never embeds, stores, or transmits `TMDB_API_KEY` or raw TMDb `session_id` in the Android APK.
 * - All TMDb API v3 authentication and user session exchanges occur strictly through the Cloudflare Worker.
 * - Returns to KRAS TV via a verified Android App Link with a single-use authorization code (`code`).
 */
class TmdbProxyClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {

    private val movieCache = ConcurrentHashMap<String, TmdbMediaMetadata>()
    private val tvCache = ConcurrentHashMap<String, TmdbMediaMetadata>()
    private val seasonEpisodesCache = ConcurrentHashMap<String, List<TmdbEpisodeMetadata>>()

    /**
     * Parses, deduplicates, normalizes, and validates a backend proxy URL.
     *
     * Specifically fixes malformed or duplicated URLs such as:
     * `https://kras-tmdb-proxy.asdking01.workers.devhttps://kras-tmdb-proxy.asdking01.workers.dev`
     * which `java.net.URI` would otherwise misparse as host `kras-tmdb-proxy.asdking01.workers.devhttps`
     * with empty port `:` and path `//kras-tmdb-proxy.asdking01.workers.dev`.
     */
    fun parseProxyEndpoint(rawUrl: String): ParsedProxyEndpoint {
        val cleanedInput = rawUrl.trim().trim('"', '\'')
        if (cleanedInput.isEmpty()) return ParsedProxyEndpoint("", "")

        var extractedToken = extractQueryTokenFromRawString(cleanedInput)
        val deduplicatedCandidate = deduplicateConcatenatedUrls(cleanedInput)
        if (deduplicatedCandidate.isEmpty()) return ParsedProxyEndpoint("", "")

        val withScheme = if (!deduplicatedCandidate.startsWith("http://", ignoreCase = true) &&
            !deduplicatedCandidate.startsWith("https://", ignoreCase = true)
        ) {
            "https://$deduplicatedCandidate"
        } else {
            deduplicatedCandidate
        }

        try {
            val uri = URI(withScheme)
            if (extractedToken.isEmpty()) {
                extractedToken = extractQueryTokenFromRawString(uri.rawQuery.orEmpty(), isRawQueryOnly = true)
            }
            val scheme = (uri.scheme ?: "https").lowercase()
            val rawHost = (uri.host ?: uri.authority?.substringBefore(':') ?: "")
                .trim()
                .replace(TRAILING_STUCK_SCHEME_REGEX, "")
            val port = uri.port
            if (isValidBackendHost(rawHost)) {
                val cleanPath = stripKnownEndpointSuffixes(uri.path ?: "")
                    .replace(Regex("/{2,}"), "/")
                    .trimEnd('/')
                val normalizedBase = buildString {
                    append(scheme)
                    append("://")
                    append(rawHost)
                    if (port > 0 && port != 80 && port != 443) {
                        append(":")
                        append(port)
                    }
                    if (cleanPath.isNotEmpty() && cleanPath != "/") {
                        if (!cleanPath.startsWith("/")) append("/")
                        append(cleanPath)
                    }
                }.trimEnd('/')
                return ParsedProxyEndpoint(normalizedBase, extractedToken)
            }
        } catch (_: Exception) {
            // Fall back to string-level normalization below
        }

        val withoutQuery = withScheme.substringBefore("?").substringBefore("#").trimEnd('/')
        val normalizedBase = stripKnownEndpointSuffixes(withoutQuery).trimEnd('/')
        val hostPart = normalizedBase.substringAfter("://", "").substringBefore("/").substringBefore(":")
            .replace(TRAILING_STUCK_SCHEME_REGEX, "")
        if (!isValidBackendHost(hostPart)) {
            return ParsedProxyEndpoint("", extractedToken)
        }
        val schemePart = normalizedBase.substringBefore("://", "https").lowercase()
        val rebuilt = "$schemePart://$hostPart"
        return ParsedProxyEndpoint(rebuilt, extractedToken)
    }

    fun normalizeProxyBaseUrl(rawUrl: String): String {
        return parseProxyEndpoint(rawUrl).baseUrl
    }

    /**
     * Safely combines a normalized backend base URL with an API path without ever duplicating
     * the host or `/api/tmdb` prefix.
     */
    fun buildEndpointUrl(rawBaseUrl: String, apiPath: String): String {
        val base = parseProxyEndpoint(rawBaseUrl).baseUrl.trimEnd('/')
        if (base.isEmpty()) return ""
        var cleanPath = apiPath.trim()
        if (cleanPath.startsWith(base, ignoreCase = true)) {
            cleanPath = cleanPath.substring(base.length)
        }
        cleanPath = deduplicateConcatenatedUrls(cleanPath)
        if (!cleanPath.startsWith("/")) {
            cleanPath = "/$cleanPath"
        }
        return "$base$cleanPath"
    }

    private fun buildAuthenticatedRequest(
        url: String,
        proxyAuthToken: String,
        tmdbSessionId: String = "",
        krasUserId: String = "",
        installationId: String = "",
        method: String = "GET",
        jsonBody: JSONObject? = null
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Client-App", "KRAS-TV-Android")

        if (proxyAuthToken.isNotBlank()) {
            builder.header("X-KRAS-Proxy-Token", proxyAuthToken)
            builder.header("Authorization", "Bearer $proxyAuthToken")
        }
        if (tmdbSessionId.isNotBlank()) {
            builder.header("X-TMDb-Session-Id", tmdbSessionId)
            builder.header("X-TMDb-Session", tmdbSessionId)
        }
        if (krasUserId.isNotBlank()) {
            builder.header("X-KRAS-User-Id", krasUserId)
        }
        if (installationId.isNotBlank()) {
            builder.header("X-KRAS-Installation-Id", installationId)
        }

        if (method.equals("POST", ignoreCase = true)) {
            val bodyStr = jsonBody?.toString() ?: "{}"
            builder.post(bodyStr.toRequestBody(JSON_MEDIA_TYPE))
        } else if (method.equals("DELETE", ignoreCase = true)) {
            val bodyStr = jsonBody?.toString() ?: "{}"
            builder.delete(bodyStr.toRequestBody(JSON_MEDIA_TYPE))
        } else {
            builder.get()
        }
        return builder.build()
    }

    // =========================================================================
    // 1. Official Browser-Based TMDb User Account Authorization & Two-Way Sync
    // =========================================================================

    /**
     * Step 1 of official browser-based TMDb user authorization:
     * Requests a new authentication session from the Cloudflare Worker.
     * The Worker creates an official TMDb `request_token`, associates it with `(installationId, krasUserId, stateNonce)`,
     * and returns the official `https://www.themoviedb.org/authenticate/{request_token}?redirect_to=...` URL.
     */
    suspend fun createAuthRequestToken(
        rawProxyUrl: String,
        redirectTo: String = "https://kras-tmdb-proxy.asdking01.workers.dev/app/tmdb-auth",
        krasUserId: String = "demo",
        stateNonce: String = "",
        installationId: String = ""
    ): TmdbRequestTokenResult = withContext(Dispatchers.IO) {
        val effectiveRawUrl = rawProxyUrl.trim().ifBlank { CANONICAL_BACKEND_BASE_URL }
        val endpoint = parseProxyEndpoint(effectiveRawUrl)
        if (endpoint.baseUrl.isEmpty()) {
            return@withContext TmdbRequestTokenResult(
                ok = false,
                endpointNotDeployed = false,
                errorCategory = TmdbErrorCategory.UNCONFIGURED,
                authErrorType = com.example.data.model.TmdbAuthErrorType.SERVICE_UNAVAILABLE,
                userMessage = "TMDb Account Sync service is not configured."
            )
        }

        val requestUrl = buildEndpointUrl(endpoint.baseUrl, "/api/tmdb/auth/request-token")
        val verifiedAppLinkUrl = "${endpoint.baseUrl}/app/tmdb-auth"

        try {
            val payload = JSONObject().apply {
                put("redirectTo", redirectTo)
                put("redirect_to", redirectTo)
                put("appLinkUrl", verifiedAppLinkUrl)
                put("app_link_url", verifiedAppLinkUrl)
                put("krasUserId", krasUserId)
                put("kras_user_id", krasUserId)
                if (installationId.isNotBlank()) {
                    put("installationId", installationId)
                    put("installation_id", installationId)
                }
                if (stateNonce.isNotBlank()) {
                    put("state", stateNonce)
                }
            }
            val request = buildAuthenticatedRequest(
                url = requestUrl,
                proxyAuthToken = endpoint.proxyAuthToken,
                krasUserId = krasUserId,
                installationId = installationId,
                method = "POST",
                jsonBody = payload
            )

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = if (body.trimStart().startsWith("{")) {
                    try { JSONObject(body) } catch (_: Exception) { null }
                } else null

                val token = json?.optString("requestToken", "")?.trim()
                    ?.ifBlank { json.optString("request_token", "").trim() }
                    .orEmpty()
                val authSessionId = json?.optString("authSessionId", "")?.trim()
                    ?.ifBlank { json.optString("auth_session_id", "").trim() }
                    .orEmpty()
                    .ifBlank { token }

                if (response.isSuccessful && (token.isNotEmpty() || authSessionId.isNotEmpty())) {
                    val returnedState = json?.optString("state", stateNonce)?.trim().orEmpty().ifBlank { stateNonce }
                    val callbackUrl = buildString {
                        append(endpoint.baseUrl)
                        append("/api/tmdb/auth/callback?app_redirect=")
                        append(URLEncoder.encode(verifiedAppLinkUrl, "UTF-8"))
                        if (returnedState.isNotBlank()) {
                            append("&state=")
                            append(URLEncoder.encode(returnedState, "UTF-8"))
                        }
                        if (krasUserId.isNotBlank()) {
                            append("&kras_user=")
                            append(URLEncoder.encode(krasUserId, "UTF-8"))
                        }
                        if (installationId.isNotBlank()) {
                            append("&iid=")
                            append(URLEncoder.encode(installationId, "UTF-8"))
                        }
                    }
                    val encodedCallback = URLEncoder.encode(callbackUrl, "UTF-8")
                    val authUrl = json?.optString("authUrl", "")?.trim()
                        ?.ifBlank { json.optString("auth_url", "").trim() }
                        ?.ifBlank { json.optString("authenticateUrl", "").trim() }
                        .takeIf { !it.isNullOrBlank() }
                        ?: "https://www.themoviedb.org/authenticate/$token?redirect_to=$encodedCallback"
                    return@withContext TmdbRequestTokenResult(
                        ok = true,
                        requestToken = token.ifBlank { authSessionId },
                        authSessionId = authSessionId,
                        authUrl = authUrl,
                        expiresAt = json?.optString("expiresAt", json.optString("expires_at", "")).orEmpty(),
                        stateNonce = returnedState,
                        httpStatusCode = response.code,
                        errorCategory = TmdbErrorCategory.NONE,
                        authErrorType = com.example.data.model.TmdbAuthErrorType.NONE,
                        requestUrl = requestUrl,
                        userMessage = "Sign in on TMDb and approve access in your browser to connect your account."
                    )
                }

                if (response.code == 404 || response.code == 405 || response.code == 501) {
                    return@withContext TmdbRequestTokenResult(
                        ok = false,
                        endpointNotDeployed = true,
                        httpStatusCode = response.code,
                        errorCategory = TmdbErrorCategory.MISSING_AUTH_ENDPOINT,
                        authErrorType = com.example.data.model.TmdbAuthErrorType.SERVICE_UNAVAILABLE,
                        requestUrl = requestUrl,
                        userMessage = "TMDb account sign-in service is awaiting the latest Cloudflare Worker deployment."
                    )
                }

                return@withContext TmdbRequestTokenResult(
                    ok = false,
                    httpStatusCode = response.code,
                    errorCategory = TmdbErrorCategory.HTTP_ERROR,
                    authErrorType = com.example.data.model.TmdbAuthErrorType.SERVICE_UNAVAILABLE,
                    requestUrl = requestUrl,
                    userMessage = "Unable to start TMDb authorization right now. Please try again in a moment."
                )
            }
        } catch (_: Exception) {
            TmdbRequestTokenResult(
                ok = false,
                errorCategory = TmdbErrorCategory.NETWORK_ERROR,
                authErrorType = com.example.data.model.TmdbAuthErrorType.NETWORK_ERROR,
                requestUrl = requestUrl,
                userMessage = "Unable to reach the TMDb service. Please check your internet connection."
            )
        }
    }

    /**
     * Step 2 of official browser-based TMDb user authorization:
     * Exchanges the one-time authorization `authCode` (returned via the verified Android App Link)
     * or verifies the pending `requestToken`/`authSessionId` with the Cloudflare Worker.
     *
     * The Cloudflare Worker creates and stores the official TMDb `session_id` server-side and returns
     * an opaque installation-bound client token (`sessionId` / `clientToken`) and verified TMDb profile
     * (`accountId`, `username`, `displayName`, `avatarUrl`).
     */
    suspend fun createAuthorizedSession(
        rawProxyUrl: String,
        requestToken: String,
        krasUserId: String = "demo",
        stateNonce: String = "",
        authCode: String = "",
        authSessionId: String = "",
        installationId: String = ""
    ): TmdbSessionResult = withContext(Dispatchers.IO) {
        val effectiveRawUrl = rawProxyUrl.trim().ifBlank { CANONICAL_BACKEND_BASE_URL }
        val endpoint = parseProxyEndpoint(effectiveRawUrl)
        val cleanToken = requestToken.trim()
        val cleanCode = authCode.trim()
        val cleanSessionRef = authSessionId.trim().ifBlank { cleanToken }
        if (endpoint.baseUrl.isEmpty() || (cleanToken.isEmpty() && cleanCode.isEmpty() && cleanSessionRef.isEmpty())) {
            return@withContext TmdbSessionResult(
                ok = false,
                verified = false,
                errorCategory = TmdbErrorCategory.UNCONFIGURED,
                authErrorType = com.example.data.model.TmdbAuthErrorType.SERVICE_UNAVAILABLE,
                userMessage = "Authorization session could not be verified."
            )
        }

        val requestUrl = buildEndpointUrl(endpoint.baseUrl, "/api/tmdb/auth/session")

        try {
            val payload = JSONObject().apply {
                if (cleanCode.isNotEmpty()) {
                    put("code", cleanCode)
                    put("authCode", cleanCode)
                    put("auth_code", cleanCode)
                }
                if (cleanToken.isNotEmpty()) {
                    put("requestToken", cleanToken)
                    put("request_token", cleanToken)
                }
                if (cleanSessionRef.isNotEmpty()) {
                    put("authSessionId", cleanSessionRef)
                    put("auth_session_id", cleanSessionRef)
                }
                put("krasUserId", krasUserId)
                put("kras_user_id", krasUserId)
                if (installationId.isNotBlank()) {
                    put("installationId", installationId)
                    put("installation_id", installationId)
                }
                if (stateNonce.isNotBlank()) {
                    put("state", stateNonce)
                }
            }
            val request = buildAuthenticatedRequest(
                url = requestUrl,
                proxyAuthToken = endpoint.proxyAuthToken,
                krasUserId = krasUserId,
                installationId = installationId,
                method = "POST",
                jsonBody = payload
            )

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = if (body.trimStart().startsWith("{")) {
                    try { JSONObject(body) } catch (_: Exception) { null }
                } else null

                if (response.code == 404 || response.code == 405 || response.code == 501) {
                    return@withContext TmdbSessionResult(
                        ok = false,
                        verified = false,
                        endpointNotDeployed = true,
                        httpStatusCode = response.code,
                        errorCategory = TmdbErrorCategory.MISSING_AUTH_ENDPOINT,
                        authErrorType = com.example.data.model.TmdbAuthErrorType.SERVICE_UNAVAILABLE,
                        requestUrl = requestUrl,
                        userMessage = "TMDb account sign-in service is awaiting the latest Cloudflare Worker deployment."
                    )
                }

                val errorCode = json?.optString("errorCode", json.optString("error_code", ""))?.trim()?.lowercase().orEmpty()
                if (response.code == 410 || errorCode == "expired_token" || errorCode == "expired_code") {
                    return@withContext TmdbSessionResult(
                        ok = false,
                        verified = false,
                        httpStatusCode = response.code,
                        errorCategory = TmdbErrorCategory.HTTP_ERROR,
                        authErrorType = com.example.data.model.TmdbAuthErrorType.EXPIRED_TOKEN,
                        requestUrl = requestUrl,
                        userMessage = "Your TMDb authorization session expired. Tap Connect TMDb Account to start a new session."
                    )
                }

                if (errorCode == "denied" || errorCode == "access_denied") {
                    return@withContext TmdbSessionResult(
                        ok = false,
                        verified = false,
                        httpStatusCode = response.code,
                        errorCategory = TmdbErrorCategory.HTTP_ERROR,
                        authErrorType = com.example.data.model.TmdbAuthErrorType.DENIED,
                        requestUrl = requestUrl,
                        userMessage = "Authorization was denied on TMDb. Tap Connect TMDb Account if you wish to try again."
                    )
                }

                val accountObj = json?.optJSONObject("account")
                // Prefer opaque `clientToken` issued by the Worker so raw TMDb session_id is never stored in the APK
                val clientToken = json?.optString("clientToken", "")?.trim()
                    ?.ifBlank { json.optString("client_token", "").trim() }
                    ?.ifBlank { json.optString("sessionId", "").trim() }
                    ?.ifBlank { json.optString("session_id", "").trim() }
                    .orEmpty()
                val username = accountObj?.optString("username", "")?.trim()
                    ?.ifBlank { json?.optString("username", "")?.trim().orEmpty() }
                    ?: json?.optString("username", "")?.trim().orEmpty()
                val accountId = accountObj?.optInt("id", 0)?.takeIf { it > 0 }
                    ?: json?.optInt("accountId", json.optInt("id", 0))
                    ?: 0
                val displayName = accountObj?.optString("name", "")?.trim()
                    ?.ifBlank { json?.optString("name", "")?.trim().orEmpty() }
                    ?: json?.optString("name", "")?.trim().orEmpty()
                val avatarUrl = accountObj?.optString("avatarUrl", "")?.trim()
                    ?.ifBlank { accountObj.optString("avatar_url", "").trim() }
                    ?.ifBlank { json?.optString("avatarUrl", "")?.trim().orEmpty() }
                    ?: json?.optString("avatarUrl", json.optString("avatar_url", ""))?.trim().orEmpty()

                if (response.isSuccessful && clientToken.isNotEmpty() && username.isNotEmpty()) {
                    return@withContext TmdbSessionResult(
                        ok = true,
                        verified = true,
                        sessionId = clientToken,
                        accountId = if (accountId > 0) accountId else username.hashCode().let { if (it < 0) -it else it + 1 },
                        username = username,
                        displayName = displayName.ifBlank { username },
                        avatarUrl = avatarUrl,
                        httpStatusCode = response.code,
                        errorCategory = TmdbErrorCategory.NONE,
                        authErrorType = com.example.data.model.TmdbAuthErrorType.NONE,
                        requestUrl = requestUrl,
                        userMessage = "Connected to TMDb as @$username."
                    )
                }

                return@withContext TmdbSessionResult(
                    ok = false,
                    verified = false,
                    httpStatusCode = response.code,
                    errorCategory = TmdbErrorCategory.HTTP_ERROR,
                    authErrorType = com.example.data.model.TmdbAuthErrorType.SERVICE_UNAVAILABLE,
                    requestUrl = requestUrl,
                    userMessage = "Please approve the TMDb authorization request in your browser first, then return to KRAS TV."
                )
            }
        } catch (_: Exception) {
            TmdbSessionResult(
                ok = false,
                verified = false,
                errorCategory = TmdbErrorCategory.NETWORK_ERROR,
                authErrorType = com.example.data.model.TmdbAuthErrorType.NETWORK_ERROR,
                requestUrl = requestUrl,
                userMessage = "Unable to reach the TMDb service to verify your sign-in. Please check your internet connection."
            )
        }
    }

    /**
     * Performs two-way synchronization of Favorites, Watchlist, and Ratings with the user's TMDb account
     * via `POST /api/tmdb/sync`.
     */
    suspend fun performTwoWaySync(
        rawProxyUrl: String,
        sessionId: String,
        accountId: Int,
        krasUserId: String = "demo",
        localFavorites: List<TmdbSyncItem>,
        localWatchlist: List<TmdbSyncItem>,
        localRatings: List<TmdbSyncItem>,
        pendingMutations: List<PendingTmdbMutation> = emptyList(),
        language: String = "en-US",
        installationId: String = ""
    ): TmdbTwoWaySyncResult = withContext(Dispatchers.IO) {
        val effectiveRawUrl = rawProxyUrl.trim().ifBlank { CANONICAL_BACKEND_BASE_URL }
        val endpoint = parseProxyEndpoint(effectiveRawUrl)
        if (endpoint.baseUrl.isEmpty() || sessionId.isBlank()) {
            return@withContext TmdbTwoWaySyncResult(
                ok = false,
                errorCategory = TmdbErrorCategory.UNCONFIGURED,
                userMessage = "Connect your TMDb account first to synchronize."
            )
        }

        val requestUrl = buildEndpointUrl(endpoint.baseUrl, "/api/tmdb/sync")

        try {
            val payload = JSONObject().apply {
                put("clientToken", sessionId)
                put("client_token", sessionId)
                put("session_id", sessionId)
                put("sessionId", sessionId)
                put("account_id", accountId)
                put("accountId", accountId)
                put("kras_user_id", krasUserId)
                put("krasUserId", krasUserId)
                if (installationId.isNotBlank()) {
                    put("installation_id", installationId)
                    put("installationId", installationId)
                }
                put("language", language)
                put("favorites", serializeSyncItemsArray(localFavorites))
                put("watchlist", serializeSyncItemsArray(localWatchlist))
                put("ratings", serializeSyncItemsArray(localRatings))
                put("mutations", serializePendingMutationsArray(pendingMutations))
            }

            val request = buildAuthenticatedRequest(
                url = requestUrl,
                proxyAuthToken = endpoint.proxyAuthToken,
                tmdbSessionId = sessionId,
                krasUserId = krasUserId,
                installationId = installationId,
                method = "POST",
                jsonBody = payload
            )

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = if (body.trimStart().startsWith("{")) {
                    try { JSONObject(body) } catch (_: Exception) { null }
                } else null

                if (response.code == 404 || response.code == 405 || response.code == 501) {
                    return@withContext TmdbTwoWaySyncResult(
                        ok = false,
                        endpointNotDeployed = true,
                        httpStatusCode = response.code,
                        errorCategory = TmdbErrorCategory.MISSING_AUTH_ENDPOINT,
                        requestUrl = requestUrl,
                        userMessage = "TMDb synchronization service is awaiting the latest Cloudflare Worker deployment."
                    )
                }

                if (response.isSuccessful && json != null && json.optBoolean("ok", true)) {
                    val favList = parseSyncItemsArray(json.optJSONArray("favorites"))
                    val watchList = parseSyncItemsArray(json.optJSONArray("watchlist"))
                    val rateList = parseSyncItemsArray(json.optJSONArray("ratings"))
                    return@withContext TmdbTwoWaySyncResult(
                        ok = true,
                        favorites = favList,
                        watchlist = watchList,
                        ratings = rateList,
                        pushedFavorites = json.optInt("pushedFavorites", json.optInt("pushedCount", 0)),
                        pushedWatchlist = json.optInt("pushedWatchlist", 0),
                        pushedRatings = json.optInt("pushedRatings", 0),
                        httpStatusCode = response.code,
                        errorCategory = TmdbErrorCategory.NONE,
                        requestUrl = requestUrl,
                        userMessage = "Favorites, Watchlist, and Ratings synchronized with TMDb."
                    )
                }

                return@withContext TmdbTwoWaySyncResult(
                    ok = false,
                    httpStatusCode = response.code,
                    errorCategory = TmdbErrorCategory.HTTP_ERROR,
                    requestUrl = requestUrl,
                    userMessage = "Unable to synchronize with TMDb right now. Your changes are saved locally and will sync automatically."
                )
            }
        } catch (_: Exception) {
            TmdbTwoWaySyncResult(
                ok = false,
                errorCategory = TmdbErrorCategory.NETWORK_ERROR,
                requestUrl = requestUrl,
                userMessage = "Unable to reach the TMDb sync service. Your changes are queued locally and will sync when back online."
            )
        }
    }

    suspend fun pushFavoriteChange(
        rawProxyUrl: String,
        sessionId: String,
        accountId: Int,
        mediaType: String,
        tmdbId: Int,
        favorite: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val endpoint = parseProxyEndpoint(rawProxyUrl.ifBlank { CANONICAL_BACKEND_BASE_URL })
        if (endpoint.baseUrl.isEmpty() || sessionId.isBlank() || tmdbId <= 0) return@withContext false

        try {
            val normalizedType = if (mediaType.equals("SERIES", true) || mediaType.equals("tv", true)) "tv" else "movie"
            val payload = JSONObject().apply {
                put("accountId", accountId)
                put("mediaType", normalizedType)
                put("tmdbId", tmdbId)
                put("mediaId", tmdbId)
                put("favorite", favorite)
            }
            val request = buildAuthenticatedRequest(
                url = buildEndpointUrl(endpoint.baseUrl, "/api/tmdb/account/favorite"),
                proxyAuthToken = endpoint.proxyAuthToken,
                tmdbSessionId = sessionId,
                method = "POST",
                jsonBody = payload
            )
            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun pushWatchlistChange(
        rawProxyUrl: String,
        sessionId: String,
        accountId: Int,
        mediaType: String,
        tmdbId: Int,
        watchlist: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val endpoint = parseProxyEndpoint(rawProxyUrl.ifBlank { CANONICAL_BACKEND_BASE_URL })
        if (endpoint.baseUrl.isEmpty() || sessionId.isBlank() || tmdbId <= 0) return@withContext false

        try {
            val normalizedType = if (mediaType.equals("SERIES", true) || mediaType.equals("tv", true)) "tv" else "movie"
            val payload = JSONObject().apply {
                put("accountId", accountId)
                put("mediaType", normalizedType)
                put("tmdbId", tmdbId)
                put("mediaId", tmdbId)
                put("watchlist", watchlist)
            }
            val request = buildAuthenticatedRequest(
                url = buildEndpointUrl(endpoint.baseUrl, "/api/tmdb/account/watchlist"),
                proxyAuthToken = endpoint.proxyAuthToken,
                tmdbSessionId = sessionId,
                method = "POST",
                jsonBody = payload
            )
            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun pushRatingChange(
        rawProxyUrl: String,
        sessionId: String,
        accountId: Int,
        mediaType: String,
        tmdbId: Int,
        rating: Double
    ): Boolean = withContext(Dispatchers.IO) {
        val endpoint = parseProxyEndpoint(rawProxyUrl.ifBlank { CANONICAL_BACKEND_BASE_URL })
        if (endpoint.baseUrl.isEmpty() || sessionId.isBlank() || tmdbId <= 0) return@withContext false

        try {
            val normalizedType = if (mediaType.equals("SERIES", true) || mediaType.equals("tv", true)) "tv" else "movie"
            val payload = JSONObject().apply {
                put("accountId", accountId)
                put("mediaType", normalizedType)
                put("tmdbId", tmdbId)
                put("mediaId", tmdbId)
                put("rating", rating)
            }
            val request = buildAuthenticatedRequest(
                url = buildEndpointUrl(endpoint.baseUrl, "/api/tmdb/account/rating"),
                proxyAuthToken = endpoint.proxyAuthToken,
                tmdbSessionId = sessionId,
                method = "POST",
                jsonBody = payload
            )
            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun disconnectSession(
        rawProxyUrl: String,
        sessionId: String,
        krasUserId: String = "demo",
        installationId: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        val endpoint = parseProxyEndpoint(rawProxyUrl.ifBlank { CANONICAL_BACKEND_BASE_URL })
        if (endpoint.baseUrl.isEmpty() || sessionId.isBlank()) return@withContext true

        try {
            val request = buildAuthenticatedRequest(
                url = buildEndpointUrl(endpoint.baseUrl, "/api/tmdb/auth/logout"),
                proxyAuthToken = endpoint.proxyAuthToken,
                tmdbSessionId = sessionId,
                krasUserId = krasUserId,
                installationId = installationId,
                method = "POST",
                jsonBody = JSONObject().apply {
                    put("clientToken", sessionId)
                    put("client_token", sessionId)
                    put("sessionId", sessionId)
                    put("session_id", sessionId)
                    put("krasUserId", krasUserId)
                    put("kras_user_id", krasUserId)
                    if (installationId.isNotBlank()) {
                        put("installationId", installationId)
                        put("installation_id", installationId)
                    }
                }
            )
            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }

    // =========================================================================
    // 2. Automatic Movie, TV Series, Season, and Episode Metadata Enrichment
    // =========================================================================

    suspend fun verifyConnection(rawProxyUrl: String): TmdbHealthResult = withContext(Dispatchers.IO) {
        val endpoint = parseProxyEndpoint(rawProxyUrl)
        if (endpoint.baseUrl.isEmpty()) {
            return@withContext TmdbHealthResult(
                state = TmdbConnectionState.NOT_CONFIGURED,
                isConnected = false,
                message = "Backend proxy URL is not configured.",
                errorCategory = TmdbErrorCategory.UNCONFIGURED
            )
        }

        // Test the backend using the existing /api/tmdb/test endpoint first, then /api/tmdb/health
        val candidatePaths = listOf("/api/tmdb/test", "/api/tmdb/health")
        var lastResult = TmdbHealthResult(
            state = TmdbConnectionState.PROXY_UNREACHABLE,
            isConnected = false,
            message = "Network error: Unable to reach ${endpoint.baseUrl}/api/tmdb/test.",
            errorCategory = TmdbErrorCategory.NETWORK_ERROR,
            testedUrl = buildEndpointUrl(endpoint.baseUrl, "/api/tmdb/test")
        )

        for (path in candidatePaths) {
            val targetUrl = buildEndpointUrl(endpoint.baseUrl, path)
            try {
                val request = buildAuthenticatedRequest(
                    url = targetUrl,
                    proxyAuthToken = endpoint.proxyAuthToken
                )

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val json = if (body.trimStart().startsWith("{")) {
                        try { JSONObject(body) } catch (_: Exception) { null }
                    } else null

                    val okFlag = json?.optBoolean("ok", false) == true ||
                        json?.optBoolean("success", false) == true
                    val tmdbConnected = json?.optBoolean("tmdbConnected", false) == true ||
                        json?.optString("status", "").equals("connected", ignoreCase = true) ||
                        json?.optString("status", "").equals("ok", ignoreCase = true)
                    val serverStatus = json?.optString("status", "").orEmpty()
                    val serverMessage = json?.optString("message", "")
                        ?.ifBlank { json.optString("error", "") }
                        .orEmpty()

                    if (response.isSuccessful && (tmdbConnected || okFlag || json != null)) {
                        return@withContext TmdbHealthResult(
                            state = TmdbConnectionState.CONNECTED,
                            isConnected = true,
                            message = serverMessage.ifBlank { "Connected to TMDb API v3 via ${endpoint.baseUrl}." },
                            httpStatusCode = response.code,
                            errorCategory = TmdbErrorCategory.NONE,
                            testedUrl = targetUrl
                        )
                    }

                    if (response.code == 503 || serverStatus.equals("unconfigured", ignoreCase = true) ||
                        serverMessage.contains("TMDB_API_KEY", ignoreCase = true)
                    ) {
                        lastResult = TmdbHealthResult(
                            state = TmdbConnectionState.SERVER_KEY_MISSING,
                            isConnected = false,
                            message = "TMDb service is currently unconfigured on ${endpoint.baseUrl} (HTTP ${response.code}).",
                            httpStatusCode = response.code,
                            errorCategory = TmdbErrorCategory.HTTP_ERROR,
                            testedUrl = targetUrl
                        )
                    } else if (response.code == 401 || response.code == 403) {
                        lastResult = TmdbHealthResult(
                            state = TmdbConnectionState.TMDB_REJECTED,
                            isConnected = false,
                            message = "TMDb authentication failed on ${endpoint.baseUrl} (HTTP ${response.code}).",
                            httpStatusCode = response.code,
                            errorCategory = TmdbErrorCategory.HTTP_ERROR,
                            testedUrl = targetUrl
                        )
                    } else {
                        lastResult = TmdbHealthResult(
                            state = TmdbConnectionState.HTTP_ERROR,
                            isConnected = false,
                            message = serverMessage.ifBlank {
                                "Backend at $targetUrl returned HTTP ${response.code}."
                            },
                            httpStatusCode = response.code,
                            errorCategory = TmdbErrorCategory.HTTP_ERROR,
                            testedUrl = targetUrl
                        )
                    }
                }
            } catch (_: Exception) {
                // Keep network error if no HTTP response was received on any candidate path
            }
        }

        lastResult
    }

    suspend fun fetchMovieMetadata(
        rawProxyUrl: String,
        rawTitle: String,
        year: String = "",
        tmdbId: Int = 0,
        language: String = "en-US"
    ): TmdbMediaMetadata? = withContext(Dispatchers.IO) {
        val endpoint = parseProxyEndpoint(rawProxyUrl.ifBlank { CANONICAL_BACKEND_BASE_URL })
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
            val url = buildEndpointUrl(
                endpoint.baseUrl,
                "/api/tmdb/movie?title=$encodedTitle&year=$encodedYear&tmdbId=$tmdbId&language=$encodedLang"
            )

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
        val endpoint = parseProxyEndpoint(rawProxyUrl.ifBlank { CANONICAL_BACKEND_BASE_URL })
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
            val url = buildEndpointUrl(
                endpoint.baseUrl,
                "/api/tmdb/tv?title=$encodedTitle&year=$encodedYear&tmdbId=$tmdbId&language=$encodedLang"
            )

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
        val endpoint = parseProxyEndpoint(rawProxyUrl.ifBlank { CANONICAL_BACKEND_BASE_URL })
        if (endpoint.baseUrl.isEmpty() || movieId <= 0) return@withContext null

        val cacheKey = "movie_id:$movieId:$language"
        movieCache[cacheKey]?.let { return@withContext it }

        try {
            val encodedLang = URLEncoder.encode(language, "UTF-8")
            val url = buildEndpointUrl(endpoint.baseUrl, "/api/tmdb/movie/$movieId?language=$encodedLang")
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
        val endpoint = parseProxyEndpoint(rawProxyUrl.ifBlank { CANONICAL_BACKEND_BASE_URL })
        if (endpoint.baseUrl.isEmpty() || tvId <= 0) return@withContext null

        val cacheKey = "tv_id:$tvId:$language"
        tvCache[cacheKey]?.let { return@withContext it }

        try {
            val encodedLang = URLEncoder.encode(language, "UTF-8")
            val url = buildEndpointUrl(endpoint.baseUrl, "/api/tmdb/tv/$tvId?language=$encodedLang")
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
        val endpoint = parseProxyEndpoint(rawProxyUrl.ifBlank { CANONICAL_BACKEND_BASE_URL })
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
            val url = buildEndpointUrl(
                endpoint.baseUrl,
                "/api/tmdb/match?title=$encodedTitle&type=$encodedType&year=$encodedYear&tmdbId=$tmdbId&language=$encodedLang"
            )
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
        val endpoint = parseProxyEndpoint(rawProxyUrl.ifBlank { CANONICAL_BACKEND_BASE_URL })
        if (endpoint.baseUrl.isEmpty() || tmdbId <= 0 || seasonNumber < 0) return@withContext emptyList()

        val cacheKey = "season:$tmdbId:$seasonNumber:$language"
        seasonEpisodesCache[cacheKey]?.let { return@withContext it }

        try {
            val encodedLang = URLEncoder.encode(language, "UTF-8")
            val url = buildEndpointUrl(endpoint.baseUrl, "/api/tmdb/tv/$tmdbId/season/$seasonNumber?language=$encodedLang")
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
        const val CANONICAL_BACKEND_BASE_URL = "https://kras-tmdb-proxy.asdking01.workers.dev"
        const val DEFAULT_CLOUDFLARE_WORKER_URL = CANONICAL_BACKEND_BASE_URL
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val SCHEME_BOUNDARY_REGEX = Regex("""(?i)https?://""")
        private val REPEATED_LEADING_SCHEME_REGEX = Regex("""^(?i)(https?://)+""")
        private val TRAILING_STUCK_SCHEME_REGEX = Regex("""(?i)https?:?$""")

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
         * Normalizes any stored or user-provided URL into a clean single base URL, falling back
         * to [CANONICAL_BACKEND_BASE_URL] if the input is null, blank, or malformed.
         */
        fun normalizeOrFallbackToCanonical(rawUrl: String?, fallbackUrl: String = CANONICAL_BACKEND_BASE_URL): String {
            val candidate = rawUrl?.trim().orEmpty()
            if (candidate.isEmpty()) {
                return TmdbProxyClient().normalizeProxyBaseUrl(fallbackUrl).ifBlank { CANONICAL_BACKEND_BASE_URL }
            }
            val normalized = TmdbProxyClient().normalizeProxyBaseUrl(candidate)
            return normalized.ifBlank {
                TmdbProxyClient().normalizeProxyBaseUrl(fallbackUrl).ifBlank { CANONICAL_BACKEND_BASE_URL }
            }
        }

        /**
         * Detects and removes duplicated/concatenated URLs such as:
         * - `https://kras-tmdb-proxy.asdking01.workers.devhttps://kras-tmdb-proxy.asdking01.workers.dev`
         * - `https://kras-tmdb-proxy.asdking01.workers.dev/https://kras-tmdb-proxy.asdking01.workers.dev`
         * - `https://https://kras-tmdb-proxy.asdking01.workers.dev`
         * - `kras-tmdb-proxy.asdking01.workers.devhttps://kras-tmdb-proxy.asdking01.workers.dev`
         * - `https://kras-tmdb-proxy.asdking01.workers.devkras-tmdb-proxy.asdking01.workers.dev`
         */
        fun deduplicateConcatenatedUrls(raw: String): String {
            var working = raw.trim().trim('"', '\'')
            if (working.isEmpty()) return ""

            // If the exact string is repeated back-to-back (S + S), collapse to S
            if (working.length % 2 == 0) {
                val half = working.length / 2
                val firstHalf = working.substring(0, half)
                val secondHalf = working.substring(half)
                if (firstHalf.equals(secondHalf, ignoreCase = true)) {
                    working = firstHalf
                }
            }

            working = REPEATED_LEADING_SCHEME_REGEX.replace(working) { match ->
                if (match.value.lowercase().contains("http://") && !match.value.lowercase().endsWith("https://")) {
                    "http://"
                } else {
                    "https://"
                }
            }

            val matches = SCHEME_BOUNDARY_REGEX.findAll(working).toList()
            if (matches.size >= 2) {
                // Split into individual scheme-prefixed segments and return the first non-empty valid segment
                for (i in matches.indices) {
                    val startIdx = matches[i].range.first
                    val endIdx = if (i + 1 < matches.size) matches[i + 1].range.first else working.length
                    val segment = working.substring(startIdx, endIdx).trim().trimEnd('/')
                    val hostCandidate = segment.substringAfter("://", "").substringBefore("/").substringBefore("?")
                    if (hostCandidate.isNotBlank()) {
                        working = segment
                        break
                    }
                }
            } else if (matches.size == 1 && matches[0].range.first > 0) {
                // E.g. "kras-tmdb-proxy.asdking01.workers.devhttps://kras-tmdb-proxy.asdking01.workers.dev"
                val secondPart = working.substring(matches[0].range.first).trim().trimEnd('/')
                if (secondPart.substringAfter("://", "").isNotBlank()) {
                    working = secondPart
                }
            }

            // Also guard against scheme-less duplicated hostnames (e.g. "https://host.workers.devhost.workers.dev")
            val schemePrefix = if (working.startsWith("http://", ignoreCase = true)) {
                "http://"
            } else if (working.startsWith("https://", ignoreCase = true)) {
                "https://"
            } else {
                ""
            }
            val remainder = if (schemePrefix.isNotEmpty()) working.substring(schemePrefix.length) else working
            val hostAndPort = remainder.substringBefore("/").substringBefore("?").substringBefore("#")
            val pathAndQuery = remainder.substring(hostAndPort.length)
            var cleanHostAndPort = hostAndPort.replace(TRAILING_STUCK_SCHEME_REGEX, "")
            if (cleanHostAndPort.length % 2 == 0 && cleanHostAndPort.isNotEmpty()) {
                val half = cleanHostAndPort.length / 2
                if (cleanHostAndPort.substring(0, half).equals(cleanHostAndPort.substring(half), ignoreCase = true)) {
                    cleanHostAndPort = cleanHostAndPort.substring(0, half)
                }
            }
            val workersDevIdx = cleanHostAndPort.lowercase().indexOf(".workers.dev")
            if (workersDevIdx > 0) {
                val endOfWorkersDev = workersDevIdx + ".workers.dev".length
                if (cleanHostAndPort.length > endOfWorkersDev && cleanHostAndPort[endOfWorkersDev] != ':') {
                    cleanHostAndPort = cleanHostAndPort.substring(0, endOfWorkersDev)
                }
            }

            return "$schemePrefix$cleanHostAndPort$pathAndQuery"
        }

        internal fun extractQueryTokenFromRawString(raw: String, isRawQueryOnly: Boolean = false): String {
            val queryPart = if (isRawQueryOnly) raw else raw.substringAfter("?", "").substringBefore("#")
            if (queryPart.isEmpty()) return ""
            for (pair in queryPart.split("&")) {
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2 && (parts[0] == "token" || parts[0] == "proxyToken")) {
                    val rawVal = parts[1].substringBefore("http://").substringBefore("https://")
                    return try {
                        java.net.URLDecoder.decode(rawVal, "UTF-8").trim()
                    } catch (_: Exception) {
                        rawVal.trim()
                    }
                }
            }
            return ""
        }

        internal fun isValidBackendHost(host: String): Boolean {
            val clean = host.trim().lowercase()
            if (clean.isEmpty()) return false
            if (clean.contains(" ") || clean.contains("http:") || clean.contains("https:") || clean.contains("/")) {
                return false
            }
            if (clean == "localhost" || clean == "127.0.0.1" || clean == "10.0.2.2") return true
            return clean.contains(".") && !clean.startsWith(".") && !clean.endsWith(".")
        }

        internal fun stripKnownEndpointSuffixes(rawPathOrUrl: String): String {
            var current = rawPathOrUrl.trim().trimEnd('/')
            val suffixes = listOf(
                "/api/tmdb/auth/session/start",
                "/api/tmdb/auth/request-token",
                "/api/tmdb/auth/callback",
                "/api/tmdb/auth/exchange",
                "/api/tmdb/auth/session",
                "/api/tmdb/auth/logout",
                "/app/tmdb-auth",
                "/api/tmdb/account/favorite",
                "/api/tmdb/account/watchlist",
                "/api/tmdb/account/rating",
                "/api/tmdb/account",
                "/api/tmdb/connectivity-test",
                "/api/tmdb/health",
                "/api/tmdb/test",
                "/api/tmdb/sync",
                "/api/tmdb/movie",
                "/api/tmdb/tv",
                "/api/tmdb/match",
                "/api/tmdb",
                "/health",
                "/test"
            )
            var changed = true
            while (changed) {
                changed = false
                for (suffix in suffixes) {
                    if (current.endsWith(suffix, ignoreCase = true)) {
                        current = current.dropLast(suffix.length).trimEnd('/')
                        changed = true
                    }
                }
            }
            return current
        }

        fun serializeSyncItemsArray(items: List<TmdbSyncItem>): JSONArray {
            val arr = JSONArray()
            for (item in items) {
                if (item.tmdbId <= 0 && item.title.isBlank()) continue
                val normalizedType = item.normalizedMediaType
                arr.put(
                    JSONObject().apply {
                        put("tmdb_id", item.tmdbId)
                        put("tmdbId", item.tmdbId)
                        put("media_type", normalizedType)
                        put("mediaType", normalizedType)
                        put("title", item.title)
                        put("poster_url", item.posterUrl)
                        put("posterUrl", item.posterUrl)
                        put("year", item.releaseYear)
                        put("releaseYear", item.releaseYear)
                        put("rating", item.rating)
                        put("user_rating", item.userRating)
                        put("userRating", item.userRating)
                        put("favorite", item.favorite)
                        put("watchlist", item.watchlist)
                        put("updated_at", item.updatedAtMs)
                    }
                )
            }
            return arr
        }

        fun serializePendingMutationsArray(mutations: List<PendingTmdbMutation>): JSONArray {
            val arr = JSONArray()
            for (mut in mutations) {
                if (mut.tmdbId <= 0 && mut.title.isBlank()) continue
                val normalizedType = mut.normalizedMediaType
                arr.put(
                    JSONObject().apply {
                        put("tmdb_id", mut.tmdbId)
                        put("media_id", mut.mediaId)
                        put("media_type", normalizedType)
                        put("title", mut.title)
                        put("year", mut.year)
                        put("category", mut.category.name)
                        put("action", mut.action.name)
                        put("user_rating", mut.userRating)
                        put("updated_at", mut.updatedAtMs)
                    }
                )
            }
            return arr
        }

        fun parseSyncItemsArray(arr: JSONArray?): List<TmdbSyncItem> {
            if (arr == null) return emptyList()
            val dedupMap = LinkedHashMap<String, TmdbSyncItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val tmdbId = obj.optInt("tmdb_id", obj.optInt("tmdbId", obj.optInt("id", 0)))
                val title = obj.optString("title", obj.optString("name", "")).trim()
                if (tmdbId <= 0 && title.isEmpty()) continue
                val rawType = obj.optString("media_type", obj.optString("mediaType", "MOVIE")).lowercase()
                val normalizedType = if (rawType == "tv" || rawType == "series") "SERIES" else "MOVIE"
                val posterUrl = obj.optString("poster_url", obj.optString("posterUrl", "")).trim()
                val releaseYear = obj.optString("year", obj.optString("releaseYear", "")).trim()
                val rating = obj.optDouble("rating", 0.0).let { if (it.isNaN()) 0.0 else it }
                val userRating = obj.optDouble("user_rating", obj.optDouble("userRating", 0.0)).let { if (it.isNaN()) 0.0 else it }
                val updatedAtMs = obj.optLong("updated_at", System.currentTimeMillis())

                val item = TmdbSyncItem(
                    tmdbId = tmdbId,
                    mediaType = normalizedType,
                    title = title,
                    posterUrl = posterUrl,
                    releaseYear = releaseYear,
                    rating = rating,
                    userRating = userRating,
                    favorite = obj.optBoolean("favorite", true),
                    watchlist = obj.optBoolean("watchlist", true),
                    updatedAtMs = updatedAtMs
                )
                val key = if (tmdbId > 0) "$normalizedType:$tmdbId" else "$normalizedType:${title.lowercase()}"
                if (!dedupMap.containsKey(key)) {
                    dedupMap[key] = item
                }
            }
            return dedupMap.values.toList()
        }

        fun cleanIptvTitleForTmdb(rawName: String): String {
            return extractTmdbQuery(rawName).cleanTitle
        }

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
