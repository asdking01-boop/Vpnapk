package com.example.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.KrasApplication
import com.example.data.api.XtreamClient
import com.example.data.local.KrasRepository
import com.example.data.local.entity.CategoryEntity
import com.example.data.local.entity.MediaItemEntity
import com.example.data.model.*
import com.example.data.sample.SampleData
import com.example.player.ExoPlayerWrapper
import com.example.sound.CinematicSoundPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KrasViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = KrasRepository(application)
    private val apiClient = XtreamClient()
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

    private val _seriesCategories = MutableStateFlow<List<Category>>(emptyList())
    val seriesCategories = _seriesCategories.asStateFlow()

    private val _series = MutableStateFlow<List<MediaItem>>(emptyList())
    val series = _series.asStateFlow()
    val totalSeriesCount = MutableStateFlow(0)

    // Active Series Details (loaded on demand)
    private val _currentSeriesDetails = MutableStateFlow<SeriesDetails?>(null)
    val currentSeriesDetails = _currentSeriesDetails.asStateFlow()

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

    // Active Playback State
    private val _nowPlayingItem = MutableStateFlow<MediaItem?>(null)
    val nowPlayingItem = _nowPlayingItem.asStateFlow()

    private val _nowPlayingEpisode = MutableStateFlow<Episode?>(null)
    val nowPlayingEpisode = _nowPlayingEpisode.asStateFlow()

    private var progressTrackingJob: Job? = null
    private var syncJob: Job? = null

    // Pagination filter states
    private var moviePage = 0
    private var movieCatFilter = "all"
    private var movieQueryFilter = ""
    private var movieRatingFilter = 0.0
    private var movieYearFilter = "All"
    private var isMoviesLoadingMore = false

    private var seriesPage = 0
    private var seriesCatFilter = "all"
    private var seriesQueryFilter = ""
    private var seriesRatingFilter = 0.0
    private var seriesYearFilter = "All"
    private var isSeriesLoadingMore = false

    private var livePage = 0
    private var liveCatFilter = "all"
    private var liveQueryFilter = ""
    private var isLiveLoadingMore = false

    private val pageSize = 40

    init {
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
                val (loadedAccounts, active, favs, history, dlList) = withContext(Dispatchers.IO) {
                    val accs = repository.getAccounts()
                    val act = repository.getActiveAccount()
                    val f = repository.getFavorites()
                    val h = repository.getWatchHistory()
                    val d = repository.getDownloads()
                    Tuple5(accs, act, f, h, d)
                }

                _accounts.value = loadedAccounts
                _activeAccount.value = active
                _favorites.value = favs
                _watchHistory.value = history
                _downloads.value = dlList

                // Initialize player with buffer setting
                playerWrapper.initPlayer(_userSettings.value.bufferSize)

                // Load cached categories and initial pages from Room instantly
                loadInitialPagesFromRoom(active)

                // Start background content sync
                loadContentForAccount(active, isBackgroundRefresh = true)
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
            val movieCats = repository.getCategories(account.id, MediaType.MOVIE)
            val seriesCats = repository.getCategories(account.id, MediaType.SERIES)
            val liveCats = repository.getCategories(account.id, MediaType.LIVE)

            val initialMovies = repository.queryMediaPaged(account.id, "MOVIE", "all", "", 0.0, "All", pageSize, 0)
            val initialSeries = repository.queryMediaPaged(account.id, "SERIES", "all", "", 0.0, "All", pageSize, 0)
            val initialLive = repository.queryMediaPaged(account.id, "LIVE", "all", "", 0.0, "All", pageSize, 0)

            val mCount = repository.countMedia(account.id, "MOVIE", "all", "", 0.0, "All")
            val sCount = repository.countMedia(account.id, "SERIES", "all", "", 0.0, "All")
            val lCount = repository.countMedia(account.id, "LIVE", "all", "", 0.0, "All")

            if (initialMovies.isNotEmpty() || initialSeries.isNotEmpty() || initialLive.isNotEmpty()) {
                _movieCategories.value = movieCats
                _seriesCategories.value = seriesCats
                _liveCategories.value = liveCats

                _movies.value = initialMovies
                totalMoviesCount.value = mCount

                _series.value = initialSeries
                totalSeriesCount.value = sCount

                _liveStreams.value = initialLive
                totalLiveCount.value = lCount

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
        _liveCategories.value = SampleData.liveCategories
        _liveStreams.value = SampleData.sampleLiveStreams
        totalLiveCount.value = SampleData.sampleLiveStreams.size

        _movieCategories.value = SampleData.movieCategories
        _movies.value = SampleData.sampleMovies
        totalMoviesCount.value = SampleData.sampleMovies.size

        _seriesCategories.value = SampleData.seriesCategories
        _series.value = SampleData.sampleSeries
        totalSeriesCount.value = SampleData.sampleSeries.size

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

        seriesPage = 0
        seriesCatFilter = "all"
        seriesQueryFilter = ""
        seriesRatingFilter = 0.0
        seriesYearFilter = "All"

        livePage = 0
        liveCatFilter = "all"
        liveQueryFilter = ""
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
                    // Populate Room with Demo Sample Data
                    val movieEntities = SampleData.sampleMovies.map { MediaItemEntity.fromMediaItem(it, account.id) }
                    val seriesEntities = SampleData.sampleSeries.map { MediaItemEntity.fromMediaItem(it, account.id) }
                    val liveEntities = SampleData.sampleLiveStreams.map { MediaItemEntity.fromMediaItem(it, account.id) }

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

                // Step 2: Stream Live Channels in 500-item Batches (Clearing old rows first)
                _syncProgress.value = "Syncing Live Channels..."
                repository.clearOldRows(account.id, "LIVE")
                var totalLiveParsed = 0
                apiClient.streamLiveStreams(account, batchSize = 500) { batch ->
                    repository.insertMediaBatch(batch)
                    totalLiveParsed += batch.size
                    _syncProgress.value = "Live Channels synced: $totalLiveParsed"
                }

                // Step 3: Stream Movies in 500-item Batches (Clearing old rows first)
                _syncProgress.value = "Syncing Movies..."
                repository.clearOldRows(account.id, "MOVIE")
                var totalMoviesParsed = 0
                apiClient.streamVodStreams(account, batchSize = 500) { batch ->
                    repository.insertMediaBatch(batch)
                    totalMoviesParsed += batch.size
                    _syncProgress.value = "Movies synced: $totalMoviesParsed"
                }

                // Step 4: Stream Series in 500-item Batches (Clearing old rows first)
                _syncProgress.value = "Syncing Series..."
                repository.clearOldRows(account.id, "SERIES")
                var totalSeriesParsed = 0
                apiClient.streamSeries(account, batchSize = 500) { batch ->
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
        year: String = movieYearFilter
    ) {
        movieCatFilter = categoryId
        movieQueryFilter = query
        movieRatingFilter = minRating
        movieYearFilter = year
        moviePage = 0

        val account = _activeAccount.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val paged = repository.queryMediaPaged(
                    accountId = account.id,
                    type = "MOVIE",
                    categoryId = categoryId,
                    query = query,
                    minRating = minRating,
                    year = year,
                    limit = pageSize,
                    offset = 0
                )
                val count = repository.countMedia(
                    accountId = account.id,
                    type = "MOVIE",
                    categoryId = categoryId,
                    query = query,
                    minRating = minRating,
                    year = year
                )
                _movies.value = paged
                totalMoviesCount.value = count
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
                    offset = nextPage * pageSize
                )
                if (more.isNotEmpty()) {
                    moviePage = nextPage
                    _movies.value = _movies.value + more
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
        year: String = seriesYearFilter
    ) {
        seriesCatFilter = categoryId
        seriesQueryFilter = query
        seriesRatingFilter = minRating
        seriesYearFilter = year
        seriesPage = 0

        val account = _activeAccount.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val paged = repository.queryMediaPaged(
                    accountId = account.id,
                    type = "SERIES",
                    categoryId = categoryId,
                    query = query,
                    minRating = minRating,
                    year = year,
                    limit = pageSize,
                    offset = 0
                )
                val count = repository.countMedia(
                    accountId = account.id,
                    type = "SERIES",
                    categoryId = categoryId,
                    query = query,
                    minRating = minRating,
                    year = year
                )
                _series.value = paged
                totalSeriesCount.value = count
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
                    offset = nextPage * pageSize
                )
                if (more.isNotEmpty()) {
                    seriesPage = nextPage
                    _series.value = _series.value + more
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
        query: String = liveQueryFilter
    ) {
        liveCatFilter = categoryId
        liveQueryFilter = query
        livePage = 0

        val account = _activeAccount.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val paged = repository.queryMediaPaged(
                    accountId = account.id,
                    type = "LIVE",
                    categoryId = categoryId,
                    query = query,
                    minRating = 0.0,
                    year = "All",
                    limit = pageSize,
                    offset = 0
                )
                val count = repository.countMedia(
                    accountId = account.id,
                    type = "LIVE",
                    categoryId = categoryId,
                    query = query,
                    minRating = 0.0,
                    year = "All"
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
                    offset = nextPage * pageSize
                )
                if (more.isNotEmpty()) {
                    livePage = nextPage
                    _liveStreams.value = _liveStreams.value + more
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLiveLoadingMore = false
            }
        }
    }

    // --- On-Demand Series Details ---
    fun loadSeriesDetails(seriesItem: MediaItem) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val active = _activeAccount.value
                if (active != null && !active.isDemo) {
                    val details = apiClient.getSeriesDetails(active, seriesItem.streamId)
                    if (details != null) {
                        _currentSeriesDetails.value = details
                        _isLoading.value = false
                        return@launch
                    }
                }
                _currentSeriesDetails.value = SampleData.getSampleSeriesDetails(seriesItem.streamId)
            } catch (e: Exception) {
                e.printStackTrace()
                _currentSeriesDetails.value = SampleData.getSampleSeriesDetails(seriesItem.streamId)
            } finally {
                _isLoading.value = false
            }
        }
    }

    // --- Playback Control ---
    fun startPlayback(mediaItem: MediaItem, episode: Episode? = null) {
        _nowPlayingItem.value = mediaItem
        _nowPlayingEpisode.value = episode

        val urlToPlay = episode?.streamUrl ?: mediaItem.streamUrl
        val previousProgress = _watchHistory.value.find { it.mediaId == (episode?.id ?: mediaItem.id) }
        val startPos = previousProgress?.positionMs ?: 0L

        playerWrapper.play(urlToPlay, startPos)
        startPositionTracker()
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
    }

    // --- Favorites ---
    fun toggleFavorite(item: MediaItem) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.toggleFavorite(item)
            _favorites.value = repository.getFavorites()
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
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveUserSettings(newSettings)
        }
        _userSettings.value = newSettings
        playerWrapper.initPlayer(newSettings.bufferSize)
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

    override fun onCleared() {
        super.onCleared()
        progressTrackingJob?.cancel()
        syncJob?.cancel()
        playerWrapper.release()
    }
}
