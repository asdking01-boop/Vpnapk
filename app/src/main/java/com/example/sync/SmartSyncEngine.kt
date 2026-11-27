package com.example.sync

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import com.example.data.api.ConditionalFetchResult
import com.example.data.api.ConditionalHttpHeaders
import com.example.data.api.M3uPlaylistParser
import com.example.data.api.XtreamClient
import com.example.data.local.KrasRepository
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.MediaItemEntity
import com.example.data.model.Account
import com.example.data.model.MediaType
import com.example.data.model.SeriesDetails
import com.example.data.model.UserSettings
import com.example.data.sample.SampleData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized KRAS TV Global Smart Sync Engine.
 *
 * Single source of truth for ALL synchronization across app startup, WorkManager background
 * scheduling, account switching, and manual Settings controls:
 * - Strictly honors persistent per-account, per-content-type intervals.
 * - Uses HTTP conditional requests (ETag / If-None-Match and Last-Modified / If-Modified-Since)
 *   to skip unchanged downloads on HTTP 304 Not Modified.
 * - Uses incremental Room diff/upserts (never wipes local cache before a successful fetch).
 * - Deduplicates concurrent sync requests per (accountId, SyncContentType) so duplicate jobs
 *   never run in parallel.
 * - Keeps automatic background syncs 100% silent (no progress popups or success toasts).
 * - Shows sync progress ONLY when manually initiated by the user.
 * - Applies exponential retry backoff on failures and NEVER marks failed syncs as successful.
 */
