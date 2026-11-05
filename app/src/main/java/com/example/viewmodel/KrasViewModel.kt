package com.example.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.KrasApplication
import com.example.data.api.XtreamClient
import com.example.data.local.ChannelNameNormalizer
import com.example.data.local.KrasRepository
import com.example.data.local.QualityDetector
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.MediaItemEntity
import com.example.data.model.*
import com.example.data.sample.SampleData
import com.example.player.ExoPlayerWrapper
import com.example.player.KrasMediaPlaybackService
import com.example.player.PlaybackNotificationMetadata
import com.example.sound.CinematicSoundPlayer
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

class KrasViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = KrasRepository(application)
    private val apiClient = XtreamClient()
    private val appUpdater = AppUpdater()
    val playerWrapper = ExoPlayerWrapper(application)

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

    // Active Series Details (loaded on demand)
    private val _currentSeriesDetails = MutableStateFlow<SeriesDetails?>(null)
    val currentSeriesDetails = _currentSeriesDetails.asStateFlow()

    private val _isSeriesDetailsLoading = MutableStateFlow(false)
    val isSeriesDetailsLoading = _isSeriesDetailsLoading.asStateFlow()

    // Favorites & History & Downloads
    private val _favorites = MutableStateFlow<List<FavoriteItem>>(emptyList())
    val favorites = _favorites.asStateFlow()

    private val _watchHistory = MutableStateFlow<List<WatchProgress>>(emptyList())
    val watchHistory = _watchHistory.asStateFlow()

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads = _downloads.asStateFlow()

    // Settings
    private val _userSettings = MutableStateFlow(repository.getUserSettings())
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

    private val _isLiveMiniPlayerActive = MutableStateFlow(false)
    val isLiveMiniPlayerActive = _isLiveMiniPlayerActive.asStateFlow()

    private val _recentLiveChannels = MutableStateFlow<List<MediaItem>>(repository.getRecentLiveChannels())
    val recentLiveChannels = _recentLiveChannels.asStateFlow()

    private val _lastWatchedLiveChannel = MutableStateFlow<MediaItem?>(repository.getLastWatchedLiveChannel())
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

    private val pageSize = 40

    init {
        KrasMediaPlaybackService.backgroundPlaybackEnabled = _userSettings.value.backgroundPlayback
        playerWrapper.onRealQualityDetected = { realQuality ->
            onRealPlaybackQualityDetected(realQuality)
        }
        loadInitialData()
    }

    fun playIntroSoundIfEnabled() {
        if (_userSettings.value.playIntroSound) {
            viewModelScope.launch {
                try {
                    CinematicSoundPlayer.playCinematicIntro()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun stopIntroSound() {
        CinematicSoundPlayer.stop()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            try {
                val currentOrder = _userSettings.value.liveChannelOrder
                val (loadedAccounts, active, favs, history, dlList) = withContext(Dispatchers.IO) {
                    val accs = repository.getAccounts()
                    val act = repository.getActiveAccount()
                    val f = repository.getFavorites(currentOrder)
                    val h = repository.getWatchHistory()
                    val d = repository.getDownloads()
                    Tuple5(accs, act, f, h, d)
                }

                _accounts.value = loadedAccounts
                _activeAccount.value = active
                _favorites.value = favs
                _watchHistory.value = history
                _downloads.value = dlList
                _recentLiveChannels.value = withContext(Dispatchers.IO) { repository.getRecentLiveChannels() }
                _lastWatchedLiveChannel.value = withContext(Dispatchers.IO) { repository.getLastWatchedLiveChannel() }

                // Initialize player with buffer setting
                playerWrapper.initPlayer(_userSettings.value.bufferSize)

                // Load cached categories and initial pages from Room instantly
                loadInitialPagesFromRoom(active)

                // Start background content sync
                loadContentForAccount(active, isBackgroundRefresh = true)

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

                _favorites.value = repository.getFavorites(order)

                _isLoading.value = false
            } else if (account.isDemo) {
                loadDemoDataFallback()
            } else {
                _isLoading.value = true
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
                resetPaginationStates()
                loadInitialPagesFromRoom(active)
                loadContentForAccount(active, isBackgroundRefresh = false)
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
                    resetPaginationStates()
                    loadContentForAccount(account, isBackgroundRefresh = false)
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
                resetPaginationStates()
                loadContentForAccount(SampleData.demoAccount, isBackgroundRefresh = false)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeAccount(accountId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.removeAccount(accountId)
                    repository.mediaDao.deleteAllByAccount(accountId)
                    repository.mediaDao.deleteCategoriesByAccount(accountId)
                }
                _accounts.value = withContext(Dispatchers.IO) { repository.getAccounts() }
                val active = withContext(Dispatchers.IO) { repository.getActiveAccount() }
                _activeAccount.value = active
                resetPaginationStates()
                loadInitialPagesFromRoom(active)
                loadContentForAccount(active, isBackgroundRefresh = false)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun refreshContent() {
        val active = _activeAccount.value ?: return
        viewModelScope.launch {
            _isRefreshing.value = true
            loadContentForAccount(active, isBackgroundRefresh = true)
            _isRefreshing.value = false
        }
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

        seriesPage = 0
        seriesCatFilter = "all"
        seriesQueryFilter = ""
        seriesRatingFilter = 0.0
        seriesYearFilter = "All"
        seriesQualityFilter = "All"
        seriesSortByQuality = false
        seriesSortOrder = MediaSortOrder.RECENTLY_ADDED

        livePage = 0
        liveCatFilter = "all"
        liveQueryFilter = ""
        liveQualityFilter = "All"
        liveSortByQuality = false
    }

    // --- Core Memory-Safe Batch Syncing ---
    private fun loadContentForAccount(account: Account, isBackgroundRefresh: Boolean = false) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch(Dispatchers.IO) {
            if (!isBackgroundRefresh && _movies.value.isEmpty()) {
                _isLoading.value = true
            }
            _errorMessage.value = null

            if (account.isDemo) {
                try {
                    // Populate Room with Demo Sample Data (with groupKey, seq, groupOrder, serverOrder)
                    val movieEntities = MediaItemEntity.fromMediaItemList(SampleData.sampleMovies, account.id)
                    val seriesEntities = MediaItemEntity.fromMediaItemList(SampleData.sampleSeries, account.id)
                    val liveEntities = MediaItemEntity.fromMediaItemList(SampleData.sampleLiveStreams, account.id)

                    val movieCatEntities = SampleData.movieCategories.map { CategoryEntity.fromCategory(it, account.id) }
                    val seriesCatEntities = SampleData.seriesCategories.map { CategoryEntity.fromCategory(it, account.id) }
                    val liveCatEntities = SampleData.liveCategories.map { CategoryEntity.fromCategory(it, account.id) }

                    repository.insertMediaBatch(movieEntities + seriesEntities + liveEntities)
                    repository.saveCategories(account.id, MediaType.MOVIE, movieCatEntities)
                    repository.saveCategories(account.id, MediaType.SERIES, seriesCatEntities)
                    repository.saveCategories(account.id, MediaType.LIVE, liveCatEntities)

                    loadInitialPagesFromRoom(account)
                } catch (e: Exception) {
                    e.printStackTrace()
                    loadDemoDataFallback()
                } finally {
                    _isLoading.value = false
                    _isRefreshing.value = false
                }
                return@launch
            }

            try {
                // Step 1: Stream and Cache Categories
                _syncProgress.value = "Updating Categories..."
                val movieCats = apiClient.fetchCategories(account, MediaType.MOVIE)
                if (movieCats.isNotEmpty()) {
                    repository.saveCategories(account.id, MediaType.MOVIE, movieCats)
                    _movieCategories.value = movieCats.map { it.toCategory() }
                }

                val seriesCats = apiClient.fetchCategories(account, MediaType.SERIES)
                if (seriesCats.isNotEmpty()) {
                    repository.saveCategories(account.id, MediaType.SERIES, seriesCats)
                    _seriesCategories.value = seriesCats.map { it.toCategory() }
                }

                val liveCats = apiClient.fetchCategories(account, MediaType.LIVE)
                if (liveCats.isNotEmpty()) {
                    repository.saveCategories(account.id, MediaType.LIVE, liveCats)
                    _liveCategories.value = liveCats.map { it.toCategory() }
                }

                val movieCatMap = movieCats.associate { it.categoryId to it.categoryName }
                val seriesCatMap = seriesCats.associate { it.categoryId to it.categoryName }
                val liveCatMap = liveCats.associate { it.categoryId to it.categoryName }

                // Step 2: Stream Live Channels in 500-item Batches (Clearing old rows first)
                _syncProgress.value = "Syncing Live Channels..."
                repository.clearOldRows(account.id, "LIVE")
                var totalLiveParsed = 0
                apiClient.streamLiveStreams(account, categoryMap = liveCatMap, batchSize = 500) { batch ->
                    repository.insertMediaBatch(batch)
                    totalLiveParsed += batch.size
                    _syncProgress.value = "Live Channels synced: $totalLiveParsed"
                }

                // Step 3: Stream Movies in 500-item Batches (Clearing old rows first)
                _syncProgress.value = "Syncing Movies..."
                repository.clearOldRows(account.id, "MOVIE")
                var totalMoviesParsed = 0
                apiClient.streamVodStreams(account, categoryMap = movieCatMap, batchSize = 500) { batch ->
                    repository.insertMediaBatch(batch)
                    totalMoviesParsed += batch.size
                    _syncProgress.value = "Movies synced: $totalMoviesParsed"
                }

                // Step 4: Stream Series in 500-item Batches (Clearing old rows first)
                _syncProgress.value = "Syncing Series..."
                repository.clearOldRows(account.id, "SERIES")
                var totalSeriesParsed = 0
                apiClient.streamSeries(account, categoryMap = seriesCatMap, batchSize = 500) { batch ->
                    repository.insertMediaBatch(batch)
                    totalSeriesParsed += batch.size
                    _syncProgress.value = "Series synced: $totalSeriesParsed"
                }

                _syncProgress.value = "Sync completed successfully"
                delay(600)
                _syncProgress.value = null

                // Step 5: Refresh UI with updated paged content from Room
                loadInitialPagesFromRoom(account)

            } catch (oom: OutOfMemoryError) {
                handleOom(oom, "Out of memory during Xtream catalog synchronization")
                loadInitialPagesFromRoom(account)
            } catch (e: Exception) {
                e.printStackTrace()
                _errorMessage.value = "Failed to update catalog: ${e.message}"
                loadInitialPagesFromRoom(account)
            } finally {
                _isLoading.value = false
                _isRefreshing.value = false
                _syncProgress.value = null
            }
        }
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

        val account = _activeAccount.value ?: return
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

        val account = _activeAccount.value ?: return
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
        if (!isSameFilter) {
            livePage = 0
        }
        liveCatFilter = categoryId
        liveQueryFilter = query
        liveQualityFilter = qualityFilter
        liveSortByQuality = sortByQuality

        val account = _activeAccount.value ?: return
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

    // --- On-Demand Series Details ---
    fun loadSeriesDetails(seriesItem: MediaItem) {
        if (_currentSeriesDetails.value?.seriesId != seriesItem.streamId) {
            _currentSeriesDetails.value = null
        }
        viewModelScope.launch {
            _isSeriesDetailsLoading.value = true
            try {
                val active = _activeAccount.value
                if (active != null && !active.isDemo) {
                    val details = withContext(Dispatchers.IO) {
                        apiClient.getSeriesDetails(active, seriesItem.streamId)
                    }
                    if (details != null) {
                        _currentSeriesDetails.value = details
                        if (details.quality.isNotBlank()) {
                            applyDetectedQualityToItem(seriesItem.id, details.quality)
                        }
                        return@launch
                    }
                }
                _currentSeriesDetails.value = SampleData.getSampleSeriesDetails(seriesItem.streamId)
            } catch (e: Exception) {
                e.printStackTrace()
                _currentSeriesDetails.value = SampleData.getSampleSeriesDetails(seriesItem.streamId)
            } finally {
                _isSeriesDetailsLoading.value = false
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

    fun setLiveFullscreen(fullscreen: Boolean) {
        _isLiveFullscreen.value = fullscreen
        if (fullscreen) {
            _isLiveMiniPlayerActive.value = false
        }
    }

    fun setLiveMiniPlayer(active: Boolean) {
        _isLiveMiniPlayerActive.value = active && _activeLiveChannel.value != null
        if (active) {
            _isLiveFullscreen.value = false
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
        }
        _nowPlayingItem.value = mediaItem
        _nowPlayingEpisode.value = episode

        val urlToPlay = (episode?.streamUrl?.takeIf { it.isNotBlank() } ?: mediaItem.streamUrl).trim()
        val previousProgress = _watchHistory.value.find { it.mediaId == (episode?.id ?: mediaItem.id) }
        val startPos = previousProgress?.positionMs ?: 0L

        if (urlToPlay.isNotBlank()) {
            playerWrapper.play(urlToPlay, startPos)
            startPositionTracker()
        } else if (mediaItem.type == MediaType.SERIES) {
            // Immediately enter the unified player in PREPARING state and resolve the episode URL in-place
            playerWrapper.setPreparingState()
            viewModelScope.launch {
                try {
                    val active = _activeAccount.value
                    val details = _currentSeriesDetails.value?.takeIf { it.seriesId == mediaItem.streamId }
                        ?: withContext(Dispatchers.IO) {
                            if (active != null && !active.isDemo) {
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
                        playerWrapper.play(resolvedEpisode.streamUrl, epProgress?.positionMs ?: startPos)
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
            playerWrapper.play(urlToPlay, startPos)
            startPositionTracker()
        }

        // Step 2b: For movies, also check get_vod_info in background for video width/height/codec/framerate
        val active = _activeAccount.value
        if (mediaItem.type == MediaType.MOVIE && active != null && !active.isDemo) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val vodQuality = apiClient.getVodVideoInfo(
                        account = active,
                        vodId = mediaItem.streamId,
                        fallbackQuality = mediaItem.quality.ifBlank { mediaItem.resolution }
                    )
                    if (!vodQuality.isNullOrBlank() && playerWrapper.detectedQualityBadge.value.isBlank()) {
                        applyDetectedQualityToItem(mediaItem.id, vodQuality)
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

    // --- Favorites ---
    fun toggleFavorite(item: MediaItem) {
        val order = _userSettings.value.liveChannelOrder
        viewModelScope.launch(Dispatchers.IO) {
            repository.toggleFavorite(item)
            _favorites.value = repository.getFavorites(order)
            if (liveCatFilter == "favorites") {
                val account = _activeAccount.value ?: return@launch
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
        val orderChanged = _userSettings.value.liveChannelOrder != newSettings.liveChannelOrder
        val bufferChanged = _userSettings.value.bufferSize != newSettings.bufferSize
        KrasMediaPlaybackService.backgroundPlaybackEnabled = newSettings.backgroundPlayback
        _userSettings.value = newSettings
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveUserSettings(newSettings)
        }
        if (bufferChanged) {
            playerWrapper.initPlayer(newSettings.bufferSize)
        }
        if (orderChanged) {
            refreshLiveChannelsOrder()
        }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearCache()
            _watchHistory.value = emptyList()
            resetPaginationStates()
            _activeAccount.value?.let { loadInitialPagesFromRoom(it) }
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
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
                    triggerInstallOrRequestPermission(apkFile)
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
        val app = getApplication<Application>()
        try {
            if (!file.exists()) {
                _updateState.value = _updateState.value.copy(
                    showInstallPermissionDialog = false,
                    showUpdateDialog = true,
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

            val installIntent = AppUpdater.createInstallApkIntent(app, file)
            app.startActivity(installIntent)
            _updateState.value = _updateState.value.copy(
                showInstallPermissionDialog = false,
                showUpdateDialog = false,
                pendingInstallFile = null,
                errorMessage = null
            )
        } catch (e: Exception) {
            e.printStackTrace()
            _updateState.value = _updateState.value.copy(
                showInstallPermissionDialog = false,
                showUpdateDialog = true,
                errorMessage = "Could not launch package installer: ${e.message ?: "Unknown error"}"
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
