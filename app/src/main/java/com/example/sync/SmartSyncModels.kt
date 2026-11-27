package com.example.sync

import com.example.data.model.UserSettings

/**
 * All independently tracked synchronization domains in the KRAS TV Global Smart Sync Engine.
 * Each content type has its own persistent timestamp, HTTP ETag/Last-Modified cache,
 * failure backoff state, and configurable refresh interval, isolated per IPTV account.
 */
enum class SyncContentType(
    val defaultIntervalHours: Int,
    val labelEn: String,
    val labelAr: String,
    val isChangeDrivenOnly: Boolean = false
) {
    MOVIES(
        defaultIntervalHours = 6,
        labelEn = "Movies",
        labelAr = "الأفلام"
    ),
    SERIES(
        defaultIntervalHours = 6,
        labelEn = "TV Series",
        labelAr = "المسلسلات"
    ),
    LIVE_TV(
        defaultIntervalHours = 12,
        labelEn = "Live TV Channels",
        labelAr = "القنوات المباشرة"
    ),
    CATEGORIES(
        defaultIntervalHours = 24,
        labelEn = "Categories",
        labelAr = "التصنيفات"
    ),
    EPG(
        defaultIntervalHours = 3,
        labelEn = "EPG Program Guide",
        labelAr = "دليل البرامج EPG"
    ),
    TMDB_METADATA(
        defaultIntervalHours = 24,
        labelEn = "TMDb Metadata",
        labelAr = "بيانات TMDb"
    ),
    ACCOUNT_STATUS(
        defaultIntervalHours = 24,
        labelEn = "Account Expiration Status",
        labelAr = "حالة صلاحية الحساب"
    ),
    FAVORITES_HISTORY(
        defaultIntervalHours = 0,
        labelEn = "Favorites & Watch History",
        labelAr = "المفضلة وسجل المشاهدة",
        isChangeDrivenOnly = true
    );

    fun label(isArabic: Boolean): String = if (isArabic) labelAr else labelEn

    fun configuredIntervalHours(settings: UserSettings): Int = when (this) {
        MOVIES -> settings.syncIntervalMoviesHours.coerceIn(1, 168)
        SERIES -> settings.syncIntervalSeriesHours.coerceIn(1, 168)
        LIVE_TV -> settings.syncIntervalLiveHours.coerceIn(1, 168)
        CATEGORIES -> settings.syncIntervalCategoriesHours.coerceIn(1, 168)
        EPG -> settings.syncIntervalEpgHours.coerceIn(1, 48)
        TMDB_METADATA -> settings.syncIntervalTmdbHours.coerceIn(1, 168)
        ACCOUNT_STATUS -> settings.syncIntervalAccountHours.coerceIn(1, 168)
        FAVORITES_HISTORY -> 0
    }

    fun effectiveIntervalMs(settings: UserSettings): Long {
        if (isChangeDrivenOnly) return Long.MAX_VALUE
        val baseHours = configuredIntervalHours(settings).toLong()
        val multiplier = if (settings.batterySavingSyncMode) 2L else 1L
        return baseHours * multiplier * ONE_HOUR_MS
    }

    companion object {
        const val ONE_HOUR_MS = 3_600_000L

        val catalogAndMetadataTypes: List<SyncContentType> = listOf(
            ACCOUNT_STATUS,
            CATEGORIES,
            LIVE_TV,
            MOVIES,
            SERIES,
            EPG,
            TMDB_METADATA
        )

        val scheduledIntervalTypes: List<SyncContentType> = listOf(
            MOVIES,
            SERIES,
            LIVE_TV,
            CATEGORIES,
            EPG,
            TMDB_METADATA,
            ACCOUNT_STATUS
        )
    }
}

/**
 * Classifies why a synchronization pass was requested.
 * Automatic triggers stay 100% silent and strictly obey intervals, constraints, and backoff.
 * Full reload only happens on missing local data, new account, credentials change, or explicit Full Refresh.
 */
enum class SyncTriggerReason(
    val isManual: Boolean,
    val forcesFullRefresh: Boolean
) {
    APP_STARTUP(isManual = false, forcesFullRefresh = false),
    PERIODIC_BACKGROUND(isManual = false, forcesFullRefresh = false),
    NEW_ACCOUNT(isManual = false, forcesFullRefresh = true),
    CREDENTIALS_CHANGED(isManual = false, forcesFullRefresh = true),
    LOCAL_DATA_MISSING(isManual = false, forcesFullRefresh = true),
    DATA_CHANGED(isManual = false, forcesFullRefresh = false),
    MANUAL_SYNC_NOW(isManual = true, forcesFullRefresh = false),
    MANUAL_FULL_REFRESH(isManual = true, forcesFullRefresh = true),
    MANUAL_SINGLE_TYPE(isManual = true, forcesFullRefresh = false)
}

enum class SyncOutcome {
    NEVER,
    SUCCESS,
    NOT_MODIFIED,
    SKIPPED_FRESH,
    SKIPPED_CONSTRAINTS,
    SKIPPED_OFFLINE,
    SKIPPED_BACKOFF,
    SKIPPED_UNCHANGED,
    FAILED
}

data class SyncDeviceConstraints(
    val isNetworkAvailable: Boolean = true,
    val isWifiConnected: Boolean = true,
    val isBatteryLowOrPowerSave: Boolean = false
)

data class SmartSyncDecision(
    val shouldSync: Boolean,
    val isFullSync: Boolean,
    val skipOutcome: SyncOutcome? = null,
    val reason: String = ""
)

data class ContentSyncStatus(
    val type: SyncContentType,
    val lastSuccessMs: Long = 0L,
    val lastAttemptMs: Long = 0L,
    val nextScheduledMs: Long = 0L,
    val isRunning: Boolean = false,
    val consecutiveFailures: Int = 0,
    val nextRetryAtMs: Long = 0L,
    val lastError: String? = null,
    val lastOutcome: SyncOutcome = SyncOutcome.NEVER
)

data class SmartSyncUiState(
    val accountId: String = "",
    val isManualSyncRunning: Boolean = false,
    val isBackgroundSyncRunning: Boolean = false,
    val activeManualMessage: String? = null,
    val activeSyncingTypes: Set<SyncContentType> = emptySet(),
    val statuses: Map<SyncContentType, ContentSyncStatus> = emptyMap(),
    val nextGlobalSyncMs: Long = 0L,
    val lastManualError: String? = null,
    val lastManualCompletedMs: Long = 0L
)

data class SmartSyncRunReport(
    val accountId: String,
    val triggerReason: SyncTriggerReason,
    val outcomes: Map<SyncContentType, SyncOutcome>,
    val updatedTypes: Set<SyncContentType>,
    val failedTypes: Map<SyncContentType, String>,
    val totalNetworkRequests: Int,
    val hadAnyCatalogChange: Boolean
)