class SmartSyncEngine(
    private val context: Context,
    val repository: KrasRepository = KrasRepository(context),
    val store: SmartSyncStore = SmartSyncStore(context),
    private val xtreamClient: XtreamClient = XtreamClient(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val deviceConstraintsProvider: () -> SyncDeviceConstraints = { readDeviceConstraints(context) }
) {

    // Single-flight guard keyed by "${accountId}:${type.name}"
    private val inFlightKeys = ConcurrentHashMap<String, Boolean>()

    // Optional in-memory M3U parsed series details cache shared with UI
    val m3uSeriesDetailsCache = ConcurrentHashMap<Int, SeriesDetails>()

    private val _uiState = MutableStateFlow(SmartSyncUiState())
    val uiState: StateFlow<SmartSyncUiState> = _uiState.asStateFlow()

    /**
     * Rebuilds the exposed [SmartSyncUiState] snapshot for the specified account & settings
     * without triggering any network or database writes.
     */
    fun refreshUiState(accountId: String, settings: UserSettings) {
        val activeTypes = SyncContentType.entries.filter { type ->
            inFlightKeys.containsKey(flightKey(accountId, type))
        }.toSet()

        val statusMap = LinkedHashMap<SyncContentType, ContentSyncStatus>()
        var earliestNextMs = Long.MAX_VALUE

        for (type in SyncContentType.entries) {
            val intervalMs = type.effectiveIntervalMs(settings)
            val status = store.buildStatus(
                accountId = accountId,
                type = type,
                intervalMs = intervalMs,
                isRunning = type in activeTypes
            )
            statusMap[type] = status
            if (!type.isChangeDrivenOnly && status.nextScheduledMs > 0L && status.nextScheduledMs < earliestNextMs) {
                earliestNextMs = status.nextScheduledMs
            }
        }

        val current = _uiState.value
        _uiState.value = current.copy(
            accountId = accountId,
            activeSyncingTypes = activeTypes,
            statuses = statusMap,
            nextGlobalSyncMs = if (earliestNextMs == Long.MAX_VALUE) 0L else earliestNextMs
        )
    }

    /**
     * Pure deterministic decision logic for whether a given [SyncContentType] should sync right now.
     */
    fun evaluateSyncDecision(
        account: Account,
        type: SyncContentType,
        triggerReason: SyncTriggerReason,
        settings: UserSettings,
        nowMs: Long,
        isLocalDataMissing: Boolean,
        hasPendingLocalChanges: Boolean,
        constraints: SyncDeviceConstraints
    ): SmartSyncDecision {
        // Explicit Full Refresh always runs and forces a complete reload (bypassing ETag & intervals)
        if (triggerReason == SyncTriggerReason.MANUAL_FULL_REFRESH) {
            return SmartSyncDecision(
                shouldSync = true,
                isFullSync = true,
                reason = "Manual full refresh requested"
            )
        }

        // Manual Sync Now or Manual Single-Type Sync bypasses timer & backoff, but uses incremental/ETag when data exists
        if (triggerReason == SyncTriggerReason.MANUAL_SYNC_NOW || triggerReason == SyncTriggerReason.MANUAL_SINGLE_TYPE) {
            return SmartSyncDecision(
                shouldSync = true,
                isFullSync = isLocalDataMissing,
                reason = "Manual sync requested"
            )
        }

        // New account or changed credentials trigger full sync (unless offline)
        if (triggerReason == SyncTriggerReason.NEW_ACCOUNT || triggerReason == SyncTriggerReason.CREDENTIALS_CHANGED) {
            if (!constraints.isNetworkAvailable && !account.isDemo) {
                return SmartSyncDecision(
                    shouldSync = false,
                    isFullSync = false,
                    skipOutcome = SyncOutcome.SKIPPED_OFFLINE,
                    reason = "Network unavailable"
                )
            }
            return SmartSyncDecision(
                shouldSync = true,
                isFullSync = true,
                reason = if (triggerReason == SyncTriggerReason.NEW_ACCOUNT) "New account" else "Credentials changed"
            )
        }

        // Event-driven Favorites & Watch History: sync ONLY when changed!
        if (type == SyncContentType.FAVORITES_HISTORY) {
            if (!hasPendingLocalChanges) {
                return SmartSyncDecision(
                    shouldSync = false,
                    isFullSync = false,
                    skipOutcome = SyncOutcome.SKIPPED_UNCHANGED,
                    reason = "Favorites and watch history unchanged"
                )
            }
            if (!settings.smartAutoSyncEnabled) {
                return SmartSyncDecision(
                    shouldSync = false,
                    isFullSync = false,
                    skipOutcome = SyncOutcome.SKIPPED_CONSTRAINTS,
                    reason = "Smart Auto Sync disabled"
                )
            }
            if (!constraints.isNetworkAvailable) {
                return SmartSyncDecision(
                    shouldSync = false,
                    isFullSync = false,
                    skipOutcome = SyncOutcome.SKIPPED_OFFLINE,
                    reason = "Offline"
                )
            }
            if (settings.syncWifiOnly && !constraints.isWifiConnected) {
                return SmartSyncDecision(
                    shouldSync = false,
                    isFullSync = false,
                    skipOutcome = SyncOutcome.SKIPPED_CONSTRAINTS,
                    reason = "Wi-Fi Only mode active"
                )
            }
            val nextRetryAt = store.getNextRetryAtMs(account.id, type)
            if (nextRetryAt > 0L && nowMs < nextRetryAt) {
                return SmartSyncDecision(
                    shouldSync = false,
                    isFullSync = false,
                    skipOutcome = SyncOutcome.SKIPPED_BACKOFF,
                    reason = "Retry backoff active"
                )
            }
            return SmartSyncDecision(
                shouldSync = true,
                isFullSync = false,
                reason = "Pending local Favorites/Watch History changes"
            )
        }

        // Automatic triggers for catalog, EPG, TMDb, and account status
        if (!settings.smartAutoSyncEnabled && !isLocalDataMissing) {
            return SmartSyncDecision(
                shouldSync = false,
                isFullSync = false,
                skipOutcome = SyncOutcome.SKIPPED_CONSTRAINTS,
                reason = "Smart Auto Sync disabled"
            )
        }

        if (!constraints.isNetworkAvailable && !account.isDemo) {
            return SmartSyncDecision(
                shouldSync = false,
                isFullSync = false,
                skipOutcome = SyncOutcome.SKIPPED_OFFLINE,
                reason = "Offline"
            )
        }

        if (settings.syncWifiOnly && !constraints.isWifiConnected && !account.isDemo) {
            return SmartSyncDecision(
                shouldSync = false,
                isFullSync = false,
                skipOutcome = SyncOutcome.SKIPPED_CONSTRAINTS,
                reason = "Wi-Fi Only mode active"
            )
        }

        if (settings.batterySavingSyncMode && constraints.isBatteryLowOrPowerSave && !isLocalDataMissing && !account.isDemo) {
            return SmartSyncDecision(
                shouldSync = false,
                isFullSync = false,
                skipOutcome = SyncOutcome.SKIPPED_CONSTRAINTS,
                reason = "Battery Saving Sync paused on low battery"
            )
        }

        // Exponential backoff protection after network failures
        val nextRetryAt = store.getNextRetryAtMs(account.id, type)
        if (nextRetryAt > 0L && nowMs < nextRetryAt) {
            return SmartSyncDecision(
                shouldSync = false,
                isFullSync = false,
                skipOutcome = SyncOutcome.SKIPPED_BACKOFF,
                reason = "Waiting for exponential retry backoff"
            )
        }

        // Missing local data justifies an automatic initial full fetch
        if (isLocalDataMissing) {
            return SmartSyncDecision(
                shouldSync = true,
                isFullSync = true,
                reason = "Local cached data missing"
            )
        }

        val lastSuccessMs = store.getLastSuccessMs(account.id, type)
        val intervalMs = type.effectiveIntervalMs(settings)
        if (lastSuccessMs <= 0L || (nowMs - lastSuccessMs) >= intervalMs) {
            return SmartSyncDecision(
                shouldSync = true,
                isFullSync = false,
                reason = "Refresh interval elapsed"
            )
        }

        return SmartSyncDecision(
            shouldSync = false,
            isFullSync = false,
            skipOutcome = SyncOutcome.SKIPPED_FRESH,
            reason = "Cached data is fresh"
        )
    }

    /**
     * Executes a smart synchronization pass for the requested [requestedTypes] on [account].
     *
     * Callbacks allow the caller (`KrasViewModel` or `SmartSyncWorker`) to run TMDb enrichment
     * and Favorites/Watch History sync while sharing the exact same timestamps, single-flight
     * locks, backoff, and silent-background contract.
     */
    suspend fun syncAccount(
        account: Account,
        requestedTypes: List<SyncContentType> = SyncContentType.entries.toList(),
        triggerReason: SyncTriggerReason = SyncTriggerReason.APP_STARTUP,
        settings: UserSettings = repository.getUserSettings(),
        hasPendingFavoritesOrHistoryChanges: Boolean? = null,
        onManualProgress: ((String?) -> Unit)? = null,
        onAccountSubscriptionUpdated: (suspend (Account) -> Unit)? = null,
        onTmdbMetadataSync: (suspend (Account) -> Boolean)? = null,
        onFavoritesHistorySync: (suspend (Account) -> Boolean)? = null
    ): SmartSyncRunReport = withContext(Dispatchers.IO) {
        val nowMs = clockMs()
        val constraints = deviceConstraintsProvider()
        val isManual = triggerReason.isManual

        // Detect new account or credentials change automatically (full refresh condition)
        val effectiveReason = when {
            isManual -> triggerReason
            store.isNewAccount(account) -> SyncTriggerReason.NEW_ACCOUNT
            store.hasCredentialsChanged(account) -> SyncTriggerReason.CREDENTIALS_CHANGED
            else -> triggerReason
        }

        if (effectiveReason == SyncTriggerReason.CREDENTIALS_CHANGED || effectiveReason == SyncTriggerReason.MANUAL_FULL_REFRESH) {
            store.clearConditionalHeaders(account.id)
        }

        val outcomes = LinkedHashMap<SyncContentType, SyncOutcome>()
        val updatedTypes = LinkedHashSet<SyncContentType>()
        val failedTypes = LinkedHashMap<SyncContentType, String>()
        var totalNetworkRequests = 0
        var hadAnyCatalogChange = false

        fun reportManualProgress(msg: String?) {
            if (isManual) {
                onManualProgress?.invoke(msg)
                _uiState.value = _uiState.value.copy(
                    isManualSyncRunning = msg != null,
                    activeManualMessage = msg
                )
            }
        }

        if (isManual) {
            _uiState.value = _uiState.value.copy(
                accountId = account.id,
                isManualSyncRunning = true,
                lastManualError = null
            )
        } else {
            _uiState.value = _uiState.value.copy(
                accountId = account.id,
                isBackgroundSyncRunning = true
            )
        }

        try {
            // --- Branch 1: Demo Account (seed locally ONCE if missing or on explicit Full Refresh) ---
            if (account.isDemo) {
                val moviesCount = repository.countLocalMedia(account.id, "MOVIE")
                val seriesCount = repository.countLocalMedia(account.id, "SERIES")
                val liveCount = repository.countLocalMedia(account.id, "LIVE")
                val missingDemoData = moviesCount == 0 || seriesCount == 0 || liveCount == 0

                val shouldSeedDemo = missingDemoData ||
                    effectiveReason == SyncTriggerReason.MANUAL_FULL_REFRESH ||
                    effectiveReason == SyncTriggerReason.MANUAL_SYNC_NOW ||
                    effectiveReason == SyncTriggerReason.MANUAL_SINGLE_TYPE

                if (shouldSeedDemo) {
                    if (isManual) reportManualProgress("Refreshing demo catalog...")
                    val movieEntities = MediaItemEntity.fromMediaItemList(SampleData.sampleMovies, account.id)
                    val seriesEntities = MediaItemEntity.fromMediaItemList(SampleData.sampleSeries, account.id)
                    val liveEntities = MediaItemEntity.fromMediaItemList(SampleData.sampleLiveStreams, account.id)

                    val movieCatEntities = SampleData.movieCategories.map { CategoryEntity.fromCategory(it, account.id) }
                    val seriesCatEntities = SampleData.seriesCategories.map { CategoryEntity.fromCategory(it, account.id) }
                    val liveCatEntities = SampleData.liveCategories.map { CategoryEntity.fromCategory(it, account.id) }

                    repository.syncMediaIncremental(account.id, "MOVIE", movieEntities, pruneRemoved = false)
                    repository.syncMediaIncremental(account.id, "SERIES", seriesEntities, pruneRemoved = false)
                    repository.syncMediaIncremental(account.id, "LIVE", liveEntities, pruneRemoved = false)
                    repository.saveCategoriesIfChanged(account.id, MediaType.MOVIE, movieCatEntities)
                    repository.saveCategoriesIfChanged(account.id, MediaType.SERIES, seriesCatEntities)
                    repository.saveCategoriesIfChanged(account.id, MediaType.LIVE, liveCatEntities)
                    hadAnyCatalogChange = true

                    for (t in listOf(
                        SyncContentType.ACCOUNT_STATUS,
                        SyncContentType.CATEGORIES,
                        SyncContentType.LIVE_TV,
                        SyncContentType.MOVIES,
                        SyncContentType.SERIES,
                        SyncContentType.EPG
                    )) {
                        if (t in requestedTypes) {
                            store.recordSuccess(account.id, t, nowMs, SyncOutcome.SUCCESS)
                            outcomes[t] = SyncOutcome.SUCCESS
                            updatedTypes.add(t)
                        }
                    }
                    store.saveCredentialsFingerprint(account)
                } else {
                    for (t in requestedTypes) {
                        if (t != SyncContentType.TMDB_METADATA && t != SyncContentType.FAVORITES_HISTORY) {
                            outcomes[t] = SyncOutcome.SKIPPED_FRESH
                        }
                    }
                }

                // TMDb enrichment for demo account obeys 24h Smart Sync interval
                if (SyncContentType.TMDB_METADATA in requestedTypes && onTmdbMetadataSync != null) {
                    val tmdbDecision = evaluateSyncDecision(
                        account = account,
                        type = SyncContentType.TMDB_METADATA,
                        triggerReason = effectiveReason,
                        settings = settings,
                        nowMs = nowMs,
                        isLocalDataMissing = false,
                        hasPendingLocalChanges = false,
                        constraints = constraints
                    )
                    if (tmdbDecision.shouldSync && tryAcquireFlight(account.id, SyncContentType.TMDB_METADATA)) {
                        try {
                            if (isManual) reportManualProgress("Updating TMDb metadata...")
                            val ok = onTmdbMetadataSync(account)
                            if (ok) {
                                store.recordSuccess(account.id, SyncContentType.TMDB_METADATA, nowMs, SyncOutcome.SUCCESS)
                                outcomes[SyncContentType.TMDB_METADATA] = SyncOutcome.SUCCESS
                                updatedTypes.add(SyncContentType.TMDB_METADATA)
                            } else {
                                store.recordFailure(account.id, SyncContentType.TMDB_METADATA, nowMs, "TMDb metadata update failed")
                                outcomes[SyncContentType.TMDB_METADATA] = SyncOutcome.FAILED
                                failedTypes[SyncContentType.TMDB_METADATA] = "TMDb metadata update failed"
                            }
                        } finally {
                            releaseFlight(account.id, SyncContentType.TMDB_METADATA)
                        }
                    } else {
                        outcomes[SyncContentType.TMDB_METADATA] = tmdbDecision.skipOutcome ?: SyncOutcome.SKIPPED_FRESH
                    }
                }

                // Favorites/Watch History sync only when changed (or manual)
                if (SyncContentType.FAVORITES_HISTORY in requestedTypes && onFavoritesHistorySync != null) {
                    val dirty = hasPendingFavoritesOrHistoryChanges
                        ?: (store.isFavoritesOrHistoryDirty(account.id) || repository.getPendingTmdbMutations(account.id).isNotEmpty())
                    val favDecision = evaluateSyncDecision(
                        account = account,
                        type = SyncContentType.FAVORITES_HISTORY,
                        triggerReason = effectiveReason,
                        settings = settings,
                        nowMs = nowMs,
                        isLocalDataMissing = false,
                        hasPendingLocalChanges = dirty,
                        constraints = constraints
                    )
                    if (favDecision.shouldSync && tryAcquireFlight(account.id, SyncContentType.FAVORITES_HISTORY)) {
                        try {
                            val ok = onFavoritesHistorySync(account)
                            if (ok) {
                                store.clearFavoritesOrHistoryDirty(account.id)
                                store.recordSuccess(account.id, SyncContentType.FAVORITES_HISTORY, nowMs, SyncOutcome.SUCCESS)
                                outcomes[SyncContentType.FAVORITES_HISTORY] = SyncOutcome.SUCCESS
                                updatedTypes.add(SyncContentType.FAVORITES_HISTORY)
                            } else {
                                store.recordFailure(account.id, SyncContentType.FAVORITES_HISTORY, nowMs, "Favorites sync failed")
                                outcomes[SyncContentType.FAVORITES_HISTORY] = SyncOutcome.FAILED
                                failedTypes[SyncContentType.FAVORITES_HISTORY] = "Favorites sync failed"
                            }
                        } finally {
                            releaseFlight(account.id, SyncContentType.FAVORITES_HISTORY)
                        }
                    } else {
                        outcomes[SyncContentType.FAVORITES_HISTORY] = favDecision.skipOutcome ?: SyncOutcome.SKIPPED_UNCHANGED
                    }
                }

                return@withContext SmartSyncRunReport(
                    accountId = account.id,
                    triggerReason = effectiveReason,
                    outcomes = outcomes,
                    updatedTypes = updatedTypes,
                    failedTypes = failedTypes,
                    totalNetworkRequests = totalNetworkRequests,
                    hadAnyCatalogChange = hadAnyCatalogChange
                )
            }

            // --- Branch 2: M3U Playlist Account (Single HTTP conditional download serves Live/Movies/Series/Categories) ---
            if (M3uPlaylistParser.isM3uPlaylistUrl(account.serverUrl)) {
                val m3uTypes = listOf(
                    SyncContentType.CATEGORIES,
                    SyncContentType.LIVE_TV,
                    SyncContentType.MOVIES,
                    SyncContentType.SERIES
                )
                val requestedM3uTypes = requestedTypes.filter { it in m3uTypes }
                if (requestedM3uTypes.isNotEmpty()) {
                    val missingM3u = repository.countLocalMedia(account.id, "LIVE") == 0 &&
                        repository.countLocalMedia(account.id, "MOVIE") == 0 &&
                        repository.countLocalMedia(account.id, "SERIES") == 0

                    val primaryDecision = evaluateSyncDecision(
                        account = account,
                        type = requestedM3uTypes.first(),
                        triggerReason = effectiveReason,
                        settings = settings,
                        nowMs = nowMs,
                        isLocalDataMissing = missingM3u,
                        hasPendingLocalChanges = false,
                        constraints = constraints
                    )

                    if (primaryDecision.shouldSync && tryAcquireFlight(account.id, SyncContentType.LIVE_TV)) {
                        try {
                            if (isManual) reportManualProgress("Syncing M3U playlist...")
                            val headers = if (primaryDecision.isFullSync) null else store.getConditionalHeaders(account.id, "m3u")
                            totalNetworkRequests++
                            when (val fetchRes = xtreamClient.fetchM3uPlaylistConditional(account, headers)) {
                                is ConditionalFetchResult.NotModified -> {
                                    for (t in m3uTypes) {
                                        store.recordSuccess(account.id, t, nowMs, SyncOutcome.NOT_MODIFIED, "m3u", fetchRes.headers)
                                        outcomes[t] = SyncOutcome.NOT_MODIFIED
                                    }
                                    store.saveCredentialsFingerprint(account)
                                }
                                is ConditionalFetchResult.Success -> {
                                    val m3u = fetchRes.data
                                    val c1 = repository.saveCategoriesIfChanged(account.id, MediaType.LIVE, m3u.liveCategories)
                                    val c2 = repository.saveCategoriesIfChanged(account.id, MediaType.MOVIE, m3u.movieCategories)
                                    val c3 = repository.saveCategoriesIfChanged(account.id, MediaType.SERIES, m3u.seriesCategories)
                                    val r1 = repository.syncMediaIncremental(account.id, "LIVE", m3u.liveStreams, pruneRemoved = true)
                                    val r2 = repository.syncMediaIncremental(account.id, "MOVIE", m3u.movies, pruneRemoved = true)
                                    val r3 = repository.syncMediaIncremental(account.id, "SERIES", m3u.series, pruneRemoved = true)

                                    m3uSeriesDetailsCache.clear()
                                    m3uSeriesDetailsCache.putAll(m3u.seriesDetailsBySeriesId)

                                    if (c1 || c2 || c3 || r1.upsertedCount > 0 || r1.deletedCount > 0 ||
                                        r2.upsertedCount > 0 || r2.deletedCount > 0 ||
                                        r3.upsertedCount > 0 || r3.deletedCount > 0
                                    ) {
                                        hadAnyCatalogChange = true
                                    }

                                    for (t in m3uTypes) {
                                        store.recordSuccess(account.id, t, nowMs, SyncOutcome.SUCCESS, "m3u", fetchRes.headers)
                                        outcomes[t] = SyncOutcome.SUCCESS
                                        updatedTypes.add(t)
                                    }
                                    store.saveCredentialsFingerprint(account)
                                }
                                is ConditionalFetchResult.Failure -> {
                                    for (t in requestedM3uTypes) {
                                        store.recordFailure(account.id, t, nowMs, fetchRes.error)
                                        outcomes[t] = SyncOutcome.FAILED
                                        failedTypes[t] = fetchRes.error
                                    }
                                }
                            }
                        } finally {
                            releaseFlight(account.id, SyncContentType.LIVE_TV)
                        }
                    } else {
                        for (t in requestedM3uTypes) {
                            outcomes[t] = primaryDecision.skipOutcome ?: SyncOutcome.SKIPPED_FRESH
                        }
                    }
                }
            } else {
                // --- Branch 3: Xtream Codes Server Sync (Per-Content-Type Independent Intervals + ETags) ---
                val orderedTypes = listOf(
                    SyncContentType.ACCOUNT_STATUS,
                    SyncContentType.CATEGORIES,
                    SyncContentType.LIVE_TV,
                    SyncContentType.MOVIES,
                    SyncContentType.SERIES,
                    SyncContentType.EPG,
                    SyncContentType.TMDB_METADATA,
                    SyncContentType.FAVORITES_HISTORY
                ).filter { it in requestedTypes }

                var movieCatMap: Map<String, String>? = null
                var seriesCatMap: Map<String, String>? = null
                var liveCatMap: Map<String, String>? = null

                suspend fun resolveCategoryMap(mediaType: MediaType): Map<String, String> {
                    return when (mediaType) {
                        MediaType.MOVIE -> movieCatMap ?: repository.getCategories(account.id, MediaType.MOVIE)
                            .associate { it.categoryId to it.categoryName }.also { movieCatMap = it }
                        MediaType.SERIES -> seriesCatMap ?: repository.getCategories(account.id, MediaType.SERIES)
                            .associate { it.categoryId to it.categoryName }.also { seriesCatMap = it }
                        MediaType.LIVE -> liveCatMap ?: repository.getCategories(account.id, MediaType.LIVE)
                            .associate { it.categoryId to it.categoryName }.also { liveCatMap = it }
                    }
                }

                for (type in orderedTypes) {
                    val isMissing = when (type) {
                        SyncContentType.MOVIES -> repository.countLocalMedia(account.id, "MOVIE") == 0
                        SyncContentType.SERIES -> repository.countLocalMedia(account.id, "SERIES") == 0
                        SyncContentType.LIVE_TV -> repository.countLocalMedia(account.id, "LIVE") == 0
                        SyncContentType.CATEGORIES ->
                            repository.countLocalCategories(account.id, MediaType.MOVIE) == 0 &&
                                repository.countLocalCategories(account.id, MediaType.SERIES) == 0 &&
                                repository.countLocalCategories(account.id, MediaType.LIVE) == 0
                        SyncContentType.ACCOUNT_STATUS -> account.expDate.isBlank() && store.getLastSuccessMs(account.id, type) == 0L
                        else -> false
                    }

                    val pendingFavChanges = if (type == SyncContentType.FAVORITES_HISTORY) {
                        hasPendingFavoritesOrHistoryChanges
                            ?: (store.isFavoritesOrHistoryDirty(account.id) || repository.getPendingTmdbMutations(account.id).isNotEmpty())
                    } else {
                        false
                    }

                    val decision = evaluateSyncDecision(
                        account = account,
                        type = type,
                        triggerReason = effectiveReason,
                        settings = settings,
                        nowMs = nowMs,
                        isLocalDataMissing = isMissing,
                        hasPendingLocalChanges = pendingFavChanges,
                        constraints = constraints
                    )

                    if (!decision.shouldSync) {
                        outcomes[type] = decision.skipOutcome ?: SyncOutcome.SKIPPED_FRESH
                        continue
                    }

                    if (!tryAcquireFlight(account.id, type)) {
                        // Another worker or UI coroutine is already syncing this exact (accountId, type)
                        outcomes[type] = SyncOutcome.SKIPPED_FRESH
                        continue
                    }

                    refreshUiState(account.id, settings)
                    try {
                        when (type) {
                            SyncContentType.ACCOUNT_STATUS -> {
                                if (isManual) reportManualProgress("Checking subscription status...")
                                val hdr = if (decision.isFullSync) null else store.getConditionalHeaders(account.id, "account")
                                totalNetworkRequests++
                                when (val res = xtreamClient.fetchSubscriptionInfoConditional(account, hdr)) {
                                    is ConditionalFetchResult.NotModified -> {
                                        store.recordSuccess(account.id, type, nowMs, SyncOutcome.NOT_MODIFIED, "account", res.headers)
                                        outcomes[type] = SyncOutcome.NOT_MODIFIED
                                    }
                                    is ConditionalFetchResult.Success -> {
                                        repository.updateAccountSubscription(res.data)
                                        onAccountSubscriptionUpdated?.invoke(res.data)
                                        store.recordSuccess(account.id, type, nowMs, SyncOutcome.SUCCESS, "account", res.headers)
                                        outcomes[type] = SyncOutcome.SUCCESS
                                        updatedTypes.add(type)
                                    }
                                    is ConditionalFetchResult.Failure -> {
                                        store.recordFailure(account.id, type, nowMs, res.error)
                                        outcomes[type] = SyncOutcome.FAILED
                                        failedTypes[type] = res.error
                                    }
                                }
                            }

                            SyncContentType.CATEGORIES -> {
                                if (isManual) reportManualProgress("Syncing categories...")
                                var anySuccessOr304 = false
                                var anyChanged = false
                                var all304 = true
                                var lastCatError: String? = null

                                for (catType in listOf(MediaType.MOVIE, MediaType.SERIES, MediaType.LIVE)) {
                                    val subKey = "cat_${catType.name.lowercase()}"
                                    val hdr = if (decision.isFullSync) null else store.getConditionalHeaders(account.id, subKey)
                                    totalNetworkRequests++
                                    when (val res = xtreamClient.fetchCategoriesConditional(account, catType, hdr)) {
                                        is ConditionalFetchResult.NotModified -> {
                                            store.saveConditionalHeaders(account.id, subKey, res.headers)
                                            anySuccessOr304 = true
                                        }
                                        is ConditionalFetchResult.Success -> {
                                            all304 = false
                                            anySuccessOr304 = true
                                            store.saveConditionalHeaders(account.id, subKey, res.headers)
                                            val changed = repository.saveCategoriesIfChanged(account.id, catType, res.data)
                                            if (changed) {
                                                anyChanged = true
                                                hadAnyCatalogChange = true
                                            }
                                            val catMap = res.data.associate { it.categoryId to it.categoryName }
                                            when (catType) {
                                                MediaType.MOVIE -> movieCatMap = catMap
                                                MediaType.SERIES -> seriesCatMap = catMap
                                                MediaType.LIVE -> liveCatMap = catMap
                                            }
                                        }
                                        is ConditionalFetchResult.Failure -> {
                                            all304 = false
                                            lastCatError = res.error
                                        }
                                    }
                                }

                                if (lastCatError != null && !anySuccessOr304) {
                                    store.recordFailure(account.id, type, nowMs, lastCatError)
                                    outcomes[type] = SyncOutcome.FAILED
                                    failedTypes[type] = lastCatError
                                } else {
                                    val outcome = if (all304 && !anyChanged) SyncOutcome.NOT_MODIFIED else SyncOutcome.SUCCESS
                                    store.recordSuccess(account.id, type, nowMs, outcome)
                                    outcomes[type] = outcome
                                    if (anyChanged) updatedTypes.add(type)
                                }
                            }

                            SyncContentType.LIVE_TV -> {
                                if (isManual) reportManualProgress("Syncing live channels...")
                                val hdr = if (decision.isFullSync) null else store.getConditionalHeaders(account.id, "live")
                                val collected = ArrayList<MediaItemEntity>()
                                val catMap = resolveCategoryMap(MediaType.LIVE)
                                totalNetworkRequests++
                                when (val res = xtreamClient.streamLiveStreamsConditional(
                                    account = account,
                                    batchSize = 500,
                                    categoryMap = catMap,
                                    conditionalHeaders = hdr
                                ) { batch -> collected.addAll(batch) }) {
                                    is ConditionalFetchResult.NotModified -> {
                                        store.recordSuccess(account.id, type, nowMs, SyncOutcome.NOT_MODIFIED, "live", res.headers)
                                        outcomes[type] = SyncOutcome.NOT_MODIFIED
                                    }
                                    is ConditionalFetchResult.Success -> {
                                        val diff = repository.syncMediaIncremental(
                                            accountId = account.id,
                                            type = "LIVE",
                                            incoming = collected,
                                            pruneRemoved = collected.isNotEmpty()
                                        )
                                        if (diff.upsertedCount > 0 || diff.deletedCount > 0) {
                                            hadAnyCatalogChange = true
                                            updatedTypes.add(type)
                                        }
                                        store.recordSuccess(account.id, type, nowMs, SyncOutcome.SUCCESS, "live", res.headers)
                                        outcomes[type] = SyncOutcome.SUCCESS
                                    }
                                    is ConditionalFetchResult.Failure -> {
                                        store.recordFailure(account.id, type, nowMs, res.error)
                                        outcomes[type] = SyncOutcome.FAILED
                                        failedTypes[type] = res.error
                                    }
                                }
                            }

                            SyncContentType.MOVIES -> {
                                if (isManual) reportManualProgress("Syncing movies...")
                                val hdr = if (decision.isFullSync) null else store.getConditionalHeaders(account.id, "movies")
                                val collected = ArrayList<MediaItemEntity>()
                                val catMap = resolveCategoryMap(MediaType.MOVIE)
                                totalNetworkRequests++
                                when (val res = xtreamClient.streamVodStreamsConditional(
                                    account = account,
                                    batchSize = 500,
                                    categoryMap = catMap,
                                    conditionalHeaders = hdr
                                ) { batch -> collected.addAll(batch) }) {
                                    is ConditionalFetchResult.NotModified -> {
                                        store.recordSuccess(account.id, type, nowMs, SyncOutcome.NOT_MODIFIED, "movies", res.headers)
                                        outcomes[type] = SyncOutcome.NOT_MODIFIED
                                    }
                                    is ConditionalFetchResult.Success -> {
                                        val diff = repository.syncMediaIncremental(
                                            accountId = account.id,
                                            type = "MOVIE",
                                            incoming = collected,
                                            pruneRemoved = collected.isNotEmpty()
                                        )
                                        if (diff.upsertedCount > 0 || diff.deletedCount > 0) {
                                            hadAnyCatalogChange = true
                                            updatedTypes.add(type)
                                        }
                                        store.recordSuccess(account.id, type, nowMs, SyncOutcome.SUCCESS, "movies", res.headers)
                                        outcomes[type] = SyncOutcome.SUCCESS
                                    }
                                    is ConditionalFetchResult.Failure -> {
                                        store.recordFailure(account.id, type, nowMs, res.error)
                                        outcomes[type] = SyncOutcome.FAILED
                                        failedTypes[type] = res.error
                                    }
                                }
                            }

                            SyncContentType.SERIES -> {
                                if (isManual) reportManualProgress("Syncing TV series...")
                                val hdr = if (decision.isFullSync) null else store.getConditionalHeaders(account.id, "series")
                                val collected = ArrayList<MediaItemEntity>()
                                val catMap = resolveCategoryMap(MediaType.SERIES)
                                totalNetworkRequests++
                                when (val res = xtreamClient.streamSeriesConditional(
                                    account = account,
                                    batchSize = 500,
                                    categoryMap = catMap,
                                    conditionalHeaders = hdr
                                ) { batch -> collected.addAll(batch) }) {
                                    is ConditionalFetchResult.NotModified -> {
                                        store.recordSuccess(account.id, type, nowMs, SyncOutcome.NOT_MODIFIED, "series", res.headers)
                                        outcomes[type] = SyncOutcome.NOT_MODIFIED
                                    }
                                    is ConditionalFetchResult.Success -> {
                                        val diff = repository.syncMediaIncremental(
                                            accountId = account.id,
                                            type = "SERIES",
                                            incoming = collected,
                                            pruneRemoved = collected.isNotEmpty()
                                        )
                                        if (diff.upsertedCount > 0 || diff.deletedCount > 0) {
                                            hadAnyCatalogChange = true
                                            updatedTypes.add(type)
                                        }
                                        store.recordSuccess(account.id, type, nowMs, SyncOutcome.SUCCESS, "series", res.headers)
                                        outcomes[type] = SyncOutcome.SUCCESS
                                    }
                                    is ConditionalFetchResult.Failure -> {
                                        store.recordFailure(account.id, type, nowMs, res.error)
                                        outcomes[type] = SyncOutcome.FAILED
                                        failedTypes[type] = res.error
                                    }
                                }
                            }

                            SyncContentType.EPG -> {
                                val channels = repository.getLiveChannelsForEpg(account.id, limit = 25)
                                if (channels.isEmpty()) {
                                    store.recordSuccess(account.id, type, nowMs, SyncOutcome.SUCCESS)
                                    outcomes[type] = SyncOutcome.SUCCESS
                                } else {
                                    if (isManual) reportManualProgress("Syncing EPG guide...")
                                    totalNetworkRequests += channels.size
                                    when (val res = xtreamClient.fetchShortEpgForChannels(account, channels)) {
                                        is ConditionalFetchResult.Success -> {
                                            val updatedCount = repository.updateLiveChannelEpgPlots(account.id, res.data)
                                            if (updatedCount > 0) {
                                                hadAnyCatalogChange = true
                                                updatedTypes.add(type)
                                            }
                                            store.recordSuccess(account.id, type, nowMs, SyncOutcome.SUCCESS)
                                            outcomes[type] = SyncOutcome.SUCCESS
                                        }
                                        is ConditionalFetchResult.NotModified -> {
                                            store.recordSuccess(account.id, type, nowMs, SyncOutcome.NOT_MODIFIED)
                                            outcomes[type] = SyncOutcome.NOT_MODIFIED
                                        }
                                        is ConditionalFetchResult.Failure -> {
                                            store.recordFailure(account.id, type, nowMs, res.error)
                                            outcomes[type] = SyncOutcome.FAILED
                                            failedTypes[type] = res.error
                                        }
                                    }
                                }
                            }

                            SyncContentType.TMDB_METADATA -> {
                                if (onTmdbMetadataSync == null) {
                                    outcomes[type] = SyncOutcome.SKIPPED_UNCHANGED
                                } else {
                                    val unenrichedMovies = repository.getUnenrichedMedia(account.id, "MOVIE", 1)
                                    val unenrichedSeries = repository.getUnenrichedMedia(account.id, "SERIES", 1)
                                    if (unenrichedMovies.isEmpty() && unenrichedSeries.isEmpty() && !isManual) {
                                        store.recordSuccess(account.id, type, nowMs, SyncOutcome.NOT_MODIFIED)
                                        outcomes[type] = SyncOutcome.NOT_MODIFIED
                                    } else {
                                        if (isManual) reportManualProgress("Updating TMDb metadata...")
                                        val ok = onTmdbMetadataSync(account)
                                        if (ok) {
                                            store.recordSuccess(account.id, type, nowMs, SyncOutcome.SUCCESS)
                                            outcomes[type] = SyncOutcome.SUCCESS
                                            updatedTypes.add(type)
                                        } else {
                                            store.recordFailure(account.id, type, nowMs, "TMDb metadata update failed")
                                            outcomes[type] = SyncOutcome.FAILED
                                            failedTypes[type] = "TMDb metadata update failed"
                                        }
                                    }
                                }
                            }

                            SyncContentType.FAVORITES_HISTORY -> {
                                if (onFavoritesHistorySync == null) {
                                    outcomes[type] = SyncOutcome.SKIPPED_UNCHANGED
                                } else {
                                    if (isManual) reportManualProgress("Syncing favorites & history...")
                                    val ok = onFavoritesHistorySync(account)
                                    if (ok) {
                                        store.clearFavoritesOrHistoryDirty(account.id)
                                        store.recordSuccess(account.id, type, nowMs, SyncOutcome.SUCCESS)
                                        outcomes[type] = SyncOutcome.SUCCESS
                                        updatedTypes.add(type)
                                    } else {
                                        store.recordFailure(account.id, type, nowMs, "Favorites & watch history sync failed")
                                        outcomes[type] = SyncOutcome.FAILED
                                        failedTypes[type] = "Favorites & watch history sync failed"
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        val msg = e.message ?: "Synchronization failed"
                        store.recordFailure(account.id, type, nowMs, msg)
                        outcomes[type] = SyncOutcome.FAILED
                        failedTypes[type] = msg
                    } finally {
                        releaseFlight(account.id, type)
                        refreshUiState(account.id, settings)
                    }
                }

                // Save credentials fingerprint once at least one catalog/account operation succeeded
                if (outcomes.values.any { it == SyncOutcome.SUCCESS || it == SyncOutcome.NOT_MODIFIED }) {
                    store.saveCredentialsFingerprint(account)
                }
            }

            SmartSyncRunReport(
                accountId = account.id,
                triggerReason = effectiveReason,
                outcomes = outcomes,
                updatedTypes = updatedTypes,
                failedTypes = failedTypes,
                totalNetworkRequests = totalNetworkRequests,
                hadAnyCatalogChange = hadAnyCatalogChange
            )
        } finally {
            if (isManual) {
                onManualProgress?.invoke(null)
                _uiState.value = _uiState.value.copy(
                    isManualSyncRunning = false,
                    activeManualMessage = null,
                    lastManualError = failedTypes.values.firstOrNull(),
                    lastManualCompletedMs = if (failedTypes.isEmpty()) clockMs() else _uiState.value.lastManualCompletedMs
                )
            } else {
                _uiState.value = _uiState.value.copy(isBackgroundSyncRunning = false)
            }
            refreshUiState(account.id, settings)
        }
    }

    private fun flightKey(accountId: String, type: SyncContentType): String = "$accountId:${type.name}"

    private fun tryAcquireFlight(accountId: String, type: SyncContentType): Boolean {
        return inFlightKeys.putIfAbsent(flightKey(accountId, type), true) == null
    }

    private fun releaseFlight(accountId: String, type: SyncContentType) {
        inFlightKeys.remove(flightKey(accountId, type))
    }

    fun isSyncInFlight(accountId: String, type: SyncContentType): Boolean =
        inFlightKeys.containsKey(flightKey(accountId, type))

    companion object {
        @Volatile
        private var INSTANCE: SmartSyncEngine? = null

        fun getInstance(context: Context): SmartSyncEngine {
            val appCtx = context.applicationContext
            val existing = INSTANCE
            if (existing != null && existing.context === appCtx) {
                return existing
            }
            return synchronized(this) {
                val current = INSTANCE
                if (current != null && current.context === appCtx) {
                    current
                } else {
                    SmartSyncEngine(appCtx).also { INSTANCE = it }
                }
            }
        }

        fun readDeviceConstraints(context: Context): SyncDeviceConstraints {
            val appCtx = context.applicationContext
            var isConnected = true
            var isWifi = true
            try {
                val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val activeNet = cm?.activeNetwork
                val caps = cm?.getNetworkCapabilities(activeNet)
                if (caps != null) {
                    isConnected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                } else {
                    isConnected = false
                    isWifi = false
                }
            } catch (_: Exception) {
                isConnected = true
                isWifi = true
            }

            var isBatteryLowOrPowerSave = false
            try {
                val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (pm?.isPowerSaveMode == true) {
                    isBatteryLowOrPowerSave = true
                }
                val batteryIntent = appCtx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (batteryIntent != null) {
                    val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                    if (!isCharging && level >= 0 && scale > 0) {
                        val pct = (level * 100) / scale
                        if (pct <= 20) {
                            isBatteryLowOrPowerSave = true
                        }
                    }
                }
            } catch (_: Exception) {
                isBatteryLowOrPowerSave = false
            }

            return SyncDeviceConstraints(
                isNetworkAvailable = isConnected,
                isWifiConnected = isWifi,
                isBatteryLowOrPowerSave = isBatteryLowOrPowerSave
            )
        }
    }
}
