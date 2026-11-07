package com.example.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TmdbAuthStatus {
    DISCONNECTED,
    LOGGING_IN,
    AUTHORIZING_IN_BROWSER,
    VERIFYING_SESSION,
    CONNECTED
}

sealed class TmdbAuthResult {
    data class Success(
        val apiKey: String,
        val sessionId: String,
        val accountId: Int,
        val username: String,
        val displayName: String,
        val avatarUrl: String
    ) : TmdbAuthResult()

    data class Error(
        val message: String,
        val statusCode: Int = 0,
        val authErrorType: TmdbAuthErrorType = TmdbAuthErrorType.NONE
    ) : TmdbAuthResult()
}

enum class TmdbAuthErrorType {
    NONE,
    WRONG_CREDENTIALS,
    INVALID_API_KEY,
    NO_INTERNET,
    CANCELLED,
    DENIED,
    EXPIRED_TOKEN,
    STATE_MISMATCH,
    NETWORK_ERROR,
    SERVICE_UNAVAILABLE
}

enum class TmdbSyncStatus {
    IDLE,
    SYNCING,
    SUCCESS,
    ERROR
}

enum class TmdbMutationCategory {
    FAVORITE,
    WATCHLIST,
    RATING
}

enum class TmdbMutationAction {
    ADD,
    REMOVE,
    RATE
}

data class TmdbSyncItem(
    val tmdbId: Int,
    val mediaType: String, // "MOVIE" or "SERIES" (also accepts "movie" or "tv")
    val title: String,
    val posterUrl: String = "",
    val releaseYear: String = "",
    val rating: Double = 0.0,
    val userRating: Double = 0.0,
    val favorite: Boolean = true,
    val watchlist: Boolean = true,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    val normalizedMediaType: String
        get() = if (mediaType.equals("tv", ignoreCase = true) || mediaType.equals("series", ignoreCase = true)) {
            "SERIES"
        } else {
            "MOVIE"
        }

    val deduplicationKey: String
        get() = "$normalizedMediaType:$tmdbId"
}

/**
 * Tracks offline or un-synced user mutations (add/remove Favorite, add/remove Watchlist, set Rating)
 * per KRAS TV user profile so changes made while offline or before reconnecting are replayed and
 * local removals are never overwritten by stale remote lists during conflict resolution.
 */
data class PendingTmdbMutation(
    val krasAccountId: String,
    val tmdbId: Int,
    val mediaId: String,
    val mediaType: String, // "MOVIE" or "SERIES"
    val title: String = "",
    val year: String = "",
    val category: TmdbMutationCategory,
    val action: TmdbMutationAction,
    val userRating: Double = 0.0,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    val normalizedMediaType: String
        get() = if (mediaType.equals("tv", ignoreCase = true) || mediaType.equals("series", ignoreCase = true)) {
            "SERIES"
        } else {
            "MOVIE"
        }

    val deduplicationKey: String
        get() = "${category.name}:$normalizedMediaType:${if (tmdbId > 0) tmdbId else mediaId}"
}

data class TmdbWatchlistItem(
    val id: String,
    val mediaId: String,
    val tmdbId: Int = 0,
    val type: MediaType,
    val title: String,
    val posterUrl: String = "",
    val rating: Double = 0.0,
    val year: String = "",
    val categoryName: String = "",
    val streamUrl: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class TmdbUserRatingItem(
    val mediaId: String,
    val tmdbId: Int = 0,
    val type: MediaType,
    val title: String,
    val posterUrl: String = "",
    val year: String = "",
    val userRating: Double, // 0.5 .. 10.0
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * UI state for the "TMDb Account & Sync" section in Settings.
 * Scoped per active KRAS TV user account.
 * Never holds or exposes TMDB_API_KEY, request tokens, or user session tokens.
 */
data class TmdbAccountUiState(
    val krasAccountId: String = "demo",
    val authStatus: TmdbAuthStatus = TmdbAuthStatus.DISCONNECTED,
    val syncStatus: TmdbSyncStatus = TmdbSyncStatus.IDLE,
    val accountId: Int = 0,
    val username: String = "",
    val displayName: String = "",
    val avatarUrl: String = "",
    val savedApiKey: String = "",
    val autoSyncEnabled: Boolean = true,
    val lastSyncTimestampMs: Long = 0L,
    val syncedFavoritesCount: Int = 0,
    val syncedWatchlistCount: Int = 0,
    val syncedRatingsCount: Int = 0,
    val pendingOfflineChangesCount: Int = 0,
    val statusMessage: String? = null,
    val isError: Boolean = false
) {
    val isConnected: Boolean
        get() = authStatus == TmdbAuthStatus.CONNECTED && username.isNotBlank()

    val isBusy: Boolean
        get() = authStatus == TmdbAuthStatus.LOGGING_IN || authStatus == TmdbAuthStatus.VERIFYING_SESSION || syncStatus == TmdbSyncStatus.SYNCING

    fun statusBadgeLabel(isArabic: Boolean): String {
        return when (authStatus) {
            TmdbAuthStatus.CONNECTED -> if (isArabic) "متصل" else "Connected"
            TmdbAuthStatus.LOGGING_IN,
            TmdbAuthStatus.VERIFYING_SESSION -> if (isArabic) "جارٍ تسجيل الدخول..." else "Logging in..."
            TmdbAuthStatus.AUTHORIZING_IN_BROWSER -> if (isArabic) "غير متصل" else "Not Connected"
            TmdbAuthStatus.DISCONNECTED -> if (isArabic) "غير متصل" else "Not Connected"
        }
    }

    fun lastSyncFormatted(isArabic: Boolean, nowMs: Long = System.currentTimeMillis()): String {
        if (lastSyncTimestampMs <= 0L) {
            return if (isArabic) "لم تتم المزامنة بعد" else "Never synced"
        }
        val diffMs = (nowMs - lastSyncTimestampMs).coerceAtLeast(0L)
        val diffMinutes = diffMs / 60_000L
        if (diffMinutes < 1L) {
            return if (isArabic) "الآن" else "Just now"
        }
        if (diffMinutes < 60L) {
            return if (isArabic) "منذ $diffMinutes دقيقة" else "${diffMinutes}m ago"
        }
        val locale = if (isArabic) Locale.forLanguageTag("ar") else Locale.US
        val formatter = SimpleDateFormat("MMM d, HH:mm", locale)
        return formatter.format(Date(lastSyncTimestampMs))
    }
}
