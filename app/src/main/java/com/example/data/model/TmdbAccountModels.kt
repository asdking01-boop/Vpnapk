package com.example.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TmdbAuthStatus {
    DISCONNECTED,
    AUTHORIZING_IN_BROWSER,
    VERIFYING_SESSION,
    CONNECTED
}

enum class TmdbSyncStatus {
    IDLE,
    SYNCING,
    SUCCESS,
    ERROR
}

data class TmdbSyncItem(
    val tmdbId: Int,
    val mediaType: String, // "movie" or "tv"
    val title: String,
    val posterUrl: String = "",
    val releaseYear: String = "",
    val rating: Double = 0.0,
    val userRating: Double = 0.0
)

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
 * Never holds or exposes TMDB_API_KEY, request tokens, or user session tokens.
 */
data class TmdbAccountUiState(
    val authStatus: TmdbAuthStatus = TmdbAuthStatus.DISCONNECTED,
    val syncStatus: TmdbSyncStatus = TmdbSyncStatus.IDLE,
    val accountId: Int = 0,
    val username: String = "",
    val displayName: String = "",
    val avatarUrl: String = "",
    val autoSyncEnabled: Boolean = true,
    val lastSyncTimestampMs: Long = 0L,
    val syncedFavoritesCount: Int = 0,
    val syncedWatchlistCount: Int = 0,
    val syncedRatingsCount: Int = 0,
    val statusMessage: String? = null,
    val isError: Boolean = false
) {
    val isConnected: Boolean
        get() = authStatus == TmdbAuthStatus.CONNECTED && username.isNotBlank()

    val isBusy: Boolean
        get() = authStatus == TmdbAuthStatus.VERIFYING_SESSION || syncStatus == TmdbSyncStatus.SYNCING

    fun statusBadgeLabel(isArabic: Boolean): String {
        return when (authStatus) {
            TmdbAuthStatus.CONNECTED -> if (isArabic) "متصل" else "Connected"
            TmdbAuthStatus.AUTHORIZING_IN_BROWSER -> if (isArabic) "بانتظار التفويض" else "Awaiting Approval"
            TmdbAuthStatus.VERIFYING_SESSION -> if (isArabic) "جاري التحقق..." else "Connecting..."
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
