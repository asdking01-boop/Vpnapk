package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.KrasApplication
import com.example.data.api.M3uPlaylistParser
import com.example.data.api.TmdbCastMember
import com.example.data.api.TmdbConnectionState
import com.example.data.api.TmdbDiagnosticCheck
import com.example.data.api.TmdbDiagnosticStatus
import com.example.data.api.TmdbDiagnosticsReport
import com.example.data.api.TmdbDirectClient
import com.example.data.api.TmdbErrorSanitizer
import com.example.data.api.TmdbHealthResult
import com.example.data.api.TmdbMediaMetadata
import com.example.data.api.TmdbMetadataMatcher
import com.example.data.api.TmdbPersonBiography
import com.example.data.api.TmdbProxyClient
import com.example.data.api.TmdbSearchCandidate
import com.example.data.api.XtreamClient
import com.example.data.local.ChannelNameNormalizer
import com.example.data.local.KrasRepository
import com.example.data.local.ManualTmdbBinding
import com.example.data.local.QualityDetector
import com.example.data.local.TmdbMetadataCacheStore
import com.example.data.local.TmdbSecureSessionStore
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.MediaItemEntity
import com.example.data.model.*
import com.example.data.sample.SampleData
import com.example.player.ExoPlayerWrapper
import com.example.player.KrasMediaPlaybackService
import com.example.player.PlaybackNotificationMetadata
import com.example.sound.CinematicSoundPlayer
import com.example.sync.SmartSyncEngine
import com.example.sync.SmartSyncWorker
import com.example.sync.SyncContentType
import com.example.sync.SyncTriggerReason
import com.example.updater.AppUpdateUiState
import com.example.updater.AppUpdater
import com.example.updater.ReleaseCheckResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class KrasViewModel(application: Application) : AndroidViewModel(application) {

    private val smartSyncEngine = SmartSyncEngine.getInstance(application)
    private val repository = smartSyncEngine.repository
    private val tmdbSessionStore = TmdbSecureSessionStore(application)
    private val tmdbCacheStore = TmdbMetadataCacheStore(application)
    private val apiClient = XtreamClient()
    private val tmdbProxyClient = TmdbProxyClient()
    private val tmdbDirectClient = TmdbDirectClient()
    private val appUpdater = AppUpdater()
    val playerWrapper = ExoPlayerWrapper(application)

    // Centralized Smart Sync Engine UI state (timestamps, next scheduled sync, manual progress)
    val smartSyncState = smartSyncEngine.uiState

    // M3U parsed series details cache (seriesId -> SeriesDetails), shared with SmartSyncEngine
    private val m3uSeriesDetailsMap get() = smartSyncEngine.m3uSeriesDetailsCache

    // TMDb Proxy Connection & Health State (internal metadata enrichment health)
    private val _tmdbHealthState = MutableStateFlow(
        TmdbHealthResult(
            state = TmdbConnectionState.NOT_CONFIGURED,
            message = ""
        )
    )
    val tmdbHealthState = _tmdbHealthState.asStateFlow()

    private val _isTmdbVerifying = MutableStateFlow(false)
    val isTmdbVerifying = _isTmdbVerifying.asStateFlow()

    // TMDb Account & Two-Way Sync UI State (never exposes passwords or logs credentials)
    private val _tmdbAccountState = MutableStateFlow(TmdbAccountUiState())
    val tmdbAccountState = _tmdbAccountState.asStateFlow()

    // Rich TMDb metadata (cast with actor photos, official trailer key, similar items) for Details screens
    private val _selectedMovieRichMetadata = MutableStateFlow<TmdbMediaMetadata?>(null)
    val selectedMovieRichMetadata = _selectedMovieRichMetadata.asStateFlow()

    private val _selectedSeriesRichMetadata = MutableStateFlow<TmdbMediaMetadata?>(null)
    val selectedSeriesRichMetadata = _selectedSeriesRichMetadata.asStateFlow()

    // Localized Actor Biography modal state (ar-SA with en-US fallback, preserves original actor name)
    private val _selectedActorBiography = MutableStateFlow<TmdbPersonBiography?>(null)
    val selectedActorBiography = _selectedActorBiography.asStateFlow()

    private val _isActorBioLoading = MutableStateFlow(false)
    val isActorBioLoading = _isActorBioLoading.asStateFlow()

    private var actorBioJob: Job? = null
    private val onDemandEnrichInFlight = ConcurrentHashMap.newKeySet<String>()

    // Manual TMDb Match Picker state
    private val _manualMatchCandidates = MutableStateFlow<List<TmdbSearchCandidate>>(emptyList())
    val manualMatchCandidates = _manualMatchCandidates.asStateFlow()

    private val _isManualMatchSearching = MutableStateFlow(false)
    val isManualMatchSearching = _isManualMatchSearching.asStateFlow()

    private var manualMatchJob: Job? = null
    private var diagnosticsJob: Job? = null

    // Settings -> TMDb -> Connection Diagnostics state
    private val _tmdbDiagnosticsReport = MutableStateFlow(
        TmdbDiagnosticsReport(
            lastSuccessfulRequestMs = tmdbCacheStore.getLastSuccessfulRequestMs(),
            lastErrorMessage = tmdbCacheStore.getLastSanitizedError(),
            cachedMetadataCount = tmdbCacheStore.getCachedEntryCount(),
            manualMatchCount = tmdbCacheStore.getManualMatchCount()
        )
    )
    val tmdbDiagnosticsReport = _tmdbDiagnosticsReport.asStateFlow()

    // Accounts
    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts = _accounts.asStateFlow()

    private val _activeAccount = MutableStateFlow<Account?>(null)
    val activeAccount = _activeAccount.asStateFlow()

    // Bounded UI Data lists (held in memory in small pages, never full catalog)
    private val _liveCategories = MutableStateFlow<List<Category>>(emptyList())
    val liveCategories = _liveCategories.asStateFlow()

    private val _liveStreams = MutableStateFlow<List<MediaItem>>(emptyList())
    val liveStreams = _liveStreams.asStateFlow()
    val totalLiveCount = MutableStateFlow(0)

    private val _movieCategories = MutableStateFlow<List<Category>>(emptyList())
    val movieCategories = _movieCategories.asStateFlow()

    private val _movies = MutableStateFlow<List<MediaItem>>(emptyList())
    val movies = _movies.asStateFlow()
    val totalMoviesCount = MutableStateFlow(0)

    // Feature 1: Latest 20 Movies in Every Category (sorted by server date_added / first-seen, newest first)
    private val _latestMoviesForCategory = MutableStateFlow<List<MediaItem>>(emptyList())
    val latestMoviesForCategory = _latestMoviesForCategory.asStateFlow()

    private val _latestMoviesByCategoryMap = MutableStateFlow<Map<String, List<MediaItem>>>(emptyMap())
    val latestMoviesByCategoryMap = _latestMoviesByCategoryMap.asStateFlow()

    private val _availableMovieYears = MutableStateFlow<List<String>>(emptyList())
    val availableMovieYears = _availableMovieYears.asStateFlow()

    private val _seriesCategories = MutableStateFlow<List<Category>>(emptyList())
    val seriesCategories = _seriesCategories.asStateFlow()

    private val _series = MutableStateFlow<List<MediaItem>>(emptyList())
    val series = _series.asStateFlow()
    val totalSeriesCount = MutableStateFlow(0)

    // Feature 2: Latest 20 Series in Every Category (sorted by server date_added / first-seen, newest first)
    private val _latestSeriesForCategory = MutableStateFlow<List<MediaItem>>(emptyList())
    val latestSeriesForCategory = _latestSeriesForCategory.asStateFlow()

    private val _latestSeriesByCategoryMap = MutableStateFlow<Map<String, List<MediaItem>>>(emptyMap())
    val latestSeriesByCategoryMap = _latestSeriesByCategoryMap.asStateFlow()

    private val _availableSeriesYears = MutableStateFlow<List<String>>(emptyList())
    val availableSeriesYears = _availableSeriesYears.asStateFlow()

    // Room-backed global search results
    private val _searchResults = MutableStateFlow<List<MediaItem>>(emptyList())
    val searchResults = _searchResults.asStateFlow()
    private var currentSearchQuery = ""

    // Active Movie & Series Details (loaded on demand with IPTV-first + TMDb fallback & caching)
    private val _selectedMovieForDetails = MutableStateFlow<MediaItem?>(null)
    val selectedMovieForDetails = _selectedMovieForDetails.asStateFlow()

    private val _selectedSeriesForDetails = MutableStateFlow<MediaItem?>(null)
    val selectedSeriesForDetails = _selectedSeriesForDetails.asStateFlow()

    private val _isMovieDetailsLoading = MutableStateFlow(false)
    val isMovieDetailsLoading = _isMovieDetailsLoading.asStateFlow()

    private val _currentSeriesDetails = MutableStateFlow<SeriesDetails?>(null)
    val currentSeriesDetails = _currentSeriesDetails.asStateFlow()

    private val _isSeriesDetailsLoading = MutableStateFlow(false)
    val isSeriesDetailsLoading = _isSeriesDetailsLoading.asStateFlow()

    // In-memory language-aware caches so reopening details screens loads immediately
    private val enrichedMediaDetailsCache = ConcurrentHashMap<String, MediaItem>()
    private val enrichedSeriesDetailsCache = ConcurrentHashMap<String, SeriesDetails>()
    private var movieDetailsJob: Job? = null
    private var seriesDetailsJob: Job? = null

    // Favorites & History & Downloads
    private val _favorites = MutableStateFlow<List<FavoriteItem>>(emptyList())
    val favorites = _favorites.asStateFlow()

    private val _watchHistory = MutableStateFlow<List<WatchProgress>>(emptyList())
    val watchHistory = _watchHistory.asStateFlow()

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads = _downloads.asStateFlow()

    // Settings
    private val _userSettings = MutableStateFlow(UserSettings())
    val userSettings = _userSettings.asStateFlow()

    // UI States
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    // First sync progress indicator (e.g. "Loading content 40%...")
    private val _syncProgress = MutableStateFlow<String?>(null)
    val syncProgress = _syncProgress.asStateFlow()

    // Active Playback State (Movies / Series Fullscreen Player)
    private val _nowPlayingItem = MutableStateFlow<MediaItem?>(null)
    val nowPlayingItem = _nowPlayingItem.asStateFlow()

    private val _nowPlayingEpisode = MutableStateFlow<Episode?>(null)
    val nowPlayingEpisode = _nowPlayingEpisode.asStateFlow()

    // Feature 3: Persistent Live TV Player State (keeps channel list visible while switching channels)
    private val _activeLiveChannel = MutableStateFlow<MediaItem?>(null)
    val activeLiveChannel = _activeLiveChannel.asStateFlow()

    private val _isLiveFullscreen = MutableStateFlow(false)
    val isLiveFullscreen = _isLiveFullscreen.asStateFlow()

    private val _liveFullscreenOrientation = MutableStateFlow(FullscreenOrientation.LANDSCAPE)
    val liveFullscreenOrientation = _liveFullscreenOrientation.asStateFlow()

    private val _isLiveMiniPlayerActive = MutableStateFlow(false)
    val isLiveMiniPlayerActive = _isLiveMiniPlayerActive.asStateFlow()

    private val _recentLiveChannels = MutableStateFlow<List<MediaItem>>(emptyList())
    val recentLiveChannels = _recentLiveChannels.asStateFlow()

    private val _lastWatchedLiveChannel = MutableStateFlow<MediaItem?>(null)
    val lastWatchedLiveChannel = _lastWatchedLiveChannel.asStateFlow()

    // In-App Updater State
    private val _updateState = MutableStateFlow(AppUpdateUiState())
    val updateState = _updateState.asStateFlow()

    private var progressTrackingJob: Job? = null
    private var syncJob: Job? = null
    private var updateCheckJob: Job? = null
    private var updateDownloadJob: Job? = null

    // Pagination filter states
    private var moviePage = 0
    private var movieCatFilter = "all"
    private var movieQueryFilter = ""
    private var movieRatingFilter = 0.0
    private var movieYearFilter = "All"
    private var movieQualityFilter = "All"
    private var movieSortByQuality = false
    private var movieSortOrder = MediaSortOrder.RECENTLY_ADDED
    private var isMoviesLoadingMore = false

    private var seriesPage = 0
    private var seriesCatFilter = "all"
    private var seriesQueryFilter = ""
    private var seriesRatingFilter = 0.0
    private var seriesYearFilter = "All"
    private var seriesQualityFilter = "All"
    private var seriesSortByQuality = false
    private var seriesSortOrder = MediaSortOrder.RECENTLY_ADDED
    private var isSeriesLoadingMore = false

    private var livePage = 0
    private var liveCatFilter = "all"
    private var liveQueryFilter = ""
    private var liveQualityFilter = "All"
    private var liveSortByQuality = false
    private var isLiveLoadingMore = false

    // Tracks which account's current filter slice is already loaded in memory to avoid redundant
    // Room re-queries and scroll resets when switching between tabs or recomposing.
    private var moviesLoadedAccountId: String? = null
    private var seriesLoadedAccountId: String? = null
    private var liveLoadedAccountId: String? = null

    private val pageSize = 40

    init {
        playerWrapper.onRealQualityDetected = { realQuality ->
            onRealPlaybackQualityDetected(realQuality)
        }
        loadInitialData()
    }

    fun getInitialStartupSettings(): UserSettings {
        return repository.getUserSettings()
    }

    fun playIntroSoundIfEnabled() {
        if (repository.getUserSettings().playIntroSound) {
            viewModelScope.launch {
                try {
                    CinematicSoundPlayer.playCinematicIntro()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun playZoomThroughBassIfEnabled() {
        if (repository.getUserSettings().playZoomThroughBassSound) {
            viewModelScope.launch {
                try {
                    CinematicSoundPlayer.playZoomThroughBass()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun stopIntroSound() {
        CinematicSoundPlayer.stop()
    }

    private fun activeKrasUserId(): String {
        return _activeAccount.value?.id?.takeIf { it.isNotBlank() }
            ?: repository.getActiveAccount().id
    }

    private fun refreshActiveUserTmdbAndFavoritesState(krasUserId: String) {
        val currentOrder = _userSettings.value.liveChannelOrder
        _favorites.value = repository.getFavorites(currentOrder, krasUserId)
        _tmdbAccountState.value = buildInitialTmdbAccountUiState(krasUserId)
    }

    private fun hasPendingFavoritesOrHistorySync(krasUserId: String): Boolean {
        val state = _tmdbAccountState.value
        if (!state.isConnected || !_userSettings.value.tmdbAutoSyncEnabled) return false
        return state.pendingOfflineChangesCount > 0 || smartSyncEngine.store.isFavoritesOrHistoryDirty(krasUserId)
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            try {
                val (settings, loadedAccounts, active) = withContext(Dispatchers.IO) {
                    val s = repository.getUserSettings()
                    val accs = repository.getAccounts()
                    val act = repository.getActiveAccount()
                    Triple(s, accs, act)
                }
                _userSettings.value = settings
                KrasMediaPlaybackService.backgroundPlaybackEnabled = settings.backgroundPlayback
                val currentOrder = settings.liveChannelOrder

                val (favs, history, dlList) = withContext(Dispatchers.IO) {
                    val f = repository.getFavorites(currentOrder, active.id)
                    val h = repository.getWatchHistory()
                    val d = repository.getDownloads()
                    Triple(f, h, d)
                }

                _accounts.value = loadedAccounts
                _activeAccount.value = active
                _favorites.value = favs
                _watchHistory.value = history
                _downloads.value = dlList
                _tmdbAccountState.value = withContext(Dispatchers.IO) {
                    buildInitialTmdbAccountUiState(active.id)
                }
                _recentLiveChannels.value = withContext(Dispatchers.IO) { repository.getRecentLiveChannels() }
                _lastWatchedLiveChannel.value = withContext(Dispatchers.IO) { repository.getLastWatchedLiveChannel() }

                // Initialize player with buffer setting
                playerWrapper.initPlayer(settings.bufferSize)

                // 1. Instant Startup: Load all existing cached categories & pages from Room immediately
                loadInitialPagesFromRoom(active)

                // 2. Refresh Smart Sync UI timestamps & schedule unique periodic WorkManager job
                smartSyncEngine.refreshUiState(active.id, settings)
                SmartSyncWorker.scheduleOrUpdate(getApplication(), settings)

                // 3. Silent Smart Sync check: ONLY syncs content types that are missing locally or past
                //    their configured refresh interval (0 network requests if everything is fresh!).
                runSmartSync(
                    account = active,
                    requestedTypes = SyncContentType.entries.toList(),
                    triggerReason = SyncTriggerReason.APP_STARTUP
                )

                // Clean up old downloaded APKs after update & run automatic 6-hour update check
                checkForUpdatesOnLaunchIfNeeded()
            } catch (oom: OutOfMemoryError) {
                handleOom(oom, "Initial loading out of memory")
            } catch (e: Exception) {
                e.printStackTrace()
                loadDemoDataFallback()
            }
        }
    }

    private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

    private suspend fun loadInitialPagesFromRoom(account: Account) = withContext(Dispatchers.IO) {
        try {
            val order = _userSettings.value.liveChannelOrder
            val movieCats = repository.getCategories(account.id, MediaType.MOVIE)
            val seriesCats = repository.getCategories(account.id, MediaType.SERIES)
            val liveCats = repository.getCategories(account.id, MediaType.LIVE)

            val mLimit = (moviePage + 1) * pageSize
            val sLimit = (seriesPage + 1) * pageSize
            val lLimit = (livePage + 1) * pageSize

            val initialMovies = repository.queryMediaPaged(
                accountId = account.id,
                type = "MOVIE",
                categoryId = movieCatFilter,
                query = movieQueryFilter,
                minRating = movieRatingFilter,
                year = movieYearFilter,
                limit = mLimit,
                offset = 0,
                qualityFilter = movieQualityFilter,
                sortByQuality = movieSortByQuality,
                sortOrder = movieSortOrder
            )
            val initialSeries = repository.queryMediaPaged(
                accountId = account.id,
                type = "SERIES",
                categoryId = seriesCatFilter,
                query = seriesQueryFilter,
                minRating = seriesRatingFilter,
                year = seriesYearFilter,
                limit = sLimit,
                offset = 0,
                qualityFilter = seriesQualityFilter,
                sortByQuality = seriesSortByQuality,
                sortOrder = seriesSortOrder
            )
            val initialLive = repository.queryMediaPaged(
                accountId = account.id,
                type = "LIVE",
                categoryId = liveCatFilter,
                query = liveQueryFilter,
                minRating = 0.0,
                year = "All",
                limit = lLimit,
                offset = 0,
                liveChannelOrder = order,
                qualityFilter = liveQualityFilter,
                sortByQuality = liveSortByQuality
            )

            val mCount = repository.countMedia(account.id, "MOVIE", movieCatFilter, movieQueryFilter, movieRatingFilter, movieYearFilter, movieQualityFilter)
            val sCount = repository.countMedia(account.id, "SERIES", seriesCatFilter, seriesQueryFilter, seriesRatingFilter, seriesYearFilter, seriesQualityFilter)
            val lCount = repository.countMedia(account.id, "LIVE", liveCatFilter, liveQueryFilter, 0.0, "All", liveQualityFilter)

            if (initialMovies.isNotEmpty() || initialSeries.isNotEmpty() || initialLive.isNotEmpty() || mCount > 0 || sCount > 0 || lCount > 0) {
                _movieCategories.value = movieCats
                _seriesCategories.value = seriesCats
                _liveCategories.value = liveCats

                _movies.value = initialMovies
                totalMoviesCount.value = mCount

                _series.value = initialSeries
                totalSeriesCount.value = sCount

                _liveStreams.value = initialLive
                totalLiveCount.value = lCount

                // Feature 1 & Feature 2: Load Latest 20 Movies & Series per category + Available Release Years
                val latestMoviesCat = repository.getLatestByCategory(account.id, "MOVIE", movieCatFilter, 20)
                val latestSeriesCat = repository.getLatestByCategory(account.id, "SERIES", seriesCatFilter, 20)
                _latestMoviesForCategory.value = latestMoviesCat
                _latestSeriesForCategory.value = latestSeriesCat
                _latestMoviesByCategoryMap.value = repository.getLatestForAllCategories(account.id, "MOVIE", movieCats, 20)
                _latestSeriesByCategoryMap.value = repository.getLatestForAllCategories(account.id, "SERIES", seriesCats, 20)
                _availableMovieYears.value = repository.getAvailableYears(account.id, "MOVIE", movieCatFilter)
                _availableSeriesYears.value = repository.getAvailableYears(account.id, "SERIES", seriesCatFilter)

                _favorites.value = repository.getFavorites(order, account.id)
                moviesLoadedAccountId = account.id
                seriesLoadedAccountId = account.id
                liveLoadedAccountId = account.id

                _isLoading.value = false
            } else if (account.isDemo) {
                loadDemoDataFallback()
            } else {
                moviesLoadedAccountId = null
                seriesLoadedAccountId = null
                liveLoadedAccountId = null
                _isLoading.value = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadDemoDataFallback() {
        val sortedLive = ChannelNameNormalizer.sortMediaItems(
            SampleData.sampleLiveStreams,
            _userSettings.value.liveChannelOrder
        )
        _liveCategories.value = SampleData.liveCategories
        _liveStreams.value = sortedLive
        totalLiveCount.value = sortedLive.size

        _movieCategories.value = SampleData.movieCategories
        val demoMoviesComparator = compareByDescending<MediaItem> { it.addedTimestamp }
            .thenByDescending { it.streamId }
        _movies.value = SampleData.sampleMovies.sortedWith(demoMoviesComparator)
        totalMoviesCount.value = SampleData.sampleMovies.size

        val demoLatestMoviesMap = LinkedHashMap<String, List<MediaItem>>()
        demoLatestMoviesMap["all"] = SampleData.sampleMovies.sortedWith(demoMoviesComparator).distinctBy { it.streamId }.take(20)
        for (cat in SampleData.movieCategories) {
            demoLatestMoviesMap[cat.categoryId] = SampleData.sampleMovies
                .filter { cat.categoryId == "vod_all" || it.categoryId == cat.categoryId }
                .sortedWith(demoMoviesComparator)
                .distinctBy { it.streamId }
                .take(20)
        }
        _latestMoviesByCategoryMap.value = demoLatestMoviesMap
        _latestMoviesForCategory.value = demoLatestMoviesMap[movieCatFilter] ?: demoLatestMoviesMap["all"].orEmpty()
        _availableMovieYears.value = SampleData.sampleMovies
            .mapNotNull { it.year.toIntOrNull() }
            .filter { it in 1900..2100 }
            .distinct()
            .sortedDescending()
            .map { it.toString() }

        _seriesCategories.value = SampleData.seriesCategories
        _series.value = SampleData.sampleSeries.sortedWith(demoMoviesComparator)
        totalSeriesCount.value = SampleData.sampleSeries.size

        val demoLatestSeriesMap = LinkedHashMap<String, List<MediaItem>>()
        demoLatestSeriesMap["all"] = SampleData.sampleSeries.sortedWith(demoMoviesComparator).distinctBy { it.streamId }.take(20)
        for (cat in SampleData.seriesCategories) {
            demoLatestSeriesMap[cat.categoryId] = SampleData.sampleSeries
                .filter { cat.categoryId == "series_all" || it.categoryId == cat.categoryId }
                .sortedWith(demoMoviesComparator)
                .distinctBy { it.streamId }
                .take(20)
        }
        _latestSeriesByCategoryMap.value = demoLatestSeriesMap
        _latestSeriesForCategory.value = demoLatestSeriesMap[seriesCatFilter] ?: demoLatestSeriesMap["all"].orEmpty()
        _availableSeriesYears.value = SampleData.sampleSeries
            .mapNotNull { it.year.toIntOrNull() }
            .filter { it in 1900..2100 }
            .distinct()
            .sortedDescending()
            .map { it.toString() }

        _isLoading.value = false
    }

    fun switchAccount(accountId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.setActiveAccount(accountId)
                }
                _accounts.value = withContext(Dispatchers.IO) { repository.getAccounts() }
                val active = withContext(Dispatchers.IO) { repository.getActiveAccount() }
                _activeAccount.value = active
                withContext(Dispatchers.IO) {
                    refreshActiveUserTmdbAndFavoritesState(active.id)
                }
                resetPaginationStates()
                loadInitialPagesFromRoom(active)
                smartSyncEngine.refreshUiState(active.id, _userSettings.value)
                // Only syncs stale or missing content types for this account (no forced redownload if fresh)
                runSmartSync(
                    account = active,
                    requestedTypes = SyncContentType.entries.toList(),
                    triggerReason = SyncTriggerReason.APP_STARTUP
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun login(serverUrl: String, user: String, pass: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val result = apiClient.authenticate(serverUrl, user, pass)
                result.onSuccess { account ->
                    withContext(Dispatchers.IO) {
                        repository.addAccount(account)
                    }
                    _accounts.value = withContext(Dispatchers.IO) { repository.getAccounts() }
                    _activeAccount.value = account
                    withContext(Dispatchers.IO) {
                        refreshActiveUserTmdbAndFavoritesState(account.id)
                    }
                    resetPaginationStates()
                    smartSyncEngine.refreshUiState(account.id, _userSettings.value)
                    // Newly added account legitimately triggers initial full catalog sync
                    runSmartSync(
                        account = account,
                        requestedTypes = SyncContentType.entries.toList(),
                        triggerReason = SyncTriggerReason.NEW_ACCOUNT
                    )
                    _isLoading.value = false
                    onResult(true, null)
                }.onFailure { error ->
                    _isLoading.value = false
                    _errorMessage.value = error.message
                    onResult(false, error.message)
                }
            } catch (e: Exception) {
                _isLoading.value = false
                val msg = e.message ?: "Authentication failed"
                _errorMessage.value = msg
                onResult(false, msg)
            }
        }
    }

    fun loginDemoMode() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.addAccount(SampleData.demoAccount)
                }
                _accounts.value = withContext(Dispatchers.IO) { repository.getAccounts() }
                _activeAccount.value = SampleData.demoAccount
                withContext(Dispatchers.IO) {
                    refreshActiveUserTmdbAndFavoritesState(SampleData.demoAccount.id)
                }
                resetPaginationStates()
                loadInitialPagesFromRoom(SampleData.demoAccount)
                smartSyncEngine.refreshUiState(SampleData.demoAccount.id, _userSettings.value)
                runSmartSync(
                    account = SampleData.demoAccount,
                    requestedTypes = SyncContentType.entries.toList(),
                    triggerReason = SyncTriggerReason.APP_STARTUP
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeAccount(accountId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val removedSession = tmdbSessionStore.getSession(accountId)
                    tmdbSessionStore.clearSession(accountId)
                    repository.clearTmdbSyncedDataOnDisconnect(accountId)
                    if (removedSession != null && removedSession.sessionId.isNotBlank()) {
                        tmdbProxyClient.disconnectSession(canonicalTmdbProxyUrl(), removedSession.sessionId, accountId)
                    }
                    repository.removeAccount(accountId)
                    repository.mediaDao.deleteAllByAccount(accountId)
                    repository.mediaDao.deleteCategoriesByAccount(accountId)
                    smartSyncEngine.store.clearAccount(accountId)
                }
                _accounts.value = withContext(Dispatchers.IO) { repository.getAccounts() }
                val active = withContext(Dispatchers.IO) { repository.getActiveAccount() }
                _activeAccount.value = active
                withContext(Dispatchers.IO) {
                    refreshActiveUserTmdbAndFavoritesState(active.id)
                }
                resetPaginationStates()
                loadInitialPagesFromRoom(active)
                smartSyncEngine.refreshUiState(active.id, _userSettings.value)
                runSmartSync(
                    account = active,
                    requestedTypes = SyncContentType.entries.toList(),
                    triggerReason = SyncTriggerReason.APP_STARTUP
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Explicit user pull-to-refresh / Refresh button action: triggers a manual incremental sync
     * through SmartSyncEngine (single unified request path, no duplicate subscription fetch).
     */
    fun refreshContent() {
        syncNowManual()
    }

    /**
     * Settings → Synchronization → "Sync Now":
     * Manually triggers incremental smart sync with ETag / Last-Modified headers and visible progress.
     */
    fun syncNowManual() {
        val active = _activeAccount.value ?: return
        runSmartSync(
            account = active,
            requestedTypes = SyncContentType.entries.toList(),
            triggerReason = SyncTriggerReason.MANUAL_SYNC_NOW
        )
    }

    /**
     * Settings → Synchronization → "Full Refresh":
     * Explicitly clears conditional headers and reloads the entire active account catalog from scratch,
     * preserving accounts, favorites, watch history, and settings.
     */
    fun fullRefreshManual() {
        val active = _activeAccount.value ?: return
        runSmartSync(
            account = active,
            requestedTypes = SyncContentType.entries.toList(),
            triggerReason = SyncTriggerReason.MANUAL_FULL_REFRESH
        )
    }

    /**
     * Settings → Synchronization → Individual Content Sync:
     * Manually synchronizes a single content type (Movies, Series, Live TV, Categories, EPG, TMDb,
     * Account Status, or Favorites & Watch History).
     */
    fun syncSingleContentTypeManual(type: SyncContentType) {
        val active = _activeAccount.value ?: return
        runSmartSync(
            account = active,
            requestedTypes = listOf(type),
            triggerReason = SyncTriggerReason.MANUAL_SINGLE_TYPE
        )
    }

    /**
     * Refreshes subscription status & expiration date through SmartSyncEngine.
     * Automatic checks obey the 24-hour interval; manual taps on the Refresh button run immediately.
     */
    fun refreshSubscriptionInfo(
        account: Account? = _activeAccount.value,
        isManual: Boolean = true
    ) {
        val target = account ?: return
        if (target.isDemo || target.serverUrl.isBlank() || target.username.isBlank()) return
        runSmartSync(
            account = target,
            requestedTypes = listOf(SyncContentType.ACCOUNT_STATUS),
            triggerReason = if (isManual) SyncTriggerReason.MANUAL_SINGLE_TYPE else SyncTriggerReason.APP_STARTUP
        )
    }

    private fun resetPaginationStates() {
        moviePage = 0
        movieCatFilter = "all"
        movieQueryFilter = ""
        movieRatingFilter = 0.0
        movieYearFilter = "All"
        movieQualityFilter = "All"
        movieSortByQuality = false
        movieSortOrder = MediaSortOrder.RECENTLY_ADDED
        moviesLoadedAccountId = null

        seriesPage = 0
        seriesCatFilter = "all"
        seriesQueryFilter = ""
        seriesRatingFilter = 0.0
        seriesYearFilter = "All"
        seriesQualityFilter = "All"
        seriesSortByQuality = false
        seriesSortOrder = MediaSortOrder.RECENTLY_ADDED
        seriesLoadedAccountId = null

        livePage = 0
        liveCatFilter = "all"
        liveQueryFilter = ""
        liveQualityFilter = "All"
        liveSortByQuality = false
        liveLoadedAccountId = null
    }

    /**
     * Centralized synchronization entry point backed by [SmartSyncEngine].
     * - Automatic triggers run 100% silently (NEVER populate [_syncProgress]).
     * - Manual triggers show real-time progress in [_syncProgress] and [smartSyncState],
     *   and surface actionable errors if a manual sync fails.
     * - Preserves existing Room cache & UI scroll position whenever nothing changed.
     */
    private fun runSmartSync(
        account: Account,
        requestedTypes: List<SyncContentType>,
        triggerReason: SyncTriggerReason
    ) {
        val isManual = triggerReason.isManual
        // Manual sync cancels any prior ViewModel sync job; automatic sync relies on SmartSyncEngine single-flight
        if (isManual) {
            syncJob?.cancel()
        }
        val launchedJob = viewModelScope.launch(Dispatchers.IO) {
            if (isManual) {
                _isRefreshing.value = true
                _errorMessage.value = null
            }
            try {
                val settings = _userSettings.value
                val report = smartSyncEngine.syncAccount(
                    account = account,
                    requestedTypes = requestedTypes,
                    triggerReason = triggerReason,
                    settings = settings,
                    hasPendingFavoritesOrHistoryChanges = hasPendingFavoritesOrHistorySync(account.id),
                    onManualProgress = { msg ->
                        // Strictly ONLY show floating sync progress during user-initiated manual syncs
                        if (isManual) {
                            _syncProgress.value = msg
                        }
                    },
                    onAccountSubscriptionUpdated = { refreshed ->
                        val allAccounts = repository.getAccounts()
                        val currentActive = repository.getActiveAccount()
                        _accounts.value = allAccounts
                        if (_activeAccount.value?.id == refreshed.id) {
                            _activeAccount.value = currentActive
                        }
                    },
                    onTmdbMetadataSync = { targetAcc ->
                        enrichCatalogWithTmdb(targetAcc)
                    },
                    onFavoritesHistorySync = { targetAcc ->
                        if (_tmdbAccountState.value.isConnected && _userSettings.value.tmdbAutoSyncEnabled) {
                            syncTmdbAccountNowSyncResult(isAuto = !isManual, targetKrasUserId = targetAcc.id)
                        } else {
                            true
                        }
                    }
                )

                // Reload Room pages ONLY if catalog data actually changed (prevents UI flicker & preserves scroll position)
                if (report.hadAnyCatalogChange && _activeAccount.value?.id == account.id) {
                    moviesLoadedAccountId = null
                    seriesLoadedAccountId = null
                    liveLoadedAccountId = null
                    loadInitialPagesFromRoom(account)
                }

                // Surface actionable error message ONLY for manual sync failures (never spam during silent background sync)
                if (isManual && report.failedTypes.isNotEmpty()) {
                    val firstErr = report.failedTypes.entries.first()
                    _errorMessage.value = "Sync failed (${firstErr.key.labelEn}): ${firstErr.value}"
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (oom: OutOfMemoryError) {
                handleOom(oom, "Out of memory during smart catalog synchronization")
                loadInitialPagesFromRoom(account)
            } catch (e: Exception) {
                e.printStackTrace()
                if (isManual) {
                    _errorMessage.value = "Sync error: ${e.message ?: "Unable to connect"}"
                }
            } finally {
                if (isManual) {
                    _isRefreshing.value = false
                    _syncProgress.value = null
                }
                _isLoading.value = false
            }
        }
        if (isManual) {
            syncJob = launchedJob
        }
    }

    private val _navigateToSettingsFromAuth = MutableStateFlow(false)
    val navigateToSettingsFromAuth = _navigateToSettingsFromAuth.asStateFlow()

    fun consumeNavigateToSettingsFromAuth() {
        _navigateToSettingsFromAuth.value = false
    }

    private fun buildInitialTmdbAccountUiState(krasUserId: String = repository.getActiveAccount().id): TmdbAccountUiState {
        val settings = repository.getUserSettings()
        val stored = tmdbSessionStore.getSession(krasUserId)
        val savedApiKey = tmdbSessionStore.getSavedApiKey()
        val pendingCount = repository.getPendingTmdbMutations(krasUserId).size
        val localFavCount = repository.getLocalTmdbSyncFavorites(krasUserId).size
        val localWatchlistCount = repository.getWatchlist(krasUserId).size
        val localRatingsCount = repository.getUserRatings(krasUserId).size
        if (stored != null && stored.sessionId.isNotBlank() && stored.username.isNotBlank()) {
            return TmdbAccountUiState(
                krasAccountId = krasUserId,
                authStatus = TmdbAuthStatus.CONNECTED,
                syncStatus = TmdbSyncStatus.IDLE,
                accountId = stored.accountId,
                username = stored.username,
                displayName = stored.displayName.ifBlank { stored.username },
                avatarUrl = stored.avatarUrl,
                autoSyncEnabled = settings.tmdbAutoSyncEnabled,
                lastSyncTimestampMs = stored.lastSyncTimestampMs,
                syncedFavoritesCount = stored.syncedFavoritesCount.coerceAtLeast(localFavCount),
                syncedWatchlistCount = stored.syncedWatchlistCount.coerceAtLeast(localWatchlistCount),
                syncedRatingsCount = stored.syncedRatingsCount.coerceAtLeast(localRatingsCount),
                pendingOfflineChangesCount = pendingCount,
                savedApiKey = savedApiKey.ifBlank { stored.apiKey }
            )
        }
        return TmdbAccountUiState(
            krasAccountId = krasUserId,
            authStatus = TmdbAuthStatus.DISCONNECTED,
            autoSyncEnabled = settings.tmdbAutoSyncEnabled,
            syncedFavoritesCount = localFavCount,
            syncedWatchlistCount = localWatchlistCount,
            syncedRatingsCount = localRatingsCount,
            pendingOfflineChangesCount = pendingCount,
            savedApiKey = savedApiKey
        )
    }

    /**
     * Directly logs into TMDb using the official 4-step HTTPS authentication workflow
     * without any browser redirect, Cloudflare Worker, or backend proxy.
     * Password is used in-memory for the single request and NEVER stored or logged.
     */
    fun loginToTmdbAccount(
        apiKeyInput: String,
        usernameInput: String,
        passwordInput: CharSequence,
        onResult: ((Boolean, String?) -> Unit)? = null
    ) {
        val cleanApiKey = apiKeyInput.trim()
        val cleanUsername = usernameInput.trim()

        if (cleanApiKey.isEmpty()) {
            _tmdbAccountState.value = _tmdbAccountState.value.copy(
                statusMessage = "Please enter your TMDb API Key (v3 auth).",
                isError = true
            )
            onResult?.invoke(false, "Please enter your TMDb API Key (v3 auth).")
            return
        }

        if (cleanUsername.isEmpty()) {
            _tmdbAccountState.value = _tmdbAccountState.value.copy(
                statusMessage = "Please enter your TMDb username.",
                isError = true
            )
            onResult?.invoke(false, "Please enter your TMDb username.")
            return
        }

        if (passwordInput.isEmpty()) {
            _tmdbAccountState.value = _tmdbAccountState.value.copy(
                statusMessage = "Please enter your TMDb password.",
                isError = true
            )
            onResult?.invoke(false, "Please enter your TMDb password.")
            return
        }

        val krasUserId = activeKrasUserId()
        _tmdbAccountState.value = _tmdbAccountState.value.copy(
            krasAccountId = krasUserId,
            authStatus = TmdbAuthStatus.LOGGING_IN,
            statusMessage = null,
            isError = false
        )

        viewModelScope.launch(Dispatchers.IO) {
            val result = tmdbDirectClient.loginWithCredentials(
                apiKey = cleanApiKey,
                username = cleanUsername,
                password = passwordInput
            )

            when (result) {
                is TmdbAuthResult.Success -> {
                    // Persist session and API key securely in EncryptedSharedPreferences
                    tmdbSessionStore.saveApiKey(cleanApiKey)
                    tmdbSessionStore.saveSession(
                        krasAccountId = krasUserId,
                        sessionId = result.sessionId,
                        accountId = result.accountId,
                        username = result.username,
                        displayName = result.displayName,
                        avatarUrl = result.avatarUrl,
                        apiKey = cleanApiKey
                    )

                    withContext(Dispatchers.Main) {
                        _tmdbAccountState.value = _tmdbAccountState.value.copy(
                            krasAccountId = krasUserId,
                            authStatus = TmdbAuthStatus.CONNECTED,
                            syncStatus = TmdbSyncStatus.IDLE,
                            accountId = result.accountId,
                            username = result.username,
                            displayName = result.displayName.ifBlank { result.username },
                            avatarUrl = result.avatarUrl,
                            savedApiKey = cleanApiKey,
                            statusMessage = "Connected as ${result.username}",
                            isError = false
                        )
                        onResult?.invoke(true, null)
                    }

                    // Automatically perform initial two-way synchronization upon login
                    syncTmdbAccountNow(isAuto = true, targetKrasUserId = krasUserId)
                }

                is TmdbAuthResult.Error -> {
                    withContext(Dispatchers.Main) {
                        _tmdbAccountState.value = _tmdbAccountState.value.copy(
                            krasAccountId = krasUserId,
                            authStatus = TmdbAuthStatus.DISCONNECTED,
                            statusMessage = result.message,
                            isError = true
                        )
                        onResult?.invoke(false, result.message)
                    }
                }
            }
        }
    }

    private fun canonicalTmdbProxyUrl(candidate: String = _userSettings.value.tmdbProxyUrl): String {
        return TmdbProxyClient.normalizeOrFallbackToCanonical(candidate)
    }

    fun isTmdbAuthCallbackUri(uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        return scheme == "krastv" && host == "tmdb-auth"
    }

    fun handleTmdbAuthCallback(uri: Uri?) {
        // Obsolete browser callback handler - kept as a no-op safety guard
    }

    fun cancelTmdbAuthorization() {
        val krasUserId = activeKrasUserId()
        tmdbSessionStore.clearPendingRequestToken(krasUserId)
        _tmdbAccountState.value = _tmdbAccountState.value.copy(
            krasAccountId = krasUserId,
            authStatus = TmdbAuthStatus.DISCONNECTED,
            statusMessage = null,
            isError = false
        )
    }

    /**
     * Performs two-way synchronization of Favorites (Movies & TV), Watchlist (Movies & TV),
     * and Ratings (Movies & TV) directly with TMDb using the user's stored API key and session.
     * Caches matched TMDb IDs in Room via repository.updateTmdbMetadata and skips unconfident matches.
     */
    fun syncTmdbAccountNow(isAuto: Boolean = false, targetKrasUserId: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            syncTmdbAccountNowSyncResult(isAuto = isAuto, targetKrasUserId = targetKrasUserId)
        }
    }

    private suspend fun syncTmdbAccountNowSyncResult(
        isAuto: Boolean = false,
        targetKrasUserId: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val krasUserId = targetKrasUserId?.takeIf { it.isNotBlank() } ?: activeKrasUserId()
        val stored = tmdbSessionStore.getSession(krasUserId)
        val apiKey = stored?.apiKey?.ifBlank { tmdbSessionStore.getSavedApiKey() }.orEmpty()

        if (stored == null || apiKey.isBlank()) {
            if (!isAuto && krasUserId == activeKrasUserId()) {
                _tmdbAccountState.value = _tmdbAccountState.value.copy(
                    statusMessage = "Please log in with your TMDb account first.",
                    isError = true
                )
            }
            return@withContext false
        }
        if (krasUserId == activeKrasUserId() && _tmdbAccountState.value.syncStatus == TmdbSyncStatus.SYNCING) {
            return@withContext true
        }

        if (krasUserId == activeKrasUserId()) {
            _tmdbAccountState.value = _tmdbAccountState.value.copy(
                krasAccountId = krasUserId,
                syncStatus = TmdbSyncStatus.SYNCING,
                statusMessage = if (isAuto) _tmdbAccountState.value.statusMessage else null,
                isError = false
            )
        }

        val localFavorites = repository.getLocalTmdbSyncFavorites(krasUserId)
        val localWatchlist = repository.getLocalTmdbSyncWatchlist(krasUserId)
        val localRatings = repository.getLocalTmdbSyncRatings(krasUserId)
        val pendingMutations = repository.getPendingTmdbMutations(krasUserId)

        val syncResult = tmdbDirectClient.performTwoWaySync(
            apiKey = apiKey,
            sessionId = stored.sessionId,
            accountId = stored.accountId,
            krasUserId = krasUserId,
            localFavorites = localFavorites,
            localWatchlist = localWatchlist,
            localRatings = localRatings,
            pendingMutations = pendingMutations,
            onResolveTmdbId = { title, year, isSeries ->
                val metadata = tmdbDirectClient.searchAndMatchMedia(
                    apiKey = apiKey,
                    rawTitle = title,
                    year = year,
                    isSeries = isSeries
                )
                if (metadata != null && metadata.tmdbId > 0) {
                    val activeEntity = repository.mediaDao.findMediaByNameAndType(
                        accountId = activeKrasUserId(),
                        name = title,
                        type = if (isSeries) "SERIES" else "MOVIE"
                    )
                    if (activeEntity != null) {
                        val activeMedia = activeEntity.toMediaItem()
                        val enriched = activeMedia.copy(
                            tmdbId = metadata.tmdbId,
                            plot = metadata.overview.ifBlank { activeMedia.plot },
                            streamIcon = metadata.posterUrl.ifBlank { activeMedia.streamIcon },
                            backdrop = metadata.backdropUrl.ifBlank { activeMedia.backdrop },
                            rating = if (metadata.rating > 0.0) metadata.rating else activeMedia.rating,
                            year = metadata.releaseYear.ifBlank { activeMedia.year },
                            genre = metadata.genres.ifBlank { activeMedia.genre },
                            cast = metadata.cast.ifBlank { activeMedia.cast },
                            director = metadata.director.ifBlank { activeMedia.director }
                        )
                        repository.updateTmdbMetadata(enriched)
                        applyEnrichedTmdbItemToState(enriched)
                    }
                    metadata.tmdbId
                } else {
                    0
                }
            }
        )

        if (syncResult.ok) {
            repository.mergeTmdbSyncedData(
                remoteFavorites = syncResult.favorites,
                remoteWatchlist = syncResult.watchlist,
                remoteRatings = syncResult.ratings,
                krasAccountId = krasUserId
            )
            smartSyncEngine.store.clearFavoritesOrHistoryDirty(krasUserId)

            val nowMs = System.currentTimeMillis()
            val favCount = repository.getLocalTmdbSyncFavorites(krasUserId).size.coerceAtLeast(syncResult.favorites.size)
            val wlCount = repository.getWatchlist(krasUserId).size.coerceAtLeast(syncResult.watchlist.size)
            val rtCount = repository.getUserRatings(krasUserId).size.coerceAtLeast(syncResult.ratings.size)

            tmdbSessionStore.updateSyncStats(
                krasAccountId = krasUserId,
                lastSyncTimestampMs = nowMs,
                favoritesCount = favCount,
                watchlistCount = wlCount,
                ratingsCount = rtCount
            )

            if (krasUserId == activeKrasUserId()) {
                _favorites.value = repository.getFavorites(_userSettings.value.liveChannelOrder, krasUserId)
                _tmdbAccountState.value = _tmdbAccountState.value.copy(
                    krasAccountId = krasUserId,
                    syncStatus = TmdbSyncStatus.SUCCESS,
                    lastSyncTimestampMs = nowMs,
                    syncedFavoritesCount = favCount,
                    syncedWatchlistCount = wlCount,
                    syncedRatingsCount = rtCount,
                    pendingOfflineChangesCount = 0,
                    statusMessage = if (isAuto) null else syncResult.userMessage,
                    isError = false
                )
            }
            true
        } else {
            val remainingPending = repository.getPendingTmdbMutations(krasUserId).size
            if (krasUserId == activeKrasUserId()) {
                _tmdbAccountState.value = _tmdbAccountState.value.copy(
                    krasAccountId = krasUserId,
                    syncStatus = TmdbSyncStatus.ERROR,
                    pendingOfflineChangesCount = remainingPending,
                    statusMessage = if (isAuto) null else syncResult.userMessage,
                    isError = !isAuto
                )
            }
            false
        }
    }

    fun setTmdbAutoSyncEnabled(enabled: Boolean) {
        val updatedSettings = _userSettings.value.copy(tmdbAutoSyncEnabled = enabled)
        updateSettings(updatedSettings)
        _tmdbAccountState.value = _tmdbAccountState.value.copy(
            autoSyncEnabled = enabled,
            statusMessage = null,
            isError = false
        )
        if (enabled && _tmdbAccountState.value.isConnected && hasPendingFavoritesOrHistorySync(activeKrasUserId())) {
            syncTmdbAccountNow(isAuto = true)
        }
    }

    fun disconnectTmdbAccount() {
        val krasUserId = activeKrasUserId()
        val stored = tmdbSessionStore.getSession(krasUserId)
        val apiKey = stored?.apiKey?.ifBlank { tmdbSessionStore.getSavedApiKey() }.orEmpty()
        val sessionId = stored?.sessionId.orEmpty()

        tmdbSessionStore.clearSession(krasUserId)
        repository.clearTmdbSyncedDataOnDisconnect(krasUserId)
        _favorites.value = repository.getFavorites(_userSettings.value.liveChannelOrder, krasUserId)
        _tmdbAccountState.value = TmdbAccountUiState(
            krasAccountId = krasUserId,
            authStatus = TmdbAuthStatus.DISCONNECTED,
            syncStatus = TmdbSyncStatus.IDLE,
            autoSyncEnabled = _userSettings.value.tmdbAutoSyncEnabled,
            syncedFavoritesCount = 0,
            syncedWatchlistCount = 0,
            syncedRatingsCount = 0,
            pendingOfflineChangesCount = 0,
            statusMessage = "Logged out from TMDb.",
            isError = false,
            savedApiKey = tmdbSessionStore.getSavedApiKey()
        )

        if (apiKey.isNotBlank() && sessionId.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                tmdbDirectClient.logout(apiKey, sessionId)
            }
        }
    }

    /**
     * Verifies the server-side TMDb metadata service connectivity without bypassing
     * SmartSyncEngine's 24-hour TMDb metadata interval.
     */
    fun verifyTmdbProxyConnection(proxyUrl: String = _userSettings.value.tmdbProxyUrl) {
        val trimmed = canonicalTmdbProxyUrl(proxyUrl)
        if (trimmed.isBlank()) {
            _tmdbHealthState.value = TmdbHealthResult(
                state = TmdbConnectionState.NOT_CONFIGURED,
                message = ""
            )
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _isTmdbVerifying.value = true
            try {
                val result = tmdbProxyClient.verifyConnection(trimmed)
                _tmdbHealthState.value = result
            } catch (_: Exception) {
                _tmdbHealthState.value = TmdbHealthResult(
                    state = TmdbConnectionState.PROXY_UNREACHABLE,
                    message = ""
                )
            } finally {
                _isTmdbVerifying.value = false
            }
        }
    }

    /**
     * Automatically fetches posters, backdrops, descriptions, ratings, release years, genres, cast,
     * and directors from the server-side TMDb proxy for imported Movies and TV Series.
     * Invoked strictly on SmartSyncEngine's 24-hour interval (or when manually requested).
     */
    private suspend fun enrichCatalogWithTmdb(account: Account): Boolean = withContext(Dispatchers.IO) {
        val settings = _userSettings.value
        val proxyUrl = canonicalTmdbProxyUrl(settings.tmdbProxyUrl)
        val savedApiKey = tmdbSessionStore.getSavedApiKey().trim()
        if (!settings.tmdbAutoEnrichEnabled || (proxyUrl.isBlank() && savedApiKey.isBlank())) {
            return@withContext true
        }

        if (savedApiKey.isBlank() && proxyUrl.isNotBlank()) {
            val health = tmdbProxyClient.verifyConnection(proxyUrl)
            _tmdbHealthState.value = health
            if (health.state != TmdbConnectionState.CONNECTED) return@withContext false
        }

        val preferArabic = isArabicMetadataPreferred(settings)

        try {
            val moviesToEnrich = (
                _movies.value.filter {
                    it.tmdbId == 0 || !TmdbMetadataMatcher.isValidSynopsis(it.plot, it.name) || it.streamIcon.isBlank()
                } + repository.getUnenrichedMedia(account.id, "MOVIE", 25)
                ).distinctBy { it.id }.take(25)

            var updatedAny = false
            for (movie in moviesToEnrich) {
                val meta = fetchAccurateTmdbMetadata(
                    rawTitle = movie.name,
                    year = movie.year,
                    isSeries = false,
                    tmdbId = movie.tmdbId,
                    preferArabic = preferArabic
                )
                if (meta != null) {
                    tmdbCacheStore.putForMediaItem(
                        mediaItemId = movie.id,
                        isSeries = false,
                        preferArabic = preferArabic,
                        metadata = meta
                    )
                    val existingValid = TmdbMetadataMatcher.isValidSynopsis(movie.plot, movie.name)
                    val upgradeLanguage = existingValid && (
                        (preferArabic && !TmdbMetadataMatcher.containsArabicScript(movie.plot) && TmdbMetadataMatcher.containsArabicScript(meta.overview)) ||
                            (!preferArabic && TmdbMetadataMatcher.containsArabicScript(movie.plot) && !TmdbMetadataMatcher.containsArabicScript(meta.overview))
                        )
                    val enriched = TmdbProxyClient.enrichMediaItem(
                        item = movie,
                        tmdb = meta,
                        overrideValidExistingPlot = upgradeLanguage,
                        preferArabic = preferArabic
                    )
                    repository.updateTmdbMetadata(enriched)
                    applyEnrichedTmdbItemToState(enriched)
                    updatedAny = true
                }
            }

            val seriesToEnrich = (
                _series.value.filter {
                    it.tmdbId == 0 || !TmdbMetadataMatcher.isValidSynopsis(it.plot, it.name) || it.streamIcon.isBlank()
                } + repository.getUnenrichedMedia(account.id, "SERIES", 25)
                ).distinctBy { it.id }.take(25)

            for (seriesItem in seriesToEnrich) {
                val meta = fetchAccurateTmdbMetadata(
                    rawTitle = seriesItem.name,
                    year = seriesItem.year,
                    isSeries = true,
                    tmdbId = seriesItem.tmdbId,
                    preferArabic = preferArabic
                )
                if (meta != null) {
                    tmdbCacheStore.putForMediaItem(
                        mediaItemId = seriesItem.id,
                        isSeries = true,
                        preferArabic = preferArabic,
                        metadata = meta
                    )
                    val existingValid = TmdbMetadataMatcher.isValidSynopsis(seriesItem.plot, seriesItem.name)
                    val upgradeLanguage = existingValid && (
                        (preferArabic && !TmdbMetadataMatcher.containsArabicScript(seriesItem.plot) && TmdbMetadataMatcher.containsArabicScript(meta.overview)) ||
                            (!preferArabic && TmdbMetadataMatcher.containsArabicScript(seriesItem.plot) && !TmdbMetadataMatcher.containsArabicScript(meta.overview))
                        )
                    val enriched = TmdbProxyClient.enrichMediaItem(
                        item = seriesItem,
                        tmdb = meta,
                        overrideValidExistingPlot = upgradeLanguage,
                        preferArabic = preferArabic
                    )
                    repository.updateTmdbMetadata(enriched)
                    applyEnrichedTmdbItemToState(enriched)
                    updatedAny = true
                }
            }

            if (updatedAny) {
                val movieCats = _movieCategories.value
                val seriesCats = _seriesCategories.value
                _latestMoviesForCategory.value = repository.getLatestByCategory(account.id, "MOVIE", movieCatFilter, 20)
                _latestSeriesForCategory.value = repository.getLatestByCategory(account.id, "SERIES", seriesCatFilter, 20)
                _latestMoviesByCategoryMap.value = repository.getLatestForAllCategories(account.id, "MOVIE", movieCats, 20)
                _latestSeriesByCategoryMap.value = repository.getLatestForAllCategories(account.id, "SERIES", seriesCats, 20)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun applyEnrichedTmdbItemToState(enriched: MediaItem) {
        if (_movies.value.any { it.id == enriched.id }) {
            _movies.value = _movies.value.map { if (it.id == enriched.id) enriched else it }
        }
        if (_latestMoviesForCategory.value.any { it.id == enriched.id }) {
            _latestMoviesForCategory.value = _latestMoviesForCategory.value.map { if (it.id == enriched.id) enriched else it }
        }
        if (_latestMoviesByCategoryMap.value.values.any { list -> list.any { it.id == enriched.id } }) {
            _latestMoviesByCategoryMap.value = _latestMoviesByCategoryMap.value.mapValues { (_, list) ->
                if (list.any { it.id == enriched.id }) list.map { if (it.id == enriched.id) enriched else it } else list
            }
        }
        if (_series.value.any { it.id == enriched.id }) {
            _series.value = _series.value.map { if (it.id == enriched.id) enriched else it }
        }
        if (_latestSeriesForCategory.value.any { it.id == enriched.id }) {
            _latestSeriesForCategory.value = _latestSeriesForCategory.value.map { if (it.id == enriched.id) enriched else it }
        }
        if (_latestSeriesByCategoryMap.value.values.any { list -> list.any { it.id == enriched.id } }) {
            _latestSeriesByCategoryMap.value = _latestSeriesByCategoryMap.value.mapValues { (_, list) ->
                if (list.any { it.id == enriched.id }) list.map { if (it.id == enriched.id) enriched else it } else list
            }
        }
        if (_searchResults.value.any { it.id == enriched.id }) {
            _searchResults.value = _searchResults.value.map { if (it.id == enriched.id) enriched else it }
        }
        _selectedMovieForDetails.value?.let { current ->
            if (current.id == enriched.id) {
                _selectedMovieForDetails.value = enriched
            }
        }
        _selectedSeriesForDetails.value?.let { current ->
            if (current.id == enriched.id) {
                _selectedSeriesForDetails.value = enriched
            }
        }
        _nowPlayingItem.value?.let { current ->
            if (current.id == enriched.id) {
                _nowPlayingItem.value = enriched
            }
        }
        if (_favorites.value.any { it.mediaId == enriched.id }) {
            _favorites.value = repository.getFavorites(_userSettings.value.liveChannelOrder)
        }
    }

    /**
     * Re-projects already-cached ar-SA / en-US TMDb metadata and localized genres onto all
     * visible lists immediately when the user switches Settings -> TMDb -> Metadata Language,
     * without triggering any IPTV synchronization.
     */
    private fun applyCachedLocalizedMetadataToCatalog(preferArabic: Boolean) {
        fun projectItem(item: MediaItem): MediaItem {
            if (item.type == MediaType.LIVE) return item
            val isSeries = item.type == MediaType.SERIES
            val cached = tmdbCacheStore.getExactLanguageForMediaItem(
                mediaItemId = item.id,
                tmdbId = item.tmdbId,
                isSeries = isSeries,
                preferArabic = preferArabic
            )
            return if (cached != null) {
                TmdbProxyClient.enrichMediaItem(
                    item = item,
                    tmdb = cached,
                    overrideValidExistingPlot = true,
                    preferArabic = preferArabic
                )
            } else {
                item.copy(genre = TmdbMetadataMatcher.localizeGenres(item.genre, preferArabic = preferArabic))
            }
        }

        _movies.value = _movies.value.map(::projectItem)
        _latestMoviesForCategory.value = _latestMoviesForCategory.value.map(::projectItem)
        _latestMoviesByCategoryMap.value = _latestMoviesByCategoryMap.value.mapValues { (_, list) -> list.map(::projectItem) }
        _series.value = _series.value.map(::projectItem)
        _latestSeriesForCategory.value = _latestSeriesForCategory.value.map(::projectItem)
        _latestSeriesByCategoryMap.value = _latestSeriesByCategoryMap.value.mapValues { (_, list) -> list.map(::projectItem) }
        _searchResults.value = _searchResults.value.map(::projectItem)
    }

    private fun handleOom(throwable: Throwable, contextMsg: String) {
        throwable.printStackTrace()
        System.gc()
        _errorMessage.value = "Device memory limit reached. Displaying cached content. Tap to retry."
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val details = buildString {
                appendLine("=== OutOfMemory Handled ===")
                appendLine("Context: $contextMsg")
                appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine("Message: ${throwable.message}")
                append(sw.toString())
            }
            val prefs = getApplication<Application>().getSharedPreferences(KrasApplication.PREFS_CRASH, Context.MODE_PRIVATE)
            prefs.edit().putString(KrasApplication.KEY_CRASH_REPORT, details).commit()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- Pagination and Filtering for Movies Screen ---
    fun filterMovies(
        categoryId: String = movieCatFilter,
        query: String = movieQueryFilter,
        minRating: Double = movieRatingFilter,
        year: String = movieYearFilter,
        qualityFilter: String = movieQualityFilter,
        sortByQuality: Boolean = movieSortByQuality,
        sortOrder: MediaSortOrder = movieSortOrder
    ) {
        val isSameFilter = categoryId == movieCatFilter &&
                query == movieQueryFilter &&
                minRating == movieRatingFilter &&
                year == movieYearFilter &&
                qualityFilter == movieQualityFilter &&
                sortByQuality == movieSortByQuality &&
                sortOrder == movieSortOrder
        val account = _activeAccount.value ?: return
        // Prevent redundant DB queries and scroll resets when switching back to the Movies tab
        if (isSameFilter && moviesLoadedAccountId == account.id && _movies.value.isNotEmpty()) {
            return
        }
        if (!isSameFilter) {
            moviePage = 0
        }
        movieCatFilter = categoryId
        movieQueryFilter = query
        movieRatingFilter = minRating
        movieYearFilter = year
        movieQualityFilter = qualityFilter
        movieSortByQuality = sortByQuality
        movieSortOrder = sortOrder

        val limitToFetch = (moviePage + 1) * pageSize
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val paged = repository.queryMediaPaged(
                    accountId = account.id,
                    type = "MOVIE",
                    categoryId = categoryId,
                    query = query,
                    minRating = minRating,
                    year = year,
                    limit = limitToFetch,
                    offset = 0,
                    qualityFilter = qualityFilter,
                    sortByQuality = sortByQuality,
                    sortOrder = sortOrder
                )
                val count = repository.countMedia(
                    accountId = account.id,
                    type = "MOVIE",
                    categoryId = categoryId,
                    query = query,
                    minRating = minRating,
                    year = year,
                    qualityFilter = qualityFilter
                )
                val latestForCat = repository.getLatestByCategory(
                    accountId = account.id,
                    type = "MOVIE",
                    categoryId = categoryId,
                    limit = 20
                )
                val yearsForCat = repository.getAvailableYears(
                    accountId = account.id,
                    type = "MOVIE",
                    categoryId = categoryId
                )
                _movies.value = paged
                totalMoviesCount.value = count
                moviesLoadedAccountId = account.id
                if (latestForCat.isNotEmpty() || !account.isDemo) {
                    _latestMoviesForCategory.value = latestForCat
                }
                if (yearsForCat.isNotEmpty() || !account.isDemo) {
                    _availableMovieYears.value = yearsForCat
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadMoreMovies() {
        if (isMoviesLoadingMore) return
        val account = _activeAccount.value ?: return
        if (_movies.value.size >= totalMoviesCount.value) return

        isMoviesLoadingMore = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val nextPage = moviePage + 1
                val more = repository.queryMediaPaged(
                    accountId = account.id,
                    type = "MOVIE",
                    categoryId = movieCatFilter,
                    query = movieQueryFilter,
                    minRating = movieRatingFilter,
                    year = movieYearFilter,
                    limit = pageSize,
                    offset = nextPage * pageSize,
                    qualityFilter = movieQualityFilter,
                    sortByQuality = movieSortByQuality,
                    sortOrder = movieSortOrder
                )
                if (more.isNotEmpty()) {
                    moviePage = nextPage
                    _movies.value = (_movies.value + more).distinctBy { it.id }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isMoviesLoadingMore = false
            }
        }
    }

    // --- Pagination and Filtering for Series Screen ---
    fun filterSeries(
        categoryId: String = seriesCatFilter,
        query: String = seriesQueryFilter,
        minRating: Double = seriesRatingFilter,
        year: String = seriesYearFilter,
        qualityFilter: String = seriesQualityFilter,
        sortByQuality: Boolean = seriesSortByQuality,
        sortOrder: MediaSortOrder = seriesSortOrder
    ) {
        val isSameFilter = categoryId == seriesCatFilter &&
                query == seriesQueryFilter &&
                minRating == seriesRatingFilter &&
                year == seriesYearFilter &&
                qualityFilter == seriesQualityFilter &&
                sortByQuality == seriesSortByQuality &&
                sortOrder == seriesSortOrder
        val account = _activeAccount.value ?: return
        // Prevent redundant DB queries and scroll resets when switching back to the Series tab
        if (isSameFilter && seriesLoadedAccountId == account.id && _series.value.isNotEmpty()) {
            return
        }
        if (!isSameFilter) {
            seriesPage = 0
        }
        seriesCatFilter = categoryId
        seriesQueryFilter = query
        seriesRatingFilter = minRating
        seriesYearFilter = year
        seriesQualityFilter = qualityFilter
        seriesSortByQuality = sortByQuality
        seriesSortOrder = sortOrder

        val limitToFetch = (seriesPage + 1) * pageSize
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val paged = repository.queryMediaPaged(
                    accountId = account.id,
                    type = "SERIES",
                    categoryId = categoryId,
                    query = query,
                    minRating = minRating,
                    year = year,
                    limit = limitToFetch,
                    offset = 0,
                    qualityFilter = qualityFilter,
                    sortByQuality = sortByQuality,
                    sortOrder = sortOrder
                )
                val count = repository.countMedia(
                    accountId = account.id,
                    type = "SERIES",
                    categoryId = categoryId,
                    query = query,
                    minRating = minRating,
                    year = year,
                    qualityFilter = qualityFilter
                )
                val latestForCat = repository.getLatestByCategory(
                    accountId = account.id,
                    type = "SERIES",
                    categoryId = categoryId,
                    limit = 20
                )
                val yearsForCat = repository.getAvailableYears(
                    accountId = account.id,
                    type = "SERIES",
                    categoryId = categoryId
                )
                _series.value = paged
                totalSeriesCount.value = count
                seriesLoadedAccountId = account.id
                if (latestForCat.isNotEmpty() || !account.isDemo) {
                    _latestSeriesForCategory.value = latestForCat
                }
                if (yearsForCat.isNotEmpty() || !account.isDemo) {
                    _availableSeriesYears.value = yearsForCat
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadMoreSeries() {
        if (isSeriesLoadingMore) return
        val account = _activeAccount.value ?: return
        if (_series.value.size >= totalSeriesCount.value) return

        isSeriesLoadingMore = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val nextPage = seriesPage + 1
                val more = repository.queryMediaPaged(
                    accountId = account.id,
                    type = "SERIES",
                    categoryId = seriesCatFilter,
                    query = seriesQueryFilter,
                    minRating = seriesRatingFilter,
                    year = seriesYearFilter,
                    limit = pageSize,
                    offset = nextPage * pageSize,
                    qualityFilter = seriesQualityFilter,
                    sortByQuality = seriesSortByQuality,
                    sortOrder = seriesSortOrder
                )
                if (more.isNotEmpty()) {
                    seriesPage = nextPage
                    _series.value = (_series.value + more).distinctBy { it.id }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isSeriesLoadingMore = false
            }
        }
    }

    // --- Pagination and Filtering for Live TV Screen ---
    fun filterLive(
        categoryId: String = liveCatFilter,
        query: String = liveQueryFilter,
        qualityFilter: String = liveQualityFilter,
        sortByQuality: Boolean = liveSortByQuality
    ) {
        val isSameFilter = categoryId == liveCatFilter &&
                query == liveQueryFilter &&
                qualityFilter == liveQualityFilter &&
                sortByQuality == liveSortByQuality
        val account = _activeAccount.value ?: return
        // Prevent redundant DB queries and scroll resets when switching back to the Live TV tab
        if (isSameFilter && liveLoadedAccountId == account.id && _liveStreams.value.isNotEmpty()) {
            return
        }
        if (!isSameFilter) {
            livePage = 0
        }
        liveCatFilter = categoryId
        liveQueryFilter = query
        liveQualityFilter = qualityFilter
        liveSortByQuality = sortByQuality

        val order = _userSettings.value.liveChannelOrder
        val limitToFetch = (livePage + 1) * pageSize
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val paged = repository.queryMediaPaged(
                    accountId = account.id,
                    type = "LIVE",
                    categoryId = categoryId,
                    query = query,
                    minRating = 0.0,
                    year = "All",
                    limit = limitToFetch,
                    offset = 0,
                    liveChannelOrder = order,
                    qualityFilter = qualityFilter,
                    sortByQuality = sortByQuality
                )
                val count = repository.countMedia(
                    accountId = account.id,
                    type = "LIVE",
                    categoryId = categoryId,
                    query = query,
                    minRating = 0.0,
                    year = "All",
                    qualityFilter = qualityFilter
                )
                _liveStreams.value = paged
                totalLiveCount.value = count
                liveLoadedAccountId = account.id
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadMoreLive() {
        if (isLiveLoadingMore) return
        val account = _activeAccount.value ?: return
        if (_liveStreams.value.size >= totalLiveCount.value) return

        isLiveLoadingMore = true
        val order = _userSettings.value.liveChannelOrder
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val nextPage = livePage + 1
                val more = repository.queryMediaPaged(
                    accountId = account.id,
                    type = "LIVE",
                    categoryId = liveCatFilter,
                    query = liveQueryFilter,
                    minRating = 0.0,
                    year = "All",
                    limit = pageSize,
                    offset = nextPage * pageSize,
                    liveChannelOrder = order,
                    qualityFilter = liveQualityFilter,
                    sortByQuality = liveSortByQuality
                )
                if (more.isNotEmpty()) {
                    livePage = nextPage
                    _liveStreams.value = (_liveStreams.value + more).distinctBy { it.id }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLiveLoadingMore = false
            }
        }
    }

    fun refreshLiveChannelsOrder() {
        val account = _activeAccount.value ?: return
        val order = _userSettings.value.liveChannelOrder
        val limitToFetch = (livePage + 1) * pageSize
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val paged = repository.queryMediaPaged(
                    accountId = account.id,
                    type = "LIVE",
                    categoryId = liveCatFilter,
                    query = liveQueryFilter,
                    minRating = 0.0,
                    year = "All",
                    limit = limitToFetch,
                    offset = 0,
                    liveChannelOrder = order,
                    qualityFilter = liveQualityFilter,
                    sortByQuality = liveSortByQuality
                )
                val count = repository.countMedia(
                    accountId = account.id,
                    type = "LIVE",
                    categoryId = liveCatFilter,
                    query = liveQueryFilter,
                    minRating = 0.0,
                    year = "All",
                    qualityFilter = liveQualityFilter
                )
                _liveStreams.value = paged
                totalLiveCount.value = count
                _favorites.value = repository.getFavorites(order)
                if (currentSearchQuery.isNotBlank()) {
                    _searchResults.value = repository.searchAll(account.id, currentSearchQuery, order)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- Global Room Search (Ordered with LiveChannelOrder) ---
    fun performSearch(query: String) {
        currentSearchQuery = query
        val account = _activeAccount.value ?: return
        val order = _userSettings.value.liveChannelOrder
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (query.isBlank()) {
                    val combined = _movies.value + _series.value +
                            ChannelNameNormalizer.sortMediaItems(_liveStreams.value, order)
                    _searchResults.value = combined.distinctBy { it.id }
                } else {
                    val roomResults = repository.searchAll(account.id, query, order)
                    if (roomResults.isNotEmpty()) {
                        _searchResults.value = roomResults
                    } else {
                        val fallback = (_movies.value + _series.value + _liveStreams.value).filter { item ->
                            item.name.contains(query, ignoreCase = true) ||
                                    item.genre.contains(query, ignoreCase = true) ||
                                    item.plot.contains(query, ignoreCase = true) ||
                                    item.categoryName.contains(query, ignoreCase = true)
                        }
                        val moviesAndSeries = fallback.filter { it.type != MediaType.LIVE }
                        val liveOnly = ChannelNameNormalizer.sortMediaItems(
                            fallback.filter { it.type == MediaType.LIVE },
                            order
                        )
                        _searchResults.value = (moviesAndSeries + liveOnly).distinctBy { it.id }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- Unified On-Demand Movie & Series Details (IPTV-First + Accurate TMDb Fallback + Bilingual Caching) ---

    fun isArabicMetadataPreferred(settings: UserSettings = _userSettings.value): Boolean {
        return settings.resolvePreferArabicMetadata()
    }

    private fun currentOverviewLanguageKey(): String {
        return _userSettings.value.resolveTmdbLanguageTag()
    }

    /**
     * Lightweight on-demand localization for visible items on Home Hero Banner, Movie Reels,
     * and Search without triggering a full IPTV synchronization.
     */
    fun ensureLocalizedMetadataOnDemand(mediaItem: MediaItem) {
        if (mediaItem.type == MediaType.LIVE) return
        val preferArabic = isArabicMetadataPreferred()
        val langKey = if (preferArabic) TmdbMetadataMatcher.TMDB_LANG_ARABIC else TmdbMetadataMatcher.TMDB_LANG_ENGLISH
        val inflightKey = "${mediaItem.id}:$langKey"
        val isSeries = mediaItem.type == MediaType.SERIES

        val cached = tmdbCacheStore.getExactLanguageForMediaItem(
            mediaItemId = mediaItem.id,
            tmdbId = mediaItem.tmdbId,
            isSeries = isSeries,
            preferArabic = preferArabic
        )
        if (cached != null && !tmdbCacheStore.isStale(cached)) {
            val localized = TmdbProxyClient.enrichMediaItem(
                item = mediaItem,
                tmdb = cached,
                overrideValidExistingPlot = true,
                preferArabic = preferArabic
            )
            if (localized.plot != mediaItem.plot || localized.genre != mediaItem.genre || localized.backdrop != mediaItem.backdrop) {
                applyEnrichedTmdbItemToState(localized)
            }
            return
        }

        if (tmdbCacheStore.shouldSkipAfterRecentFailure(mediaItem.id, preferArabic)) return
        if (!onDemandEnrichInFlight.add(inflightKey)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                getOrFetchEnrichedMediaDetails(mediaItem, forceRefresh = false)
            } catch (_: Exception) {
            } finally {
                onDemandEnrichInFlight.remove(inflightKey)
            }
        }
    }

    /**
     * Opens the localized Actor Biography dialog (ar-SA when Arabic metadata is selected,
     * automatic en-US fallback, cached separately per language, preserving original actor name).
     */
    fun loadActorBiography(member: TmdbCastMember) {
        if (member.id <= 0) return
        val preferArabic = isArabicMetadataPreferred()
        val langTag = if (preferArabic) TmdbMetadataMatcher.TMDB_LANG_ARABIC else TmdbMetadataMatcher.TMDB_LANG_ENGLISH

        val cached = tmdbCacheStore.getPersonBiography(member.id, preferArabic)
        if (cached != null && !tmdbCacheStore.isBiographyStale(cached) && cached.biography.isNotBlank()) {
            _selectedActorBiography.value = cached.copy(
                name = member.name.ifBlank { cached.name },
                character = member.character.ifBlank { cached.character },
                profileUrl = member.profileUrl.ifBlank { cached.profileUrl }
            )
            _isActorBioLoading.value = false
            return
        }

        _selectedActorBiography.value = cached?.copy(
            name = member.name.ifBlank { cached.name },
            character = member.character.ifBlank { cached.character },
            profileUrl = member.profileUrl.ifBlank { cached.profileUrl }
        ) ?: TmdbPersonBiography(
            personId = member.id,
            name = member.name,
            character = member.character,
            profileUrl = member.profileUrl,
            languageTag = langTag
        )

        actorBioJob?.cancel()
        actorBioJob = viewModelScope.launch {
            _isActorBioLoading.value = true
            try {
                val fetched = withContext(Dispatchers.IO) {
                    val savedApiKey = tmdbSessionStore.getSavedApiKey().trim()
                    val proxyUrl = canonicalTmdbProxyUrl(_userSettings.value.tmdbProxyUrl)
                    val directBio = if (savedApiKey.isNotEmpty()) {
                        tmdbDirectClient.fetchPersonBiography(
                            apiKey = savedApiKey,
                            personId = member.id,
                            preferArabic = preferArabic,
                            fallbackName = member.name,
                            fallbackCharacter = member.character,
                            fallbackProfileUrl = member.profileUrl
                        )
                    } else {
                        null
                    }
                    directBio ?: if (proxyUrl.isNotBlank()) {
                        tmdbProxyClient.fetchPersonBiography(
                            rawProxyUrl = proxyUrl,
                            personId = member.id,
                            preferArabic = preferArabic,
                            fallbackName = member.name,
                            fallbackCharacter = member.character,
                            fallbackProfileUrl = member.profileUrl
                        )
                    } else {
                        null
                    }
                }
                if (fetched != null) {
                    val finalized = fetched.copy(
                        name = member.name.ifBlank { fetched.name },
                        character = member.character.ifBlank { fetched.character },
                        profileUrl = member.profileUrl.ifBlank { fetched.profileUrl }
                    )
                    withContext(Dispatchers.IO) {
                        tmdbCacheStore.putPersonBiography(member.id, preferArabic, finalized)
                    }
                    if (_selectedActorBiography.value?.personId == member.id) {
                        _selectedActorBiography.value = finalized
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                _isActorBioLoading.value = false
            }
        }
    }

    fun dismissActorBiography() {
        actorBioJob?.cancel()
        _selectedActorBiography.value = null
        _isActorBioLoading.value = false
    }

    /**
     * Opens the Movie or TV Series Details screen from any section (Home, Search, Movies, Series,
     * Favorites, or Recommendations) and ensures real, verified plot synopses and metadata are loaded
     * and cached without ever showing generic placeholder text.
     */
    fun showMediaDetails(mediaItem: MediaItem) {
        val preferArabic = isArabicMetadataPreferred()
        val langKey = currentOverviewLanguageKey()
        when (mediaItem.type) {
            MediaType.MOVIE -> {
                _selectedSeriesForDetails.value = null
                val manualBinding = tmdbCacheStore.getManualBinding(mediaItem.id)
                val cachedRich = tmdbCacheStore.getForMediaItem(
                    mediaItemId = mediaItem.id,
                    tmdbId = manualBinding?.tmdbId ?: mediaItem.tmdbId,
                    isSeries = false,
                    preferArabic = preferArabic
                )
                _selectedMovieRichMetadata.value = cachedRich

                val cached = enrichedMediaDetailsCache["${mediaItem.id}:$langKey"]
                val sanitizedInitial = (cached ?: mediaItem).let {
                    it.copy(
                        plot = TmdbMetadataMatcher.sanitizeSynopsis(it.plot, it.name),
                        genre = TmdbMetadataMatcher.localizeGenres(it.genre, preferArabic = preferArabic)
                    )
                }
                _selectedMovieForDetails.value = sanitizedInitial

                if (cached != null &&
                    cachedRich != null &&
                    !tmdbCacheStore.isStale(cachedRich) &&
                    TmdbMetadataMatcher.isValidSynopsis(cached.plot, cached.name)
                ) {
                    _isMovieDetailsLoading.value = false
                    return
                }

                movieDetailsJob?.cancel()
                movieDetailsJob = viewModelScope.launch {
                    _isMovieDetailsLoading.value = true
                    try {
                        val enriched = getOrFetchEnrichedMediaDetails(mediaItem)
                        if (_selectedMovieForDetails.value?.id == mediaItem.id) {
                            _selectedMovieForDetails.value = enriched
                            _selectedMovieRichMetadata.value = tmdbCacheStore.getForMediaItem(
                                mediaItemId = mediaItem.id,
                                tmdbId = enriched.tmdbId,
                                isSeries = false,
                                preferArabic = preferArabic
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        if (_selectedMovieForDetails.value?.id == mediaItem.id) {
                            _isMovieDetailsLoading.value = false
                        }
                    }
                }
            }

            MediaType.SERIES -> {
                _selectedMovieForDetails.value = null
                val cachedItem = enrichedMediaDetailsCache["${mediaItem.id}:$langKey"]
                val sanitizedInitial = (cachedItem ?: mediaItem).let {
                    it.copy(
                        plot = TmdbMetadataMatcher.sanitizeSynopsis(it.plot, it.name),
                        genre = TmdbMetadataMatcher.localizeGenres(it.genre, preferArabic = preferArabic)
                    )
                }
                _selectedSeriesForDetails.value = sanitizedInitial
                loadSeriesDetails(sanitizedInitial)
            }

            MediaType.LIVE -> {
                selectLiveChannel(mediaItem)
            }
        }
    }

    fun dismissMovieDetails() {
        movieDetailsJob?.cancel()
        _selectedMovieForDetails.value = null
        _isMovieDetailsLoading.value = false
    }

    fun dismissSeriesDetails() {
        seriesDetailsJob?.cancel()
        _selectedSeriesForDetails.value = null
        _isSeriesDetailsLoading.value = false
    }

    fun dismissMediaDetails() {
        dismissMovieDetails()
        dismissSeriesDetails()
    }

    /**
     * Retrieves or fetches verified enriched details for a movie or series:
     * 1. Checks in-memory language-aware cache and persisted Room metadata.
     * 2. Uses IPTV / Xtream Codes plot (`get_vod_info`) first if it contains a valid synopsis.
     * 3. If the plot is missing, empty, or generic (or Arabic is selected and current plot is non-Arabic),
     *    retrieves the verified overview from TMDb (Direct API v3 when user API key is configured,
     *    or KRAS TMDb Proxy otherwise), requesting Arabic first when Arabic is selected and falling back
     *    to English if Arabic is unavailable.
     * 4. Caches verified metadata in memory and Room so reopening details loads quickly.
     */
    suspend fun getOrFetchEnrichedMediaDetails(
        mediaItem: MediaItem,
        forceRefresh: Boolean = false
    ): MediaItem = withContext(Dispatchers.IO) {
        val preferArabic = isArabicMetadataPreferred()
        val langKey = if (preferArabic) TmdbMetadataMatcher.TMDB_LANG_ARABIC else TmdbMetadataMatcher.TMDB_LANG_ENGLISH
        val cacheKey = "${mediaItem.id}:$langKey"
        val isSeries = mediaItem.type == MediaType.SERIES

        val manualBinding = tmdbCacheStore.getManualBinding(mediaItem.id)
        val existingExactRich = tmdbCacheStore.getExactLanguageForMediaItem(
            mediaItemId = mediaItem.id,
            tmdbId = manualBinding?.tmdbId ?: mediaItem.tmdbId,
            isSeries = isSeries,
            preferArabic = preferArabic
        )

        if (!forceRefresh &&
            existingExactRich != null &&
            !tmdbCacheStore.isStale(existingExactRich) &&
            tmdbCacheStore.isRichMetadataComplete(existingExactRich)
        ) {
            if (!isSeries && _selectedMovieForDetails.value?.id == mediaItem.id) {
                _selectedMovieRichMetadata.value = existingExactRich
            }
            enrichedMediaDetailsCache[cacheKey]?.let { cached ->
                if (TmdbMetadataMatcher.isValidBackdropUrl(cached.backdrop, cached.streamIcon) ||
                    existingExactRich.backdropUrl.isBlank()
                ) {
                    return@withContext cached
                }
            }
        }

        // Start by merging any previously persisted metadata from Room
        val persisted = repository.getItemById(mediaItem.id)
        var working = if (persisted != null) {
            val persistedPlot = TmdbMetadataMatcher.sanitizeSynopsis(persisted.plot, mediaItem.name)
            val currentPlot = TmdbMetadataMatcher.sanitizeSynopsis(mediaItem.plot, mediaItem.name)
            val validCurrentBackdrop = mediaItem.backdrop.takeIf {
                TmdbMetadataMatcher.isValidBackdropUrl(it, mediaItem.streamIcon)
            }.orEmpty()
            val validPersistedBackdrop = persisted.backdrop.takeIf {
                TmdbMetadataMatcher.isValidBackdropUrl(it, persisted.streamIcon.ifBlank { mediaItem.streamIcon })
            }.orEmpty()
            mediaItem.copy(
                streamIcon = mediaItem.streamIcon.ifBlank { persisted.streamIcon },
                backdrop = validCurrentBackdrop.ifBlank { validPersistedBackdrop },
                plot = currentPlot.ifEmpty { persistedPlot },
                genre = mediaItem.genre.ifBlank { persisted.genre },
                cast = mediaItem.cast.ifBlank { persisted.cast },
                director = mediaItem.director.ifBlank { persisted.director },
                duration = mediaItem.duration.ifBlank { persisted.duration },
                year = mediaItem.year.ifBlank { persisted.year },
                rating = if (mediaItem.rating > 0.0) mediaItem.rating else persisted.rating,
                tmdbId = manualBinding?.tmdbId ?: if (mediaItem.tmdbId > 0) mediaItem.tmdbId else persisted.tmdbId
            )
        } else {
            mediaItem.copy(
                backdrop = mediaItem.backdrop.takeIf {
                    TmdbMetadataMatcher.isValidBackdropUrl(it, mediaItem.streamIcon)
                }.orEmpty(),
                plot = TmdbMetadataMatcher.sanitizeSynopsis(mediaItem.plot, mediaItem.name),
                tmdbId = manualBinding?.tmdbId ?: mediaItem.tmdbId
            )
        }

        // Step 1: Fetch real Xtream VOD details first for Movies on Xtream accounts
        val active = _activeAccount.value
        if (working.type == MediaType.MOVIE &&
            active != null &&
            !active.isDemo &&
            !M3uPlaylistParser.isM3uPlaylistUrl(active.serverUrl) &&
            working.streamId > 0
        ) {
            val needsXtreamFetch = !TmdbMetadataMatcher.isValidSynopsis(working.plot, working.name) ||
                working.tmdbId <= 0 ||
                working.cast.isBlank() ||
                working.director.isBlank() ||
                working.duration.isBlank()

            if (needsXtreamFetch) {
                val vodDetails = apiClient.fetchVodDetails(
                    account = active,
                    vodId = working.streamId,
                    fallbackQuality = working.quality.ifBlank { working.resolution }
                )
                if (vodDetails != null) {
                    val validXtreamPlot = TmdbMetadataMatcher.sanitizeSynopsis(vodDetails.plot, working.name)
                    val existingValidPlot = TmdbMetadataMatcher.sanitizeSynopsis(working.plot, working.name)
                    val validVodBackdrop = vodDetails.backdrop.takeIf {
                        TmdbMetadataMatcher.isValidBackdropUrl(it, vodDetails.streamIcon.ifBlank { working.streamIcon })
                    }.orEmpty()
                    working = working.copy(
                        streamIcon = working.streamIcon.ifBlank { vodDetails.streamIcon },
                        backdrop = validVodBackdrop.ifBlank { working.backdrop },
                        plot = validXtreamPlot.ifEmpty { existingValidPlot },
                        cast = vodDetails.cast.ifBlank { working.cast },
                        director = vodDetails.director.ifBlank { working.director },
                        genre = vodDetails.genre.ifBlank { working.genre },
                        year = vodDetails.releaseDate.take(4).ifBlank { working.year },
                        rating = if (vodDetails.rating > 0.0 && working.rating <= 0.0) vodDetails.rating else working.rating,
                        duration = vodDetails.duration.ifBlank { working.duration },
                        tmdbId = manualBinding?.tmdbId ?: if (vodDetails.tmdbId > 0) vodDetails.tmdbId else working.tmdbId,
                        quality = vodDetails.quality.ifBlank { working.quality }
                    )
                }
            }
        }

        // Step 2: Determine if we need to fetch rich TMDb metadata (or refresh stale cache / switch language)
        val hasValidPlot = TmdbMetadataMatcher.isValidSynopsis(working.plot, working.name)
        val wantsArabicUpgrade = preferArabic && hasValidPlot && !TmdbMetadataMatcher.containsArabicScript(working.plot)
        val wantsEnglishSwitch = !preferArabic && hasValidPlot && TmdbMetadataMatcher.containsArabicScript(working.plot)
        val hasRealBackdrop = TmdbMetadataMatcher.isValidBackdropUrl(working.backdrop, working.streamIcon)
        val hasCompleteRichCache = existingExactRich != null &&
            !tmdbCacheStore.isStale(existingExactRich) &&
            tmdbCacheStore.isRichMetadataComplete(existingExactRich)
        val needsMetadataFill = forceRefresh ||
            !hasValidPlot ||
            (wantsArabicUpgrade && !hasCompleteRichCache) ||
            (wantsEnglishSwitch && !hasCompleteRichCache) ||
            !hasRealBackdrop ||
            working.cast.isBlank() ||
            working.director.isBlank() ||
            working.genre.isBlank() ||
            working.tmdbId <= 0 ||
            !hasCompleteRichCache

        if (needsMetadataFill && (forceRefresh || !tmdbCacheStore.shouldSkipAfterRecentFailure(working.id, preferArabic))) {
            val tmdbMeta = fetchAccurateTmdbMetadata(
                rawTitle = working.name,
                year = working.year,
                isSeries = isSeries,
                tmdbId = manualBinding?.tmdbId ?: working.tmdbId,
                preferArabic = preferArabic
            )
            if (tmdbMeta != null) {
                val stamped = tmdbMeta.copy(
                    cachedAtMs = System.currentTimeMillis(),
                    isManualMatch = manualBinding != null
                )
                tmdbCacheStore.putForMediaItem(
                    mediaItemId = working.id,
                    isSeries = isSeries,
                    preferArabic = preferArabic,
                    metadata = stamped
                )
                refreshDiagnosticsCacheCounts()
                if (!isSeries && _selectedMovieForDetails.value?.id == working.id) {
                    _selectedMovieRichMetadata.value = stamped
                }

                val tmdbHasValidOverview = TmdbMetadataMatcher.isValidSynopsis(stamped.overview, working.name)
                val tmdbHasArabicOverview = TmdbMetadataMatcher.containsArabicScript(stamped.overview) && tmdbHasValidOverview
                val tmdbHasEnglishOverview = !TmdbMetadataMatcher.containsArabicScript(stamped.overview) && tmdbHasValidOverview
                val overridePlot = manualBinding != null ||
                    !hasValidPlot ||
                    (wantsArabicUpgrade && tmdbHasArabicOverview) ||
                    (wantsEnglishSwitch && tmdbHasEnglishOverview)
                working = TmdbProxyClient.enrichMediaItem(
                    item = working,
                    tmdb = stamped,
                    overrideValidExistingPlot = overridePlot,
                    preferArabic = preferArabic
                )
            } else {
                tmdbCacheStore.markLookupFailure(working.id, preferArabic)
                // Preserve existing cached metadata if API temporarily failed
                val fallbackCached = existingExactRich ?: tmdbCacheStore.getForMediaItem(
                    mediaItemId = working.id,
                    tmdbId = working.tmdbId,
                    isSeries = isSeries,
                    preferArabic = preferArabic
                )
                if (fallbackCached != null) {
                    working = TmdbProxyClient.enrichMediaItem(
                        item = working,
                        tmdb = fallbackCached,
                        overrideValidExistingPlot = !hasValidPlot || wantsArabicUpgrade || wantsEnglishSwitch,
                        preferArabic = preferArabic
                    )
                }
            }
        } else if (existingExactRich != null) {
            if (!isSeries && _selectedMovieForDetails.value?.id == working.id) {
                _selectedMovieRichMetadata.value = existingExactRich
            }
            val cachedHasValidOverview = TmdbMetadataMatcher.isValidSynopsis(existingExactRich.overview, working.name)
            val overridePlot = manualBinding != null ||
                !hasValidPlot ||
                (wantsArabicUpgrade && TmdbMetadataMatcher.containsArabicScript(existingExactRich.overview) && cachedHasValidOverview) ||
                (wantsEnglishSwitch && !TmdbMetadataMatcher.containsArabicScript(existingExactRich.overview) && cachedHasValidOverview)
            working = TmdbProxyClient.enrichMediaItem(
                item = working,
                tmdb = existingExactRich,
                overrideValidExistingPlot = overridePlot,
                preferArabic = preferArabic
            )
        }

        // Step 3: Final strict sanitization (never allow placeholder text) & persist/cache
        val finalCleanPlot = TmdbMetadataMatcher.sanitizeSynopsis(working.plot, working.name)
        val finalItem = working.copy(
            plot = finalCleanPlot,
            genre = TmdbMetadataMatcher.localizeGenres(working.genre, preferArabic = preferArabic)
        )
        enrichedMediaDetailsCache[cacheKey] = finalItem

        if (finalItem.tmdbId > 0 || finalItem.plot.isNotEmpty() || finalItem.cast.isNotBlank() || finalItem.backdrop.isNotBlank()) {
            val persistedValidPlot = TmdbMetadataMatcher.sanitizeSynopsis(persisted?.plot, finalItem.name)
            val itemToPersist = if (finalItem.plot.isEmpty() && persistedValidPlot.isNotEmpty()) {
                finalItem.copy(plot = persistedValidPlot)
            } else {
                finalItem
            }
            repository.updateTmdbMetadata(itemToPersist)
            applyEnrichedTmdbItemToState(finalItem)
        }

        finalItem
    }

    /**
     * Resolves verified TMDb metadata using Direct TMDb API v3/v4 when a user API key is configured,
     * falling back automatically to the server-side KRAS TMDb Proxy when needed.
     * Records sanitized success/failure telemetry for Connection Diagnostics.
     */
    private suspend fun fetchAccurateTmdbMetadata(
        rawTitle: String,
        year: String,
        isSeries: Boolean,
        tmdbId: Int,
        preferArabic: Boolean
    ): TmdbMediaMetadata? {
        val savedApiKey = tmdbSessionStore.getSavedApiKey().trim()
        if (savedApiKey.isNotEmpty()) {
            val directMeta = tmdbDirectClient.fetchAccurateMediaMetadata(
                apiKey = savedApiKey,
                rawTitle = rawTitle,
                year = year,
                isSeries = isSeries,
                tmdbId = tmdbId,
                preferArabic = preferArabic
            )
            if (directMeta != null) {
                tmdbCacheStore.recordApiSuccess(if (isSeries) "direct/tv" else "direct/movie")
                return directMeta
            }
        }

        val proxyUrl = canonicalTmdbProxyUrl(_userSettings.value.tmdbProxyUrl)
        if (proxyUrl.isNotBlank()) {
            val proxyMeta = tmdbProxyClient.fetchAccurateMediaMetadata(
                rawProxyUrl = proxyUrl,
                rawTitle = rawTitle,
                year = year,
                isSeries = isSeries,
                tmdbId = tmdbId,
                preferArabic = preferArabic
            )
            if (proxyMeta != null) {
                tmdbCacheStore.recordApiSuccess(if (isSeries) "proxy/tv" else "proxy/movie")
                return proxyMeta
            }
        }
        return null
    }

    // --- On-Demand Series Details (Xtream Codes + M3U + Accurate TMDb Fallback & Persistent Caching) ---
    fun loadSeriesDetails(seriesItem: MediaItem, forceRefresh: Boolean = false) {
        val preferArabic = isArabicMetadataPreferred()
        val langKey = if (preferArabic) TmdbMetadataMatcher.TMDB_LANG_ARABIC else TmdbMetadataMatcher.TMDB_LANG_ENGLISH
        val detailsCacheKey = "${seriesItem.streamId}:$langKey"
        val manualBinding = tmdbCacheStore.getManualBinding(seriesItem.id)
        val cachedRich = tmdbCacheStore.getForMediaItem(
            mediaItemId = seriesItem.id,
            tmdbId = manualBinding?.tmdbId ?: seriesItem.tmdbId,
            isSeries = true,
            preferArabic = preferArabic
        )
        val cachedExactRich = tmdbCacheStore.getExactLanguageForMediaItem(
            mediaItemId = seriesItem.id,
            tmdbId = manualBinding?.tmdbId ?: seriesItem.tmdbId,
            isSeries = true,
            preferArabic = preferArabic
        )
        if (cachedRich != null) {
            _selectedSeriesRichMetadata.value = cachedRich
        }

        val cachedDetails = enrichedSeriesDetailsCache[detailsCacheKey]
        val isCompleteAndFresh = !forceRefresh &&
            cachedDetails != null &&
            cachedExactRich != null &&
            !tmdbCacheStore.isStale(cachedExactRich) &&
            tmdbCacheStore.isRichMetadataComplete(cachedExactRich) &&
            TmdbMetadataMatcher.isValidSynopsis(cachedDetails.plot, cachedDetails.name) &&
            TmdbMetadataMatcher.isValidBackdropUrl(cachedDetails.backdrop, cachedDetails.cover)

        if (isCompleteAndFresh && cachedDetails != null) {
            _currentSeriesDetails.value = cachedDetails
            _isSeriesDetailsLoading.value = false
            return
        }

        if (cachedDetails != null) {
            _currentSeriesDetails.value = cachedDetails
        } else if (_currentSeriesDetails.value?.seriesId != seriesItem.streamId) {
            _currentSeriesDetails.value = null
        }

        seriesDetailsJob?.cancel()
        seriesDetailsJob = viewModelScope.launch {
            _isSeriesDetailsLoading.value = (cachedDetails == null && cachedRich == null)
            try {
                val active = _activeAccount.value
                val persisted = withContext(Dispatchers.IO) { repository.getItemById(seriesItem.id) }
                var baseDetails: SeriesDetails? = null

                if (active != null && !active.isDemo) {
                    baseDetails = m3uSeriesDetailsMap[seriesItem.streamId]
                        ?: withContext(Dispatchers.IO) {
                            if (!M3uPlaylistParser.isM3uPlaylistUrl(active.serverUrl)) {
                                apiClient.getSeriesDetails(active, seriesItem.streamId)
                            } else {
                                null
                            }
                        }
                }

                if (baseDetails == null) {
                    baseDetails = if (active?.isDemo == true) {
                        SampleData.getSampleSeriesDetails(seriesItem.streamId)
                    } else {
                        SeriesDetails(
                            seriesId = seriesItem.streamId,
                            name = seriesItem.name,
                            cover = seriesItem.streamIcon,
                            backdrop = seriesItem.backdrop.takeIf {
                                TmdbMetadataMatcher.isValidBackdropUrl(it, seriesItem.streamIcon)
                            }.orEmpty(),
                            plot = seriesItem.plot,
                            cast = seriesItem.cast,
                            director = seriesItem.director,
                            genre = seriesItem.genre.ifBlank { seriesItem.categoryName },
                            releaseDate = seriesItem.year,
                            rating = seriesItem.rating,
                            seasons = emptyList(),
                            quality = seriesItem.quality.ifBlank { seriesItem.resolution },
                            tmdbId = manualBinding?.tmdbId ?: seriesItem.tmdbId
                        )
                    }
                }

                // Merge any valid persisted metadata from Room and sanitize generic/placeholder plot
                val baseValidPlot = TmdbMetadataMatcher.sanitizeSynopsis(baseDetails.plot, seriesItem.name)
                val itemValidPlot = TmdbMetadataMatcher.sanitizeSynopsis(seriesItem.plot, seriesItem.name)
                val persistedValidPlot = TmdbMetadataMatcher.sanitizeSynopsis(persisted?.plot, seriesItem.name)
                val effectiveTmdbId = manualBinding?.tmdbId
                    ?: baseDetails.tmdbId.takeIf { it > 0 }
                    ?: seriesItem.tmdbId.takeIf { it > 0 }
                    ?: (persisted?.tmdbId ?: 0)

                val coverForCheck = baseDetails.cover.ifBlank { seriesItem.streamIcon }
                val validBackdrop = listOf(baseDetails.backdrop, seriesItem.backdrop, persisted?.backdrop.orEmpty())
                    .firstOrNull { TmdbMetadataMatcher.isValidBackdropUrl(it, coverForCheck) }
                    ?.let { TmdbMetadataMatcher.normalizeTmdbImageUrl(it, size = "w1280") }
                    .orEmpty()

                var sanitizedBase = baseDetails.copy(
                    plot = baseValidPlot.ifEmpty { itemValidPlot.ifEmpty { persistedValidPlot } },
                    cast = baseDetails.cast.ifBlank { seriesItem.cast.ifBlank { persisted?.cast.orEmpty() } },
                    director = baseDetails.director.ifBlank { seriesItem.director.ifBlank { persisted?.director.orEmpty() } },
                    genre = TmdbMetadataMatcher.localizeGenres(
                        baseDetails.genre.ifBlank { seriesItem.genre.ifBlank { persisted?.genre.orEmpty() } },
                        preferArabic = preferArabic
                    ),
                    backdrop = validBackdrop,
                    tmdbId = effectiveTmdbId
                )

                // Immediately apply cached rich metadata + cached localized season episodes before any network round-trip
                if (cachedRich != null) {
                    val cachedSeasonEps = LinkedHashMap<Int, List<com.example.data.api.TmdbEpisodeMetadata>>()
                    if (cachedRich.tmdbId > 0) {
                        val sNums = sanitizedBase.seasons.map { it.seasonNumber }.ifEmpty { cachedRich.seasons.map { it.seasonNumber } }.distinct().take(6)
                        for (sNum in sNums) {
                            tmdbCacheStore.getSeasonEpisodes(cachedRich.tmdbId, sNum, preferArabic)?.let {
                                cachedSeasonEps[sNum] = it
                            }
                        }
                    }
                    val cachedMatchesDesiredLang = cachedExactRich != null
                    sanitizedBase = TmdbProxyClient.enrichSeriesDetails(
                        details = sanitizedBase,
                        metadata = cachedRich,
                        seasonEpisodesMap = cachedSeasonEps,
                        overrideValidExistingPlot = manualBinding != null ||
                            cachedMatchesDesiredLang ||
                            !TmdbMetadataMatcher.isValidSynopsis(sanitizedBase.plot, sanitizedBase.name),
                        preferArabic = preferArabic
                    )
                }

                _currentSeriesDetails.value = sanitizedBase
                if (sanitizedBase.quality.isNotBlank()) {
                    applyDetectedQualityToItem(seriesItem.id, sanitizedBase.quality)
                }

                val hasValidIptvPlot = TmdbMetadataMatcher.isValidSynopsis(sanitizedBase.plot, sanitizedBase.name)
                val wantsArabicUpgrade = preferArabic && hasValidIptvPlot && !TmdbMetadataMatcher.containsArabicScript(sanitizedBase.plot)
                val wantsEnglishSwitch = !preferArabic && hasValidIptvPlot && TmdbMetadataMatcher.containsArabicScript(sanitizedBase.plot)
                val hasRealBackdrop = TmdbMetadataMatcher.isValidBackdropUrl(sanitizedBase.backdrop, sanitizedBase.cover.ifBlank { seriesItem.streamIcon })
                val hasCompleteRichCache = cachedExactRich != null &&
                    !tmdbCacheStore.isStale(cachedExactRich) &&
                    tmdbCacheStore.isRichMetadataComplete(cachedExactRich)
                val needsTmdb = forceRefresh ||
                    !hasValidIptvPlot ||
                    (wantsArabicUpgrade && !hasCompleteRichCache) ||
                    (wantsEnglishSwitch && !hasCompleteRichCache) ||
                    !hasRealBackdrop ||
                    sanitizedBase.cast.isBlank() ||
                    sanitizedBase.director.isBlank() ||
                    !hasCompleteRichCache

                var finalDetails = sanitizedBase
                if (needsTmdb && (forceRefresh || !tmdbCacheStore.shouldSkipAfterRecentFailure(seriesItem.id, preferArabic))) {
                    withContext(Dispatchers.IO) {
                        val tvMeta = fetchAccurateTmdbMetadata(
                            rawTitle = seriesItem.name,
                            year = seriesItem.year.ifBlank { sanitizedBase.releaseDate.take(4) },
                            isSeries = true,
                            tmdbId = effectiveTmdbId,
                            preferArabic = preferArabic
                        )
                        if (tvMeta != null) {
                            val stamped = tvMeta.copy(
                                cachedAtMs = System.currentTimeMillis(),
                                isManualMatch = manualBinding != null
                            )
                            tmdbCacheStore.putForMediaItem(
                                mediaItemId = seriesItem.id,
                                isSeries = true,
                                preferArabic = preferArabic,
                                metadata = stamped
                            )
                            refreshDiagnosticsCacheCounts()
                            if (_selectedSeriesForDetails.value?.id == seriesItem.id) {
                                _selectedSeriesRichMetadata.value = stamped
                            }

                            val savedApiKey = tmdbSessionStore.getSavedApiKey().trim()
                            val proxyUrl = canonicalTmdbProxyUrl(_userSettings.value.tmdbProxyUrl)
                            val seasonEpMap = LinkedHashMap<Int, List<com.example.data.api.TmdbEpisodeMetadata>>()
                            if (stamped.tmdbId > 0) {
                                val seasonNumsToFetch = if (sanitizedBase.seasons.isNotEmpty()) {
                                    sanitizedBase.seasons.map { it.seasonNumber }.distinct().take(6)
                                } else {
                                    stamped.seasons.map { it.seasonNumber }.distinct().take(5)
                                }
                                for (sNum in seasonNumsToFetch) {
                                    val cachedEps = if (!forceRefresh) {
                                        tmdbCacheStore.getSeasonEpisodes(stamped.tmdbId, sNum, preferArabic)
                                    } else {
                                        null
                                    }
                                    val eps = if (!cachedEps.isNullOrEmpty()) {
                                        cachedEps
                                    } else {
                                        val fetchedEps = if (savedApiKey.isNotEmpty()) {
                                            tmdbDirectClient.fetchTvSeasonEpisodesLocalized(savedApiKey, stamped.tmdbId, sNum, preferArabic)
                                                .ifEmpty {
                                                    if (proxyUrl.isNotBlank()) tmdbProxyClient.fetchTvSeasonEpisodesLocalized(proxyUrl, stamped.tmdbId, sNum, preferArabic) else emptyList()
                                                }
                                        } else if (proxyUrl.isNotBlank()) {
                                            tmdbProxyClient.fetchTvSeasonEpisodesLocalized(proxyUrl, stamped.tmdbId, sNum, preferArabic)
                                        } else {
                                            emptyList()
                                        }
                                        if (fetchedEps.isNotEmpty()) {
                                            tmdbCacheStore.putSeasonEpisodes(stamped.tmdbId, sNum, preferArabic, fetchedEps)
                                        }
                                        fetchedEps
                                    }
                                    if (eps.isNotEmpty()) {
                                        seasonEpMap[sNum] = eps
                                    }
                                }
                            }

                            val tmdbHasValidOverview = TmdbMetadataMatcher.isValidSynopsis(stamped.overview, sanitizedBase.name)
                            val tmdbHasArabicOverview = TmdbMetadataMatcher.containsArabicScript(stamped.overview) && tmdbHasValidOverview
                            val tmdbHasEnglishOverview = !TmdbMetadataMatcher.containsArabicScript(stamped.overview) && tmdbHasValidOverview
                            val overridePlot = manualBinding != null ||
                                !hasValidIptvPlot ||
                                (wantsArabicUpgrade && tmdbHasArabicOverview) ||
                                (wantsEnglishSwitch && tmdbHasEnglishOverview)

                            finalDetails = TmdbProxyClient.enrichSeriesDetails(
                                details = sanitizedBase,
                                metadata = stamped,
                                seasonEpisodesMap = seasonEpMap,
                                overrideValidExistingPlot = overridePlot,
                                preferArabic = preferArabic
                            )
                            _currentSeriesDetails.value = finalDetails
                            if (m3uSeriesDetailsMap.containsKey(seriesItem.streamId)) {
                                m3uSeriesDetailsMap[seriesItem.streamId] = finalDetails
                            }

                            val enrichedItem = TmdbProxyClient.enrichMediaItem(
                                item = seriesItem.copy(
                                    plot = sanitizedBase.plot,
                                    tmdbId = effectiveTmdbId
                                ),
                                tmdb = stamped,
                                overrideValidExistingPlot = overridePlot,
                                preferArabic = preferArabic
                            )
                            enrichedMediaDetailsCache["${seriesItem.id}:$langKey"] = enrichedItem
                            if (_selectedSeriesForDetails.value?.id == seriesItem.id) {
                                _selectedSeriesForDetails.value = enrichedItem
                            }
                            repository.updateTmdbMetadata(enrichedItem)
                            applyEnrichedTmdbItemToState(enrichedItem)
                        } else {
                            tmdbCacheStore.markLookupFailure(seriesItem.id, preferArabic)
                        }
                    }
                } else {
                    // Hydrate localized season episodes on demand even when show-level metadata is already cached
                    if (cachedExactRich != null && cachedExactRich.tmdbId > 0) {
                        val seasonNums = finalDetails.seasons.map { it.seasonNumber }.distinct().take(6)
                        val missingSeasons = seasonNums.filter { tmdbCacheStore.getSeasonEpisodes(cachedExactRich.tmdbId, it, preferArabic).isNullOrEmpty() }
                        if (missingSeasons.isNotEmpty()) {
                            withContext(Dispatchers.IO) {
                                val savedApiKey = tmdbSessionStore.getSavedApiKey().trim()
                                val proxyUrl = canonicalTmdbProxyUrl(_userSettings.value.tmdbProxyUrl)
                                val epMap = LinkedHashMap<Int, List<com.example.data.api.TmdbEpisodeMetadata>>()
                                for (sNum in seasonNums) {
                                    val cachedEps = tmdbCacheStore.getSeasonEpisodes(cachedExactRich.tmdbId, sNum, preferArabic)
                                    val eps = if (!cachedEps.isNullOrEmpty()) {
                                        cachedEps
                                    } else {
                                        val fetched = if (savedApiKey.isNotEmpty()) {
                                            tmdbDirectClient.fetchTvSeasonEpisodesLocalized(savedApiKey, cachedExactRich.tmdbId, sNum, preferArabic)
                                                .ifEmpty {
                                                    if (proxyUrl.isNotBlank()) tmdbProxyClient.fetchTvSeasonEpisodesLocalized(proxyUrl, cachedExactRich.tmdbId, sNum, preferArabic) else emptyList()
                                                }
                                        } else if (proxyUrl.isNotBlank()) {
                                            tmdbProxyClient.fetchTvSeasonEpisodesLocalized(proxyUrl, cachedExactRich.tmdbId, sNum, preferArabic)
                                        } else {
                                            emptyList()
                                        }
                                        if (fetched.isNotEmpty()) {
                                            tmdbCacheStore.putSeasonEpisodes(cachedExactRich.tmdbId, sNum, preferArabic, fetched)
                                        }
                                        fetched
                                    }
                                    if (eps.isNotEmpty()) epMap[sNum] = eps
                                }
                                if (epMap.isNotEmpty()) {
                                    finalDetails = TmdbProxyClient.enrichSeriesDetails(
                                        details = finalDetails,
                                        metadata = cachedExactRich,
                                        seasonEpisodesMap = epMap,
                                        overrideValidExistingPlot = true,
                                        preferArabic = preferArabic
                                    )
                                    _currentSeriesDetails.value = finalDetails
                                }
                            }
                        }
                    }

                    val syncedItem = seriesItem.copy(
                        plot = finalDetails.plot,
                        cast = finalDetails.cast.ifBlank { seriesItem.cast },
                        director = finalDetails.director.ifBlank { seriesItem.director },
                        genre = finalDetails.genre.ifBlank { seriesItem.genre },
                        backdrop = finalDetails.backdrop.ifBlank { seriesItem.backdrop },
                        tmdbId = finalDetails.tmdbId
                    )
                    enrichedMediaDetailsCache["${seriesItem.id}:$langKey"] = syncedItem
                    withContext(Dispatchers.IO) {
                        repository.updateTmdbMetadata(syncedItem)
                    }
                    applyEnrichedTmdbItemToState(syncedItem)
                }

                enrichedSeriesDetailsCache[detailsCacheKey] = finalDetails
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                if (_currentSeriesDetails.value == null && _activeAccount.value?.isDemo == true) {
                    _currentSeriesDetails.value = SampleData.getSampleSeriesDetails(seriesItem.streamId)
                }
            } finally {
                _isSeriesDetailsLoading.value = false
            }
        }
    }

    // --- Manual TMDb Matching (When automatic match fails or picks the wrong item) ---

    fun searchManualTmdbMatches(query: String, year: String = "", isSeries: Boolean) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _manualMatchCandidates.value = emptyList()
            _isManualMatchSearching.value = false
            return
        }
        manualMatchJob?.cancel()
        manualMatchJob = viewModelScope.launch {
            _isManualMatchSearching.value = true
            try {
                val preferArabic = isArabicMetadataPreferred()
                val savedApiKey = tmdbSessionStore.getSavedApiKey().trim()
                val proxyUrl = canonicalTmdbProxyUrl(_userSettings.value.tmdbProxyUrl)

                val results = withContext(Dispatchers.IO) {
                    val fromDirect = if (savedApiKey.isNotEmpty()) {
                        tmdbDirectClient.searchCandidates(
                            apiKey = savedApiKey,
                            rawQuery = trimmed,
                            year = year,
                            isSeries = isSeries,
                            preferArabic = preferArabic
                        )
                    } else {
                        emptyList()
                    }
                    if (fromDirect.isNotEmpty()) {
                        tmdbCacheStore.recordApiSuccess(if (isSeries) "direct/search/tv" else "direct/search/movie")
                        fromDirect
                    } else if (proxyUrl.isNotBlank()) {
                        val fromProxy = tmdbProxyClient.searchCandidates(
                            rawProxyUrl = proxyUrl,
                            rawQuery = trimmed,
                            year = year,
                            isSeries = isSeries,
                            preferArabic = preferArabic
                        )
                        if (fromProxy.isNotEmpty()) {
                            tmdbCacheStore.recordApiSuccess(if (isSeries) "proxy/search/tv" else "proxy/search/movie")
                        }
                        fromProxy
                    } else {
                        emptyList()
                    }
                }
                _manualMatchCandidates.value = results
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tmdbCacheStore.recordApiError(e.localizedMessage ?: "Search failed")
                _manualMatchCandidates.value = emptyList()
            } finally {
                _isManualMatchSearching.value = false
            }
        }
    }

    fun clearManualMatchState() {
        manualMatchJob?.cancel()
        _manualMatchCandidates.value = emptyList()
        _isManualMatchSearching.value = false
    }

    /**
     * Permanently binds [mediaItem] to a user-selected TMDb [candidate], fetches full rich
     * metadata by exact TMDb ID, updates persistent cache + Room + UI immediately.
     */
    fun applyManualTmdbMatch(mediaItem: MediaItem, candidate: TmdbSearchCandidate) {
        val preferArabic = isArabicMetadataPreferred()
        val isSeries = mediaItem.type == MediaType.SERIES

        viewModelScope.launch {
            if (isSeries) _isSeriesDetailsLoading.value = true else _isMovieDetailsLoading.value = true
            try {
                withContext(Dispatchers.IO) {
                    tmdbCacheStore.saveManualBinding(
                        ManualTmdbBinding(
                            mediaItemId = mediaItem.id,
                            tmdbId = candidate.tmdbId,
                            isSeries = isSeries,
                            matchedTitle = candidate.title,
                            boundAtMs = System.currentTimeMillis()
                        )
                    )
                    // Immediately seed candidate artwork & overview so UI reflects the manual match
                    // instantaneously even if proxy detail routes are unavailable or offline.
                    val seededMeta = TmdbMediaMetadata(
                        tmdbId = candidate.tmdbId,
                        title = candidate.title,
                        originalTitle = candidate.originalTitle,
                        overview = TmdbMetadataMatcher.sanitizeSynopsis(candidate.overview, candidate.title),
                        posterUrl = candidate.posterUrl,
                        backdropUrl = candidate.backdropUrl,
                        rating = candidate.rating,
                        releaseYear = candidate.releaseYear,
                        releaseDate = candidate.releaseDate,
                        genres = TmdbMetadataMatcher.mapTmdbGenreIds(candidate.genreIds, preferArabic = preferArabic),
                        cast = "",
                        director = "",
                        cachedAtMs = 0L, // Mark stale so rich details still fetch if available
                        isManualMatch = true
                    )
                    tmdbCacheStore.putForMediaItem(
                        mediaItemId = mediaItem.id,
                        isSeries = isSeries,
                        preferArabic = preferArabic,
                        metadata = seededMeta,
                        nowMs = 0L
                    )
                    enrichedMediaDetailsCache.remove("${mediaItem.id}:${TmdbMetadataMatcher.TMDB_LANG_ENGLISH}")
                    enrichedMediaDetailsCache.remove("${mediaItem.id}:${TmdbMetadataMatcher.TMDB_LANG_ARABIC}")
                    enrichedMediaDetailsCache.remove("${mediaItem.id}:en")
                    enrichedMediaDetailsCache.remove("${mediaItem.id}:ar")
                    if (isSeries) {
                        enrichedSeriesDetailsCache.remove("${mediaItem.streamId}:${TmdbMetadataMatcher.TMDB_LANG_ENGLISH}")
                        enrichedSeriesDetailsCache.remove("${mediaItem.streamId}:${TmdbMetadataMatcher.TMDB_LANG_ARABIC}")
                        enrichedSeriesDetailsCache.remove("${mediaItem.streamId}:en")
                        enrichedSeriesDetailsCache.remove("${mediaItem.streamId}:ar")
                    }
                    tmdbDirectClient.clearMemoryCaches()
                    tmdbProxyClient.clearMemoryCaches()
                }

                val boundItem = mediaItem.copy(
                    tmdbId = candidate.tmdbId,
                    backdrop = candidate.backdropUrl.ifBlank { mediaItem.backdrop },
                    plot = candidate.overview.ifBlank { mediaItem.plot },
                    rating = if (candidate.rating > 0.0) candidate.rating else mediaItem.rating,
                    year = candidate.releaseYear.ifBlank { mediaItem.year },
                    genre = TmdbMetadataMatcher.mapTmdbGenreIds(candidate.genreIds, preferArabic = preferArabic).ifBlank { mediaItem.genre }
                )
                if (isSeries) {
                    loadSeriesDetails(boundItem, forceRefresh = true)
                } else {
                    val enriched = getOrFetchEnrichedMediaDetails(boundItem, forceRefresh = true)
                    if (_selectedMovieForDetails.value?.id == mediaItem.id) {
                        _selectedMovieForDetails.value = enriched
                        _selectedMovieRichMetadata.value = tmdbCacheStore.getForMediaItem(
                            mediaItemId = mediaItem.id,
                            tmdbId = candidate.tmdbId,
                            isSeries = false,
                            preferArabic = preferArabic
                        )
                    }
                }
                refreshDiagnosticsCacheCounts()
            } finally {
                if (!isSeries) _isMovieDetailsLoading.value = false
            }
        }
    }

    // --- Settings -> TMDb -> Connection Diagnostics & Manual Metadata Refresh ---

    fun refreshDiagnosticsCacheCounts() {
        val current = _tmdbDiagnosticsReport.value
        val accountState = _tmdbAccountState.value
        val accountCheck = buildAccountAuthDiagnosticCheck(accountState)
        _tmdbDiagnosticsReport.value = current.copy(
            accountAuth = accountCheck,
            lastSuccessfulRequestMs = tmdbCacheStore.getLastSuccessfulRequestMs(),
            lastErrorMessage = tmdbCacheStore.getLastSanitizedError(),
            cachedMetadataCount = tmdbCacheStore.getCachedEntryCount(),
            manualMatchCount = tmdbCacheStore.getManualMatchCount()
        )
    }

    private fun buildAccountAuthDiagnosticCheck(state: TmdbAccountUiState): TmdbDiagnosticCheck {
        return when {
            state.isConnected && state.username.isNotBlank() -> TmdbDiagnosticCheck(
                status = TmdbDiagnosticStatus.PASSED,
                summary = "Signed in as @${state.username} (Favorites/Watchlist sync)"
            )
            state.isConnected -> TmdbDiagnosticCheck(
                status = TmdbDiagnosticStatus.PASSED,
                summary = "TMDb account signed in"
            )
            else -> TmdbDiagnosticCheck(
                status = TmdbDiagnosticStatus.NOT_CONFIGURED,
                summary = "Not signed in (metadata works via Proxy/API key)"
            )
        }
    }

    /**
     * Runs a full live Connection Diagnostics suite covering:
     * 1. Direct TMDb API v3/v4 authorization (if user API key is saved)
     * 2. Cloudflare Worker Proxy connectivity (/api/tmdb/test)
     * 3. User account authentication status (distinct from metadata API auth)
     * 4. Movie search test ("Inception", 2010)
     * 5. TV series search test ("Breaking Bad", 2008)
     * 6. TMDb Image CDN test (image.tmdb.org)
     * All errors are strictly sanitized so API keys/tokens are never exposed.
     */
    fun runTmdbConnectionDiagnostics() {
        diagnosticsJob?.cancel()
        diagnosticsJob = viewModelScope.launch {
            val accountCheck = buildAccountAuthDiagnosticCheck(_tmdbAccountState.value)
            val runningCheck = TmdbDiagnosticCheck(status = TmdbDiagnosticStatus.RUNNING, summary = "Testing...")
            _tmdbDiagnosticsReport.value = _tmdbDiagnosticsReport.value.copy(
                isRunning = true,
                apiConnection = runningCheck,
                proxyConnection = runningCheck,
                accountAuth = accountCheck,
                movieSearchTest = runningCheck,
                tvSearchTest = runningCheck,
                imageLoadingTest = runningCheck,
                refreshFeedbackMessage = ""
            )

            val savedApiKey = tmdbSessionStore.getSavedApiKey().trim()
            val proxyUrl = canonicalTmdbProxyUrl(_userSettings.value.tmdbProxyUrl)
            var firstError = ""

            // 1. Direct API Check
            val directCheck = withContext(Dispatchers.IO) {
                tmdbDirectClient.testApiConnectivity(savedApiKey)
            }
            if (directCheck.status == TmdbDiagnosticStatus.PASSED) {
                tmdbCacheStore.recordApiSuccess("direct/configuration")
            } else if (directCheck.status == TmdbDiagnosticStatus.FAILED && firstError.isBlank()) {
                firstError = directCheck.summary
            }

            // 2. Proxy Check
            val proxyStart = System.currentTimeMillis()
            val proxyHealth = withContext(Dispatchers.IO) {
                tmdbProxyClient.verifyConnection(proxyUrl)
            }
            val proxyLatency = (System.currentTimeMillis() - proxyStart).coerceAtLeast(1L)
            val proxyCheck = if (proxyHealth.isConnected) {
                tmdbCacheStore.recordApiSuccess("proxy/test")
                TmdbDiagnosticCheck(
                    status = TmdbDiagnosticStatus.PASSED,
                    summary = "Proxy online & TMDb upstream OK (${proxyLatency}ms)",
                    httpCode = proxyHealth.httpStatusCode,
                    latencyMs = proxyLatency
                )
            } else {
                val err = TmdbErrorSanitizer.sanitize(proxyHealth.message.ifBlank { "HTTP ${proxyHealth.httpStatusCode}" })
                if (firstError.isBlank()) firstError = err
                TmdbDiagnosticCheck(
                    status = TmdbDiagnosticStatus.FAILED,
                    summary = err,
                    httpCode = proxyHealth.httpStatusCode,
                    latencyMs = proxyLatency
                )
            }

            // 3. Movie Search Test ("Inception", 2010)
            val movieStart = System.currentTimeMillis()
            val movieMeta = withContext(Dispatchers.IO) {
                fetchAccurateTmdbMetadata(
                    rawTitle = "Inception",
                    year = "2010",
                    isSeries = false,
                    tmdbId = 0,
                    preferArabic = false
                )
            }
            val movieLatency = (System.currentTimeMillis() - movieStart).coerceAtLeast(1L)
            val movieCheck = if (movieMeta != null && movieMeta.tmdbId > 0) {
                val hasBackdrop = movieMeta.backdropUrl.isNotBlank()
                val hasPlot = TmdbMetadataMatcher.isValidSynopsis(movieMeta.overview, movieMeta.title)
                val hasCredits = movieMeta.castMembers.isNotEmpty() || movieMeta.director.isNotBlank()
                val detailNote = if (hasCredits) "" else " • Search-only proxy (redeploy worker.js for cast/trailers)"
                TmdbDiagnosticCheck(
                    status = if (hasBackdrop && hasPlot) TmdbDiagnosticStatus.PASSED else TmdbDiagnosticStatus.WARNING,
                    summary = "${movieMeta.title} (${movieMeta.releaseYear}) • ID ${movieMeta.tmdbId} (${movieLatency}ms)$detailNote",
                    httpCode = 200,
                    latencyMs = movieLatency
                )
            } else {
                val err = "Movie search returned no verified match (${movieLatency}ms)"
                if (firstError.isBlank()) firstError = err
                TmdbDiagnosticCheck(
                    status = TmdbDiagnosticStatus.FAILED,
                    summary = err,
                    latencyMs = movieLatency
                )
            }

            // 4. TV Series Search Test ("Breaking Bad", 2008)
            val tvStart = System.currentTimeMillis()
            val tvMeta = withContext(Dispatchers.IO) {
                fetchAccurateTmdbMetadata(
                    rawTitle = "Breaking Bad",
                    year = "2008",
                    isSeries = true,
                    tmdbId = 0,
                    preferArabic = false
                )
            }
            val tvLatency = (System.currentTimeMillis() - tvStart).coerceAtLeast(1L)
            val tvCheck = if (tvMeta != null && tvMeta.tmdbId > 0) {
                val hasBackdrop = tvMeta.backdropUrl.isNotBlank()
                val hasPlot = TmdbMetadataMatcher.isValidSynopsis(tvMeta.overview, tvMeta.title)
                TmdbDiagnosticCheck(
                    status = if (hasBackdrop && hasPlot) TmdbDiagnosticStatus.PASSED else TmdbDiagnosticStatus.WARNING,
                    summary = "${tvMeta.title} (${tvMeta.releaseYear}) • ID ${tvMeta.tmdbId} (${tvLatency}ms)",
                    httpCode = 200,
                    latencyMs = tvLatency
                )
            } else {
                val err = "TV series search returned no verified match (${tvLatency}ms)"
                if (firstError.isBlank()) firstError = err
                TmdbDiagnosticCheck(
                    status = TmdbDiagnosticStatus.FAILED,
                    summary = err,
                    latencyMs = tvLatency
                )
            }

            // 5. Image Loading Test (verify actual TMDb backdrop/poster CDN URL)
            val testImageUrl = listOf(
                movieMeta?.backdropUrl.orEmpty(),
                tvMeta?.backdropUrl.orEmpty(),
                movieMeta?.posterUrl.orEmpty(),
                "https://image.tmdb.org/t/p/w500/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg"
            ).first { it.isNotBlank() }

            val imageCheck = withContext(Dispatchers.IO) {
                tmdbProxyClient.verifyImageUrl(testImageUrl)
            }
            if (imageCheck.status == TmdbDiagnosticStatus.FAILED && firstError.isBlank()) {
                firstError = imageCheck.summary
            }

            val sanitizedError = TmdbErrorSanitizer.sanitize(firstError)
            if (sanitizedError.isNotBlank()) {
                tmdbCacheStore.recordApiError(sanitizedError)
            } else {
                tmdbCacheStore.clearLastApiError()
            }

            _tmdbDiagnosticsReport.value = TmdbDiagnosticsReport(
                isRunning = false,
                apiConnection = directCheck,
                proxyConnection = proxyCheck,
                accountAuth = buildAccountAuthDiagnosticCheck(_tmdbAccountState.value),
                movieSearchTest = movieCheck,
                tvSearchTest = tvCheck,
                imageLoadingTest = imageCheck,
                lastSuccessfulRequestMs = tmdbCacheStore.getLastSuccessfulRequestMs(),
                lastErrorMessage = sanitizedError,
                checkedAtMs = System.currentTimeMillis(),
                cachedMetadataCount = tmdbCacheStore.getCachedEntryCount(),
                manualMatchCount = tmdbCacheStore.getManualMatchCount()
            )
        }
    }

    /**
     * Marks cached TMDb metadata as stale (preserving cached values on failure), clears client
     * negative backoffs, and refreshes any currently open Movie/Series or top incomplete items
     * without triggering a full IPTV library sync.
     */
    fun refreshTmdbMetadataManual() {
        viewModelScope.launch {
            val isUiArabic = SubscriptionInfoHelper.isArabicLanguage(_userSettings.value.language)
            val preferArabicMeta = isArabicMetadataPreferred()
            _tmdbDiagnosticsReport.value = _tmdbDiagnosticsReport.value.copy(
                isRefreshingMetadata = true,
                refreshFeedbackMessage = if (isUiArabic) "جاري تحديث بيانات TMDb..." else "Refreshing TMDb metadata..."
            )
            try {
                var refreshedCount = 0
                withContext(Dispatchers.IO) {
                    tmdbCacheStore.markAllEntriesStaleForRefresh()
                    tmdbDirectClient.clearMemoryCaches()
                    tmdbProxyClient.clearMemoryCaches()
                    enrichedMediaDetailsCache.clear()
                    enrichedSeriesDetailsCache.clear()

                    // Refresh up to 10 visible movies/series needing backdrop, synopsis, or localized metadata
                    val candidates = (_movies.value.take(12) + _series.value.take(8))
                        .filter {
                            !TmdbMetadataMatcher.isValidBackdropUrl(it.backdrop, it.streamIcon) ||
                                !TmdbMetadataMatcher.isValidSynopsis(it.plot, it.name) ||
                                (preferArabicMeta && !TmdbMetadataMatcher.containsArabicScript(it.plot)) ||
                                (!preferArabicMeta && TmdbMetadataMatcher.containsArabicScript(it.plot)) ||
                                it.cast.isBlank()
                        }
                        .take(8)

                    for (item in candidates) {
                        val updated = getOrFetchEnrichedMediaDetails(item, forceRefresh = true)
                        if (updated.tmdbId > 0 || updated.backdrop.isNotBlank() || updated.plot.isNotBlank()) {
                            refreshedCount++
                        }
                    }
                }

                // Also refresh currently open Details screen if one is active
                _selectedMovieForDetails.value?.let { openMovie ->
                    val refreshed = getOrFetchEnrichedMediaDetails(openMovie, forceRefresh = true)
                    if (_selectedMovieForDetails.value?.id == openMovie.id) {
                        _selectedMovieForDetails.value = refreshed
                        _selectedMovieRichMetadata.value = tmdbCacheStore.getForMediaItem(
                            mediaItemId = openMovie.id,
                            tmdbId = refreshed.tmdbId,
                            isSeries = false,
                            preferArabic = preferArabicMeta
                        )
                    }
                }
                _selectedSeriesForDetails.value?.let { openSeries ->
                    loadSeriesDetails(openSeries, forceRefresh = true)
                }

                val feedback = if (isUiArabic) {
                    if (refreshedCount > 0) "تم تحديث بيانات $refreshedCount عنصر من TMDb بنجاح"
                    else "تم تجهيز ذاكرة TMDb للتحديث عند فتح أي فيلم أو مسلسل"
                } else {
                    if (refreshedCount > 0) "Refreshed metadata for $refreshedCount item(s) from TMDb"
                    else "TMDb metadata cache refreshed — open any title to load latest details"
                }

                _tmdbDiagnosticsReport.value = _tmdbDiagnosticsReport.value.copy(
                    isRefreshingMetadata = false,
                    refreshFeedbackMessage = feedback,
                    lastSuccessfulRequestMs = tmdbCacheStore.getLastSuccessfulRequestMs(),
                    cachedMetadataCount = tmdbCacheStore.getCachedEntryCount(),
                    manualMatchCount = tmdbCacheStore.getManualMatchCount()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = TmdbErrorSanitizer.sanitize(e.localizedMessage ?: "Refresh failed")
                tmdbCacheStore.recordApiError(err)
                _tmdbDiagnosticsReport.value = _tmdbDiagnosticsReport.value.copy(
                    isRefreshingMetadata = false,
                    lastErrorMessage = err,
                    refreshFeedbackMessage = err
                )
            }
        }
    }

    // --- Feature 3: Persistent Live TV Channel Selection & Playback ---
    /**
     * Switches or starts Live TV playback inside the persistent Live TV player while keeping
     * the channel list visible, preserving the selected category and scroll position, and
     * reusing the single Media3 ExoPlayer instance.
     */
    fun selectLiveChannel(channel: MediaItem, enterFullscreen: Boolean = _isLiveFullscreen.value) {
        _nowPlayingItem.value = null
        _nowPlayingEpisode.value = null
        _activeLiveChannel.value = channel
        _isLiveFullscreen.value = enterFullscreen
        _isLiveMiniPlayerActive.value = false

        repository.recordRecentLiveChannel(channel)
        _recentLiveChannels.value = repository.getRecentLiveChannels()
        _lastWatchedLiveChannel.value = channel

        val notificationMeta = PlaybackNotificationMetadata(
            mediaId = channel.id,
            title = channel.name,
            subtitle = channel.plot.ifBlank {
                "KRAS TV • ${channel.categoryName.ifBlank { "Live Broadcast" }}"
            },
            artworkUrl = channel.streamIcon.ifBlank { channel.backdrop },
            isLive = true
        )
        KrasMediaPlaybackService.updateChannelNavigationSupport(
            canSkipPrevNext = true,
            onPrevious = { switchLiveChannelByOffset(-1) },
            onNext = { switchLiveChannelByOffset(1) },
            onStop = { stopLiveChannelPlayback() }
        )

        val urlToPlay = channel.streamUrl.trim()
        playerWrapper.play(
            url = urlToPlay,
            startPositionMs = 0L,
            metadata = notificationMeta,
            keepLastFrameUntilReady = true
        )
        startPositionTracker()
    }

    fun switchLiveChannelByOffset(delta: Int) {
        val list = _liveStreams.value.ifEmpty { SampleData.sampleLiveStreams }
        if (list.isEmpty()) return
        val currentId = _activeLiveChannel.value?.id
        val curIdx = list.indexOfFirst { it.id == currentId }
        val targetIdx = if (curIdx < 0) 0 else (curIdx + delta + list.size) % list.size
        selectLiveChannel(list[targetIdx], _isLiveFullscreen.value)
    }

    fun setLiveFullscreen(
        fullscreen: Boolean,
        orientation: FullscreenOrientation = FullscreenOrientation.LANDSCAPE
    ) {
        _isLiveFullscreen.value = fullscreen
        if (fullscreen) {
            _liveFullscreenOrientation.value = orientation
            _isLiveMiniPlayerActive.value = false
        } else {
            _liveFullscreenOrientation.value = FullscreenOrientation.LANDSCAPE
        }
    }

    /**
     * Switches Live TV between Landscape (Horizontal Fullscreen) and Portrait (Vertical Fullscreen)
     * directly inside the player without leaving fullscreen or interrupting playback.
     */
    fun setLiveFullscreenOrientation(orientation: FullscreenOrientation) {
        _isLiveFullscreen.value = true
        _liveFullscreenOrientation.value = orientation
        _isLiveMiniPlayerActive.value = false
    }

    fun setLiveMiniPlayer(active: Boolean) {
        _isLiveMiniPlayerActive.value = active && _activeLiveChannel.value != null
        if (active) {
            _isLiveFullscreen.value = false
            _liveFullscreenOrientation.value = FullscreenOrientation.LANDSCAPE
        }
    }

    fun retryLiveChannel() {
        val ch = _activeLiveChannel.value ?: return
        if (ch.streamUrl.isNotBlank()) {
            playerWrapper.retry()
        } else {
            selectLiveChannel(ch, _isLiveFullscreen.value)
        }
    }

    fun stopLiveChannelPlayback() {
        progressTrackingJob?.cancel()
        KrasMediaPlaybackService.updateChannelNavigationSupport(
            canSkipPrevNext = false,
            onPrevious = null,
            onNext = null
        )
        playerWrapper.stop()
        _activeLiveChannel.value = null
        _isLiveFullscreen.value = false
        _liveFullscreenOrientation.value = FullscreenOrientation.LANDSCAPE
        _isLiveMiniPlayerActive.value = false
    }

    // --- Playback Control (Movies & Series Unified Fullscreen Player) ---
    fun startPlayback(mediaItem: MediaItem, episode: Episode? = null) {
        _isLiveMiniPlayerActive.value = false
        if (mediaItem.type == MediaType.LIVE && episode == null) {
            _activeLiveChannel.value = mediaItem
        } else {
            _activeLiveChannel.value = null
            _isLiveFullscreen.value = false
            KrasMediaPlaybackService.updateChannelNavigationSupport(
                canSkipPrevNext = false,
                onPrevious = null,
                onNext = null,
                onStop = { stopPlayback() }
            )
        }
        _nowPlayingItem.value = mediaItem
        _nowPlayingEpisode.value = episode

        fun buildMetadataFor(ep: Episode?): PlaybackNotificationMetadata {
            val isLive = mediaItem.type == MediaType.LIVE && ep == null
            return PlaybackNotificationMetadata(
                mediaId = ep?.id ?: mediaItem.id,
                title = ep?.title?.ifBlank { mediaItem.name } ?: mediaItem.name,
                subtitle = when {
                    ep != null -> "${mediaItem.name} • S${ep.seasonNumber}:E${ep.episodeNum}"
                    isLive -> "KRAS TV • ${mediaItem.categoryName.ifBlank { "Live Broadcast" }}"
                    else -> "KRAS TV • ${mediaItem.categoryName.ifBlank { "Cinema" }}"
                },
                artworkUrl = ep?.coverUrl?.ifBlank { mediaItem.streamIcon }
                    ?: mediaItem.streamIcon.ifBlank { mediaItem.backdrop },
                isLive = isLive
            )
        }

        val urlToPlay = (episode?.streamUrl?.takeIf { it.isNotBlank() } ?: mediaItem.streamUrl).trim()
        val previousProgress = _watchHistory.value.find { it.mediaId == (episode?.id ?: mediaItem.id) }
        val startPos = previousProgress?.positionMs ?: 0L

        if (urlToPlay.isNotBlank()) {
            playerWrapper.play(urlToPlay, startPos, metadata = buildMetadataFor(episode))
            startPositionTracker()
        } else if (mediaItem.type == MediaType.SERIES) {
            // Immediately enter the unified player in PREPARING state and resolve the episode URL in-place
            playerWrapper.setPreparingState()
            viewModelScope.launch {
                try {
                    val active = _activeAccount.value
                    val details = _currentSeriesDetails.value?.takeIf { it.seriesId == mediaItem.streamId }
                        ?: m3uSeriesDetailsMap[mediaItem.streamId]
                        ?: withContext(Dispatchers.IO) {
                            if (active != null && !active.isDemo && !M3uPlaylistParser.isM3uPlaylistUrl(active.serverUrl)) {
                                apiClient.getSeriesDetails(active, mediaItem.streamId)
                            } else {
                                SampleData.getSampleSeriesDetails(mediaItem.streamId)
                            }
                        }
                        ?: SampleData.getSampleSeriesDetails(mediaItem.streamId)

                    _currentSeriesDetails.value = details
                    val resolvedEpisode = episode?.let { ep ->
                        details.seasons.flatMap { it.episodes }.find {
                            it.id == ep.id || (it.seasonNumber == ep.seasonNumber && it.episodeNum == ep.episodeNum)
                        }
                    } ?: details.seasons.firstOrNull()?.episodes?.firstOrNull()

                    if (_nowPlayingItem.value?.id != mediaItem.id) return@launch

                    if (resolvedEpisode != null && resolvedEpisode.streamUrl.isNotBlank()) {
                        _nowPlayingEpisode.value = resolvedEpisode
                        val epProgress = _watchHistory.value.find { it.mediaId == resolvedEpisode.id }
                        playerWrapper.play(
                            url = resolvedEpisode.streamUrl,
                            startPositionMs = epProgress?.positionMs ?: startPos,
                            metadata = buildMetadataFor(resolvedEpisode)
                        )
                        startPositionTracker()
                    } else {
                        playerWrapper.failWithError("Unable to load episode stream URL.")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    if (_nowPlayingItem.value?.id == mediaItem.id) {
                        playerWrapper.failWithError("Failed to prepare series episode stream.")
                    }
                }
            }
        } else {
            playerWrapper.play(urlToPlay, startPos, metadata = buildMetadataFor(episode))
            startPositionTracker()
        }

        // Step 2b: For movies, also check get_vod_info in background for video width/height/codec/framerate
        // and enrich movie metadata from server-side TMDb proxy if configured
        val active = _activeAccount.value
        if (mediaItem.type == MediaType.MOVIE) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    if (active != null && !active.isDemo && !M3uPlaylistParser.isM3uPlaylistUrl(active.serverUrl)) {
                        val vodQuality = apiClient.getVodVideoInfo(
                            account = active,
                            vodId = mediaItem.streamId,
                            fallbackQuality = mediaItem.quality.ifBlank { mediaItem.resolution }
                        )
                        if (!vodQuality.isNullOrBlank() && playerWrapper.detectedQualityBadge.value.isBlank()) {
                            applyDetectedQualityToItem(mediaItem.id, vodQuality)
                        }
                    }
                    val settings = _userSettings.value
                    val preferArabic = isArabicMetadataPreferred(settings)
                    if (settings.tmdbAutoEnrichEnabled && (mediaItem.plot.isBlank() || mediaItem.tmdbId == 0)) {
                        val movieMeta = fetchAccurateTmdbMetadata(
                            rawTitle = mediaItem.name,
                            year = mediaItem.year,
                            isSeries = false,
                            tmdbId = mediaItem.tmdbId,
                            preferArabic = preferArabic
                        )
                        if (movieMeta != null) {
                            tmdbCacheStore.putForMediaItem(
                                mediaItemId = mediaItem.id,
                                isSeries = false,
                                preferArabic = preferArabic,
                                metadata = movieMeta
                            )
                            val enriched = TmdbProxyClient.enrichMediaItem(
                                item = mediaItem,
                                tmdb = movieMeta,
                                preferArabic = preferArabic
                            )
                            repository.updateTmdbMetadata(enriched)
                            applyEnrichedTmdbItemToState(enriched)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun retryPlayback() {
        val currentItem = _nowPlayingItem.value ?: _activeLiveChannel.value ?: return
        val currentEp = _nowPlayingEpisode.value
        val activeUrl = currentEp?.streamUrl?.ifBlank { currentItem.streamUrl } ?: currentItem.streamUrl
        if (activeUrl.isNotBlank()) {
            playerWrapper.retry()
        } else if (_nowPlayingItem.value != null) {
            startPlayback(currentItem, currentEp)
        } else {
            selectLiveChannel(currentItem, _isLiveFullscreen.value)
        }
    }

    private fun onRealPlaybackQualityDetected(realQuality: String) {
        val currentItem = _nowPlayingItem.value ?: _activeLiveChannel.value ?: return
        if (realQuality.isBlank()) return

        // Merge with existing extra tags (e.g. HEVC, 5.1) if realQuality only had height/FPS from onVideoSizeChanged
        val newParsed = QualityDetector.parseStoredQuality(realQuality)
        val existingParsed = QualityDetector.parseStoredQuality(currentItem.quality.ifBlank { currentItem.resolution })

        val mergedExtras = mutableListOf<String>()
        if ("HEVC" in newParsed.extraTags || "HEVC" in existingParsed.extraTags) {
            mergedExtras.add("HEVC")
        }
        val fpsTag = newParsed.extraTags.firstOrNull { it.endsWith("FPS") }
            ?: existingParsed.extraTags.firstOrNull { it.endsWith("FPS") }
        if (fpsTag != null) {
            mergedExtras.add(fpsTag)
        }
        if ("5.1" in newParsed.extraTags || "5.1" in existingParsed.extraTags) {
            mergedExtras.add("5.1")
        }

        val finalQuality = QualityDetector.ParsedQuality(
            badge = newParsed.badge.ifBlank { existingParsed.badge },
            extraTags = mergedExtras
        ).toStorageString()

        if (finalQuality.isNotBlank()) {
            applyDetectedQualityToItem(currentItem.id, finalQuality)
        }
    }

    private fun applyDetectedQualityToItem(itemId: String, updatedQuality: String) {
        if (itemId.isBlank() || updatedQuality.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Save in Room (and favorites preference if present)
                repository.updateMediaQuality(itemId, updatedQuality)

                // Update in-memory StateFlows so the badge updates everywhere immediately
                _nowPlayingItem.value?.let { current ->
                    if (current.id == itemId) {
                        _nowPlayingItem.value = current.copy(quality = updatedQuality, resolution = updatedQuality)
                    }
                }
                _activeLiveChannel.value?.let { currentLive ->
                    if (currentLive.id == itemId) {
                        _activeLiveChannel.value = currentLive.copy(quality = updatedQuality, resolution = updatedQuality)
                    }
                }
                if (_liveStreams.value.any { it.id == itemId }) {
                    _liveStreams.value = _liveStreams.value.map {
                        if (it.id == itemId) it.copy(quality = updatedQuality, resolution = updatedQuality) else it
                    }
                }
                if (_movies.value.any { it.id == itemId }) {
                    _movies.value = _movies.value.map {
                        if (it.id == itemId) it.copy(quality = updatedQuality, resolution = updatedQuality) else it
                    }
                }
                if (_latestMoviesForCategory.value.any { it.id == itemId }) {
                    _latestMoviesForCategory.value = _latestMoviesForCategory.value.map {
                        if (it.id == itemId) it.copy(quality = updatedQuality, resolution = updatedQuality) else it
                    }
                }
                if (_series.value.any { it.id == itemId }) {
                    _series.value = _series.value.map {
                        if (it.id == itemId) it.copy(quality = updatedQuality, resolution = updatedQuality) else it
                    }
                }
                if (_latestSeriesForCategory.value.any { it.id == itemId }) {
                    _latestSeriesForCategory.value = _latestSeriesForCategory.value.map {
                        if (it.id == itemId) it.copy(quality = updatedQuality, resolution = updatedQuality) else it
                    }
                }
                if (_searchResults.value.any { it.id == itemId }) {
                    _searchResults.value = _searchResults.value.map {
                        if (it.id == itemId) it.copy(quality = updatedQuality, resolution = updatedQuality) else it
                    }
                }
                if (_favorites.value.any { it.mediaId == itemId }) {
                    _favorites.value = repository.getFavorites(_userSettings.value.liveChannelOrder)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startPositionTracker() {
        progressTrackingJob?.cancel()
        progressTrackingJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                try {
                    playerWrapper.updatePosition()
                    val item = _nowPlayingItem.value ?: continue
                    val ep = _nowPlayingEpisode.value
                    val curPos = playerWrapper.currentPosition.value
                    val dur = playerWrapper.duration.value

                    if (dur > 0 && curPos > 5000) {
                        val progress = WatchProgress(
                            id = ep?.id ?: item.id,
                            mediaId = ep?.id ?: item.id,
                            title = ep?.title ?: item.name,
                            type = item.type,
                            posterUrl = ep?.coverUrl?.ifEmpty { item.streamIcon } ?: item.streamIcon,
                            positionMs = curPos,
                            durationMs = dur,
                            updatedAt = System.currentTimeMillis()
                        )
                        withContext(Dispatchers.IO) {
                            repository.saveWatchProgress(progress)
                            val uid = activeKrasUserId()
                            if (!smartSyncEngine.store.isFavoritesOrHistoryDirty(uid)) {
                                smartSyncEngine.store.markFavoritesOrHistoryDirty(uid)
                            }
                        }
                        _watchHistory.value = withContext(Dispatchers.IO) { repository.getWatchHistory() }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun stopPlayback() {
        progressTrackingJob?.cancel()
        playerWrapper.stop()
        _nowPlayingItem.value = null
        _nowPlayingEpisode.value = null
        _isLiveFullscreen.value = false
    }

    // --- Favorites, Watchlist & Ratings (with Automatic Two-Way TMDb Sync & Offline Queue) ---
    fun toggleFavorite(item: MediaItem) {
        val order = _userSettings.value.liveChannelOrder
        val krasUserId = activeKrasUserId()
        viewModelScope.launch(Dispatchers.IO) {
            val isAdded = repository.toggleFavorite(item, krasUserId)
            smartSyncEngine.store.markFavoritesOrHistoryDirty(krasUserId)
            _favorites.value = repository.getFavorites(order, krasUserId)
            val favSyncCount = repository.getLocalTmdbSyncFavorites(krasUserId).size
            val pendingCount = repository.getPendingTmdbMutations(krasUserId).size
            _tmdbAccountState.value = _tmdbAccountState.value.copy(
                syncedFavoritesCount = favSyncCount,
                pendingOfflineChangesCount = pendingCount
            )
            if (liveCatFilter == "favorites") {
                val account = _activeAccount.value
                if (account != null) {
                    val limitToFetch = (livePage + 1) * pageSize
                    _liveStreams.value = repository.queryMediaPaged(
                        accountId = account.id,
                        type = "LIVE",
                        categoryId = "favorites",
                        query = liveQueryFilter,
                        minRating = 0.0,
                        year = "All",
                        limit = limitToFetch,
                        offset = 0,
                        liveChannelOrder = order,
                        qualityFilter = liveQualityFilter,
                        sortByQuality = liveSortByQuality
                    )
                    totalLiveCount.value = repository.countMedia(
                        accountId = account.id,
                        type = "LIVE",
                        categoryId = "favorites",
                        query = liveQueryFilter,
                        minRating = 0.0,
                        year = "All",
                        qualityFilter = liveQualityFilter
                    )
                }
            }

            // Automatic two-way TMDb sync for Movie and Series favorites when connected & Auto Sync enabled
            if ((item.type == MediaType.MOVIE || item.type == MediaType.SERIES) && _userSettings.value.tmdbAutoSyncEnabled) {
                val stored = tmdbSessionStore.getSession(krasUserId)
                val apiKey = stored?.apiKey?.ifBlank { tmdbSessionStore.getSavedApiKey() }.orEmpty()
                if (stored != null && apiKey.isNotBlank() && stored.sessionId.isNotBlank()) {
                    val resolvedTmdbId = resolveTmdbIdForItem(item, apiKey)
                    if (resolvedTmdbId > 0) {
                        val pushed = tmdbDirectClient.pushFavoriteDirect(
                            apiKey = apiKey,
                            sessionId = stored.sessionId,
                            accountId = stored.accountId,
                            mediaType = item.type.name,
                            tmdbId = resolvedTmdbId,
                            favorite = isAdded
                        )
                        if (pushed) {
                            val nowMs = System.currentTimeMillis()
                            tmdbSessionStore.updateSyncStats(
                                krasAccountId = krasUserId,
                                lastSyncTimestampMs = nowMs,
                                favoritesCount = favSyncCount,
                                watchlistCount = _tmdbAccountState.value.syncedWatchlistCount,
                                ratingsCount = _tmdbAccountState.value.syncedRatingsCount
                            )
                            _tmdbAccountState.value = _tmdbAccountState.value.copy(
                                lastSyncTimestampMs = nowMs,
                                syncedFavoritesCount = favSyncCount
                            )
                        }
                    }
                }
            }
        }
    }

    fun toggleWatchlist(item: MediaItem) {
        if (item.type != MediaType.MOVIE && item.type != MediaType.SERIES) return
        val krasUserId = activeKrasUserId()
        viewModelScope.launch(Dispatchers.IO) {
            val isAdded = repository.toggleWatchlist(item, krasUserId)
            smartSyncEngine.store.markFavoritesOrHistoryDirty(krasUserId)
            val wlCount = repository.getWatchlist(krasUserId).size
            val pendingCount = repository.getPendingTmdbMutations(krasUserId).size
            _tmdbAccountState.value = _tmdbAccountState.value.copy(
                syncedWatchlistCount = wlCount,
                pendingOfflineChangesCount = pendingCount
            )

            if (_userSettings.value.tmdbAutoSyncEnabled) {
                val stored = tmdbSessionStore.getSession(krasUserId)
                val apiKey = stored?.apiKey?.ifBlank { tmdbSessionStore.getSavedApiKey() }.orEmpty()
                if (stored != null && apiKey.isNotBlank() && stored.sessionId.isNotBlank()) {
                    val resolvedTmdbId = resolveTmdbIdForItem(item, apiKey)
                    if (resolvedTmdbId > 0) {
                        val pushed = tmdbDirectClient.pushWatchlistDirect(
                            apiKey = apiKey,
                            sessionId = stored.sessionId,
                            accountId = stored.accountId,
                            mediaType = item.type.name,
                            tmdbId = resolvedTmdbId,
                            watchlist = isAdded
                        )
                        if (pushed) {
                            val nowMs = System.currentTimeMillis()
                            tmdbSessionStore.updateSyncStats(
                                krasAccountId = krasUserId,
                                lastSyncTimestampMs = nowMs,
                                favoritesCount = _tmdbAccountState.value.syncedFavoritesCount,
                                watchlistCount = wlCount,
                                ratingsCount = _tmdbAccountState.value.syncedRatingsCount
                            )
                            _tmdbAccountState.value = _tmdbAccountState.value.copy(
                                lastSyncTimestampMs = nowMs,
                                syncedWatchlistCount = wlCount
                            )
                        }
                    }
                }
            }
        }
    }

    fun rateMediaItem(item: MediaItem, rating: Double) {
        if (item.type != MediaType.MOVIE && item.type != MediaType.SERIES) return
        val krasUserId = activeKrasUserId()
        viewModelScope.launch(Dispatchers.IO) {
            repository.setUserRating(item, rating, krasUserId)
            smartSyncEngine.store.markFavoritesOrHistoryDirty(krasUserId)
            val rtCount = repository.getUserRatings(krasUserId).size
            val pendingCount = repository.getPendingTmdbMutations(krasUserId).size
            _tmdbAccountState.value = _tmdbAccountState.value.copy(
                syncedRatingsCount = rtCount,
                pendingOfflineChangesCount = pendingCount
            )

            if (_userSettings.value.tmdbAutoSyncEnabled) {
                val stored = tmdbSessionStore.getSession(krasUserId)
                val apiKey = stored?.apiKey?.ifBlank { tmdbSessionStore.getSavedApiKey() }.orEmpty()
                if (stored != null && apiKey.isNotBlank() && stored.sessionId.isNotBlank()) {
                    val resolvedTmdbId = resolveTmdbIdForItem(item, apiKey)
                    if (resolvedTmdbId > 0) {
                        val pushed = tmdbDirectClient.pushRatingDirect(
                            apiKey = apiKey,
                            sessionId = stored.sessionId,
                            mediaType = item.type.name,
                            tmdbId = resolvedTmdbId,
                            rating = rating
                        )
                        if (pushed) {
                            val nowMs = System.currentTimeMillis()
                            tmdbSessionStore.updateSyncStats(
                                krasAccountId = krasUserId,
                                lastSyncTimestampMs = nowMs,
                                favoritesCount = _tmdbAccountState.value.syncedFavoritesCount,
                                watchlistCount = _tmdbAccountState.value.syncedWatchlistCount,
                                ratingsCount = rtCount
                            )
                            _tmdbAccountState.value = _tmdbAccountState.value.copy(
                                lastSyncTimestampMs = nowMs,
                                syncedRatingsCount = rtCount
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun resolveTmdbIdForItem(item: MediaItem, apiKey: String): Int {
        if (item.tmdbId > 0) return item.tmdbId
        if (apiKey.isBlank()) return 0
        val isSeries = item.type == MediaType.SERIES
        val metadata = tmdbDirectClient.searchAndMatchMedia(
            apiKey = apiKey,
            rawTitle = item.name,
            year = item.year,
            isSeries = isSeries
        )
        if (metadata != null && metadata.tmdbId > 0) {
            val enriched = item.copy(
                tmdbId = metadata.tmdbId,
                plot = metadata.overview.ifBlank { item.plot },
                streamIcon = metadata.posterUrl.ifBlank { item.streamIcon },
                backdrop = metadata.backdropUrl.ifBlank { item.backdrop },
                rating = if (metadata.rating > 0.0) metadata.rating else item.rating,
                year = metadata.releaseYear.ifBlank { item.year },
                genre = metadata.genres.ifBlank { item.genre },
                cast = metadata.cast.ifBlank { item.cast },
                director = metadata.director.ifBlank { item.director }
            )
            repository.updateTmdbMetadata(enriched)
            applyEnrichedTmdbItemToState(enriched)
            return metadata.tmdbId
        }
        return 0
    }

    fun isFavorite(mediaId: String): Boolean {
        return _favorites.value.any { it.mediaId == mediaId }
    }

    // --- Downloads ---
    fun downloadItem(item: MediaItem) {
        val existing = _downloads.value.find { it.mediaId == item.id }
        if (existing != null) return

        val newDownload = DownloadItem(
            id = java.util.UUID.randomUUID().toString(),
            mediaId = item.id,
            title = item.name,
            type = item.type,
            posterUrl = item.streamIcon,
            status = DownloadStatus.QUEUED,
            progress = 0f,
            downloadedBytes = 0,
            fileSizeBytes = if (item.type == MediaType.MOVIE) 1_800_000_000L else 450_000_000L,
            streamUrl = item.streamUrl
        )

        viewModelScope.launch {
            val updated = _downloads.value + newDownload
            withContext(Dispatchers.IO) {
                repository.saveDownloads(updated)
            }
            _downloads.value = updated

            // Simulate background progress in IO
            for (i in 1..10) {
                delay(800)
                val prog = i / 10f
                try {
                    val list = _downloads.value.map {
                        if (it.mediaId == item.id) {
                            it.copy(
                                progress = prog,
                                downloadedBytes = (it.fileSizeBytes * prog).toLong(),
                                status = if (prog >= 1.0f) DownloadStatus.COMPLETED else DownloadStatus.DOWNLOADING
                            )
                        } else it
                    }
                    withContext(Dispatchers.IO) {
                        repository.saveDownloads(list)
                    }
                    _downloads.value = list
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // --- Settings & Cache ---
    fun updateSettings(newSettings: UserSettings) {
        val prev = _userSettings.value
        val normalizedSettings = newSettings.copy(
            tmdbProxyUrl = canonicalTmdbProxyUrl(newSettings.tmdbProxyUrl)
        )
        val orderChanged = prev.liveChannelOrder != normalizedSettings.liveChannelOrder
        val bufferChanged = prev.bufferSize != normalizedSettings.bufferSize
        val languageChanged = !prev.language.equals(normalizedSettings.language, ignoreCase = true)
        val metadataLanguageChanged = prev.tmdbMetadataLanguage != normalizedSettings.tmdbMetadataLanguage ||
            prev.resolvePreferArabicMetadata() != normalizedSettings.resolvePreferArabicMetadata()
        val tmdbProxyChanged = prev.tmdbProxyUrl != normalizedSettings.tmdbProxyUrl
        val syncScheduleChanged = prev.smartAutoSyncEnabled != normalizedSettings.smartAutoSyncEnabled ||
            prev.syncWifiOnly != normalizedSettings.syncWifiOnly ||
            prev.batterySavingSyncMode != normalizedSettings.batterySavingSyncMode ||
            prev.syncIntervalMoviesHours != normalizedSettings.syncIntervalMoviesHours ||
            prev.syncIntervalSeriesHours != normalizedSettings.syncIntervalSeriesHours ||
            prev.syncIntervalLiveHours != normalizedSettings.syncIntervalLiveHours ||
            prev.syncIntervalCategoriesHours != normalizedSettings.syncIntervalCategoriesHours ||
            prev.syncIntervalEpgHours != normalizedSettings.syncIntervalEpgHours ||
            prev.syncIntervalTmdbHours != normalizedSettings.syncIntervalTmdbHours ||
            prev.syncIntervalAccountHours != normalizedSettings.syncIntervalAccountHours

        KrasMediaPlaybackService.backgroundPlaybackEnabled = normalizedSettings.backgroundPlayback
        _userSettings.value = normalizedSettings
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveUserSettings(normalizedSettings)
        }
        if (bufferChanged) {
            playerWrapper.initPlayer(normalizedSettings.bufferSize)
        }
        if (orderChanged) {
            refreshLiveChannelsOrder()
        }
        if (tmdbProxyChanged) {
            verifyTmdbProxyConnection(normalizedSettings.tmdbProxyUrl)
        }
        if (syncScheduleChanged) {
            SmartSyncWorker.scheduleOrUpdate(getApplication(), normalizedSettings)
            _activeAccount.value?.let { active ->
                smartSyncEngine.refreshUiState(active.id, normalizedSettings)
            }
        }
        if (metadataLanguageChanged || languageChanged) {
            // Preserve separate ar-SA / en-US caches on disk and NEVER trigger an IPTV sync
            enrichedMediaDetailsCache.clear()
            enrichedSeriesDetailsCache.clear()
            val preferArabicMeta = normalizedSettings.resolvePreferArabicMetadata()
            applyCachedLocalizedMetadataToCatalog(preferArabicMeta)
            _selectedMovieForDetails.value?.let { showMediaDetails(it) }
            _selectedSeriesForDetails.value?.let { showMediaDetails(it) }
        }
    }

    fun clearCache() {
        enrichedMediaDetailsCache.clear()
        enrichedSeriesDetailsCache.clear()
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearCache()
            _activeAccount.value?.let { active ->
                smartSyncEngine.store.clearCatalogTimestamps(active.id)
                smartSyncEngine.refreshUiState(active.id, _userSettings.value)
            }
            _watchHistory.value = emptyList()
            resetPaginationStates()
            _activeAccount.value?.let { loadInitialPagesFromRoom(it) }
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    // --- KRAS TV Cinematic Premium 2.0 Helpers ---
    fun restoreOriginalAppearance() {
        updateSettings(_userSettings.value.restoreOriginalAppearance())
    }

    /**
     * Resolves an official YouTube trailer key (11-char ID) for Movie Reels & Details:
     * 1) Prefers localized Arabic trailer/subtitled trailer from TMDb when Arabic metadata is enabled
     * 2) Falls back to Xtream VOD/Series youtube_trailer or English TMDb trailer
     *
     * Never scrapes or downloads protected video streams; returns only an official YouTube video key.
     */
    suspend fun resolveOfficialTrailerKey(item: MediaItem): String? = withContext(Dispatchers.IO) {
        try {
            val isSeries = item.type == MediaType.SERIES
            val preferArabic = isArabicMetadataPreferred()
            var resolvedTmdbId = tmdbCacheStore.getManualBinding(item.id)?.tmdbId ?: item.tmdbId

            // Check cached rich metadata for preferred-language trailer first
            val cachedMeta = tmdbCacheStore.getExactLanguageForMediaItem(
                mediaItemId = item.id,
                tmdbId = resolvedTmdbId,
                isSeries = isSeries,
                preferArabic = preferArabic
            )
            if (cachedMeta != null) {
                if (resolvedTmdbId <= 0 && cachedMeta.tmdbId > 0) resolvedTmdbId = cachedMeta.tmdbId
                val cachedKey = normalizeYoutubeKey(cachedMeta.trailerYoutubeKey)
                if (cachedKey != null && (!preferArabic || cachedMeta.trailerLanguage == "ar")) {
                    return@withContext cachedKey
                }
            }

            val apiKey = tmdbSessionStore.getSavedApiKey().trim()
            if (preferArabic && apiKey.isNotBlank() && resolvedTmdbId > 0) {
                val localizedTmdbKey = tmdbDirectClient.fetchOfficialYoutubeTrailerKey(
                    apiKey = apiKey,
                    tmdbId = resolvedTmdbId,
                    isSeries = isSeries,
                    preferArabic = true
                )
                if (!localizedTmdbKey.isNullOrBlank()) {
                    return@withContext localizedTmdbKey
                }
            }

            val account = _activeAccount.value
            if (account != null && !account.isDemo && !account.serverUrl.startsWith("m3u://") && item.streamId > 0) {
                if (isSeries) {
                    val details = apiClient.getSeriesDetails(account, item.streamId)
                    val ytKey = normalizeYoutubeKey(details?.youtubeTrailer.orEmpty())
                    if (ytKey != null) return@withContext ytKey
                } else {
                    val vodDetails = apiClient.fetchVodDetails(account, item.streamId, item.quality)
                    val ytKey = normalizeYoutubeKey(vodDetails?.youtubeTrailer.orEmpty())
                    if (ytKey != null) return@withContext ytKey
                    if (resolvedTmdbId <= 0 && (vodDetails?.tmdbId ?: 0) > 0) {
                        resolvedTmdbId = vodDetails!!.tmdbId
                    }
                }
            }

            if (apiKey.isNotBlank() && resolvedTmdbId > 0) {
                val tmdbKey = tmdbDirectClient.fetchOfficialYoutubeTrailerKey(
                    apiKey = apiKey,
                    tmdbId = resolvedTmdbId,
                    isSeries = isSeries,
                    preferArabic = preferArabic
                )
                if (!tmdbKey.isNullOrBlank()) {
                    return@withContext tmdbKey
                }
            }

            cachedMeta?.trailerYoutubeKey?.let { normalizeYoutubeKey(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeYoutubeKey(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val directIdRegex = Regex("""^[A-Za-z0-9_-]{11}$""")
        if (directIdRegex.matches(trimmed)) return trimmed
        val urlRegex = Regex("""(?:youtube\.com/(?:watch\?v=|embed/|shorts/)|youtu\.be/)([A-Za-z0-9_-]{11})""")
        return urlRegex.find(trimmed)?.groupValues?.getOrNull(1)
    }

    // --- In-App GitHub Release Updater ---
    private fun checkForUpdatesOnLaunchIfNeeded() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                AppUpdater.cleanupOldApksAfterUpdate(app, repository, BuildConfig.VERSION_CODE)

                if (!_userSettings.value.autoCheckUpdates) return@launch

                val lastCheckMs = repository.getLastUpdateCheckMs()
                val now = System.currentTimeMillis()
                if (now - lastCheckMs >= AppUpdater.SIX_HOURS_MS) {
                    checkForUpdates(isManual = false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun checkForUpdates(isManual: Boolean = true) {
        if (_updateState.value.isChecking) return
        updateCheckJob?.cancel()
        updateCheckJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _updateState.value = _updateState.value.copy(
                    isChecking = true,
                    statusMessage = if (isManual) "Checking for updates..." else _updateState.value.statusMessage,
                    isStatusError = false,
                    errorMessage = null
                )

                val result = appUpdater.checkLatestRelease(BuildConfig.VERSION_CODE)
                repository.saveLastUpdateCheckMs(System.currentTimeMillis())

                when (result) {
                    is ReleaseCheckResult.UpdateAvailable -> {
                        _updateState.value = _updateState.value.copy(
                            isChecking = false,
                            availableRelease = result.release,
                            showUpdateDialog = true,
                            statusMessage = "New version ${result.release.tagName} available",
                            isStatusError = false,
                            errorMessage = null
                        )
                    }
                    is ReleaseCheckResult.UpToDate -> {
                        _updateState.value = _updateState.value.copy(
                            isChecking = false,
                            statusMessage = if (isManual) "You are up to date" else _updateState.value.statusMessage,
                            isStatusError = false
                        )
                    }
                    is ReleaseCheckResult.RateLimited -> {
                        _updateState.value = _updateState.value.copy(
                            isChecking = false,
                            statusMessage = if (isManual) result.message else null,
                            isStatusError = isManual
                        )
                    }
                    is ReleaseCheckResult.MissingAsset -> {
                        _updateState.value = _updateState.value.copy(
                            isChecking = false,
                            statusMessage = if (isManual) result.message else null,
                            isStatusError = isManual
                        )
                    }
                    is ReleaseCheckResult.NetworkError -> {
                        _updateState.value = _updateState.value.copy(
                            isChecking = false,
                            statusMessage = if (isManual) result.message else null,
                            isStatusError = isManual
                        )
                    }
                    is ReleaseCheckResult.Error -> {
                        _updateState.value = _updateState.value.copy(
                            isChecking = false,
                            statusMessage = if (isManual) result.message else null,
                            isStatusError = isManual
                        )
                    }
                }
            } catch (ce: CancellationException) {
                _updateState.value = _updateState.value.copy(isChecking = false)
            } catch (e: Exception) {
                _updateState.value = _updateState.value.copy(
                    isChecking = false,
                    statusMessage = if (isManual) (e.message ?: "Unable to check for updates.") else null,
                    isStatusError = isManual
                )
            }
        }
    }

    fun startUpdateDownload() {
        val release = _updateState.value.availableRelease ?: return
        if (_updateState.value.isDownloading) return

        updateDownloadJob?.cancel()
        updateDownloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _updateState.value = _updateState.value.copy(
                    isDownloading = true,
                    downloadProgress = 0,
                    downloadedBytes = 0L,
                    totalBytes = release.assetSize,
                    errorMessage = null
                )

                val downloadResult = appUpdater.downloadApk(
                    context = getApplication(),
                    release = release
                ) { pct, downloaded, total ->
                    _updateState.value = _updateState.value.copy(
                        downloadProgress = pct,
                        downloadedBytes = downloaded,
                        totalBytes = total
                    )
                }

                downloadResult.onSuccess { apkFile ->
                    _updateState.value = _updateState.value.copy(
                        isDownloading = false,
                        downloadProgress = 100,
                        pendingInstallFile = apkFile,
                        errorMessage = null
                    )
                    launchInstallerOrRequestPermissionInternal(apkFile)
                }.onFailure { err ->
                    _updateState.value = _updateState.value.copy(
                        isDownloading = false,
                        errorMessage = err.message ?: "Download failed. Please try again."
                    )
                }
            } catch (ce: CancellationException) {
                _updateState.value = _updateState.value.copy(
                    isDownloading = false,
                    downloadProgress = 0,
                    downloadedBytes = 0L
                )
            } catch (e: Exception) {
                _updateState.value = _updateState.value.copy(
                    isDownloading = false,
                    errorMessage = e.message ?: "Download failed. Please try again."
                )
            }
        }
    }

    fun cancelUpdateDownload() {
        try {
            appUpdater.cancelActiveDownload()
            updateDownloadJob?.cancel()
        } catch (_: Exception) {
        }
        _updateState.value = _updateState.value.copy(
            isDownloading = false,
            downloadProgress = 0,
            downloadedBytes = 0L,
            errorMessage = null
        )
    }

    fun dismissUpdateDialog() {
        if (_updateState.value.isDownloading) {
            cancelUpdateDownload()
        }
        _updateState.value = _updateState.value.copy(
            showUpdateDialog = false,
            errorMessage = null
        )
    }

    fun triggerInstallOrRequestPermission(apkFile: File? = _updateState.value.pendingInstallFile) {
        val file = apkFile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            launchInstallerOrRequestPermissionInternal(file)
        }
    }

    private suspend fun launchInstallerOrRequestPermissionInternal(file: File) {
        val app = getApplication<Application>()
        try {
            if (!file.exists()) {
                _updateState.value = _updateState.value.copy(
                    showInstallPermissionDialog = false,
                    showUpdateDialog = true,
                    pendingInstallFile = null,
                    errorMessage = "Downloaded update file was not found. Tap Retry to download again."
                )
                return
            }

            if (!AppUpdater.canRequestPackageInstalls(app)) {
                _updateState.value = _updateState.value.copy(
                    pendingInstallFile = file,
                    showInstallPermissionDialog = true
                )
                return
            }

            withContext(Dispatchers.Main) {
                val installIntent = AppUpdater.createInstallApkIntent(app, file)
                val resolved = installIntent.resolveActivity(app.packageManager)
                if (resolved == null) {
                    _updateState.value = _updateState.value.copy(
                        showInstallPermissionDialog = false,
                        showUpdateDialog = true,
                        errorMessage = "No Android package installer was found on this device to open the update APK."
                    )
                    return@withContext
                }
                app.startActivity(installIntent)
                _updateState.value = _updateState.value.copy(
                    showInstallPermissionDialog = false,
                    showUpdateDialog = false,
                    pendingInstallFile = null,
                    errorMessage = null
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _updateState.value = _updateState.value.copy(
                showInstallPermissionDialog = false,
                showUpdateDialog = true,
                errorMessage = e.message ?: "Could not launch package installer."
            )
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        try {
            val intent = AppUpdater.createManageUnknownAppSourcesIntent(context)
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            _updateState.value = _updateState.value.copy(
                showInstallPermissionDialog = false,
                showUpdateDialog = true,
                errorMessage = "Unable to open system settings: ${e.message ?: "Unknown error"}"
            )
        }
    }

    fun dismissInstallPermissionDialog() {
        _updateState.value = _updateState.value.copy(
            showInstallPermissionDialog = false
        )
    }

    fun onResumeCheckPendingInstall() {
        val pendingFile = _updateState.value.pendingInstallFile ?: return
        val app = getApplication<Application>()
        if (AppUpdater.canRequestPackageInstalls(app)) {
            triggerInstallOrRequestPermission(pendingFile)
        }
    }

    override fun onCleared() {
        super.onCleared()
        progressTrackingJob?.cancel()
        syncJob?.cancel()
        updateCheckJob?.cancel()
        updateDownloadJob?.cancel()
        appUpdater.cancelActiveDownload()
        playerWrapper.release()
    }
}

