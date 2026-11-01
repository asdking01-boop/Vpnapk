package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.api.XtreamClient
import com.example.data.local.KrasRepository
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

class KrasViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = KrasRepository(application)
    private val apiClient = XtreamClient()
    val playerWrapper = ExoPlayerWrapper(application)

    // Accounts
    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts = _accounts.asStateFlow()

    private val _activeAccount = MutableStateFlow<Account?>(null)
    val activeAccount = _activeAccount.asStateFlow()

    // Data lists
    private val _liveCategories = MutableStateFlow<List<Category>>(emptyList())
    val liveCategories = _liveCategories.asStateFlow()

    private val _liveStreams = MutableStateFlow<List<MediaItem>>(emptyList())
    val liveStreams = _liveStreams.asStateFlow()

    private val _movieCategories = MutableStateFlow<List<Category>>(emptyList())
    val movieCategories = _movieCategories.asStateFlow()

    private val _movies = MutableStateFlow<List<MediaItem>>(emptyList())
    val movies = _movies.asStateFlow()

    private val _seriesCategories = MutableStateFlow<List<Category>>(emptyList())
    val seriesCategories = _seriesCategories.asStateFlow()

    private val _series = MutableStateFlow<List<MediaItem>>(emptyList())
    val series = _series.asStateFlow()

    // Active Series Details
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

    // Active Playback State
    private val _nowPlayingItem = MutableStateFlow<MediaItem?>(null)
    val nowPlayingItem = _nowPlayingItem.asStateFlow()

    private val _nowPlayingEpisode = MutableStateFlow<Episode?>(null)
    val nowPlayingEpisode = _nowPlayingEpisode.asStateFlow()

    private var progressTrackingJob: Job? = null

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

    private fun loadInitialData() {
        viewModelScope.launch {
            try {
                // Ensure all disk reading happens on Dispatchers.IO
                val (loadedAccounts, active, favs, history, dlList, cachedMovies, cachedSeries, cachedLive, cachedMovieCats, cachedSeriesCats, cachedLiveCats) =
                    withContext(Dispatchers.IO) {
                        val accs = repository.getAccounts()
                        val act = repository.getActiveAccount()
                        val f = repository.getFavorites()
                        val h = repository.getWatchHistory()
                        val d = repository.getDownloads()
                        val cm = repository.getCachedMediaList("cached_movies")
                        val cs = repository.getCachedMediaList("cached_series")
                        val cl = repository.getCachedMediaList("cached_live")
                        val cmc = repository.getCachedCategories("cached_movie_cats")
                        val csc = repository.getCachedCategories("cached_series_cats")
                        val clc = repository.getCachedCategories("cached_live_cats")
                        Tuple11(accs, act, f, h, d, cm, cs, cl, cmc, csc, clc)
                    }

                _accounts.value = loadedAccounts
                _activeAccount.value = active
                _favorites.value = favs
                _watchHistory.value = history
                _downloads.value = dlList

                // Initialize player with buffer setting
                playerWrapper.initPlayer(_userSettings.value.bufferSize)

                val hasCache = cachedMovies.isNotEmpty() || cachedSeries.isNotEmpty() || cachedLive.isNotEmpty()
                if (hasCache) {
                    _movies.value = cachedMovies
                    _series.value = cachedSeries
                    _liveStreams.value = cachedLive
                    if (cachedMovieCats.isNotEmpty()) _movieCategories.value = cachedMovieCats
                    if (cachedSeriesCats.isNotEmpty()) _seriesCategories.value = cachedSeriesCats
                    if (cachedLiveCats.isNotEmpty()) _liveCategories.value = cachedLiveCats
                    _isLoading.value = false
                } else if (active.isDemo) {
                    _liveCategories.value = SampleData.liveCategories
                    _liveStreams.value = SampleData.sampleLiveStreams
                    _movieCategories.value = SampleData.movieCategories
                    _movies.value = SampleData.sampleMovies
                    _seriesCategories.value = SampleData.seriesCategories
                    _series.value = SampleData.sampleSeries
                    _isLoading.value = false
                } else {
                    _isLoading.value = true
                }

                // Refresh in background
                loadContentForAccount(active, isBackgroundRefresh = hasCache)
            } catch (e: Exception) {
                e.printStackTrace()
                // Graceful fallback to sample data if anything fails
                _liveCategories.value = SampleData.liveCategories
                _liveStreams.value = SampleData.sampleLiveStreams
                _movieCategories.value = SampleData.movieCategories
                _movies.value = SampleData.sampleMovies
                _seriesCategories.value = SampleData.seriesCategories
                _series.value = SampleData.sampleSeries
                _isLoading.value = false
            }
        }
    }

    private data class Tuple11<A, B, C, D, E, F, G, H, I, J, K>(
        val a: A, val b: B, val c: C, val d: D, val e: E, val f: F, val g: G, val h: H, val i: I, val j: J, val k: K
    )

    fun switchAccount(accountId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.setActiveAccount(accountId)
                }
                _accounts.value = withContext(Dispatchers.IO) { repository.getAccounts() }
                val active = withContext(Dispatchers.IO) { repository.getActiveAccount() }
                _activeAccount.value = active
                loadContentForAccount(active)
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
                    loadContentForAccount(account)
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
                loadContentForAccount(SampleData.demoAccount)
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
                }
                _accounts.value = withContext(Dispatchers.IO) { repository.getAccounts() }
                val active = withContext(Dispatchers.IO) { repository.getActiveAccount() }
                _activeAccount.value = active
                loadContentForAccount(active)
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

    private suspend fun loadContentForAccount(account: Account, isBackgroundRefresh: Boolean = false) {
        if (!isBackgroundRefresh) {
            _isLoading.value = true
        }
        _errorMessage.value = null

        if (account.isDemo) {
            _liveCategories.value = SampleData.liveCategories
            _liveStreams.value = SampleData.sampleLiveStreams
            _movieCategories.value = SampleData.movieCategories
            _movies.value = SampleData.sampleMovies
            _seriesCategories.value = SampleData.seriesCategories
            _series.value = SampleData.sampleSeries

            withContext(Dispatchers.IO) {
                try {
                    repository.cacheMediaList("cached_movies", SampleData.sampleMovies)
                    repository.cacheMediaList("cached_series", SampleData.sampleSeries)
                    repository.cacheMediaList("cached_live", SampleData.sampleLiveStreams)
                    repository.cacheCategories("cached_movie_cats", SampleData.movieCategories)
                    repository.cacheCategories("cached_series_cats", SampleData.seriesCategories)
                    repository.cacheCategories("cached_live_cats", SampleData.liveCategories)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            _isLoading.value = false
            return
        }

        try {
            // Load from Xtream Codes server
            val liveCats = apiClient.getLiveCategories(account)
            val liveItems = apiClient.getLiveStreams(account)
            val vodCats = apiClient.getVodCategories(account)
            val vodItems = apiClient.getVodStreams(account)
            val seriesCats = apiClient.getSeriesCategories(account)
            val seriesItems = apiClient.getSeries(account)

            val finalLiveCats = if (liveCats.isNotEmpty()) liveCats else SampleData.liveCategories
            val finalLiveItems = if (liveItems.isNotEmpty()) liveItems else SampleData.sampleLiveStreams
            val finalVodCats = if (vodCats.isNotEmpty()) vodCats else SampleData.movieCategories
            val finalMovies = if (vodItems.isNotEmpty()) vodItems else SampleData.sampleMovies
            val finalSeriesCats = if (seriesCats.isNotEmpty()) seriesCats else SampleData.seriesCategories
            val finalSeries = if (seriesItems.isNotEmpty()) seriesItems else SampleData.sampleSeries

            _liveCategories.value = finalLiveCats
            _liveStreams.value = finalLiveItems
            _movieCategories.value = finalVodCats
            _movies.value = finalMovies
            _seriesCategories.value = finalSeriesCats
            _series.value = finalSeries

            withContext(Dispatchers.IO) {
                try {
                    repository.cacheMediaList("cached_movies", finalMovies)
                    repository.cacheMediaList("cached_series", finalSeries)
                    repository.cacheMediaList("cached_live", finalLiveItems)
                    repository.cacheCategories("cached_movie_cats", finalVodCats)
                    repository.cacheCategories("cached_series_cats", finalSeriesCats)
                    repository.cacheCategories("cached_live_cats", finalLiveCats)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            _errorMessage.value = "Failed to update from server: ${e.message}"
            if (_movies.value.isEmpty()) {
                _liveCategories.value = SampleData.liveCategories
                _liveStreams.value = SampleData.sampleLiveStreams
                _movieCategories.value = SampleData.movieCategories
                _movies.value = SampleData.sampleMovies
                _seriesCategories.value = SampleData.seriesCategories
                _series.value = SampleData.sampleSeries
            }
        } finally {
            _isLoading.value = false
        }
    }

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

    // Playback control
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
                            seasonNum = ep?.seasonNumber,
                            episodeNum = ep?.episodeNum,
                            streamUrl = ep?.streamUrl ?: item.streamUrl
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
        playerWrapper.release()
        _nowPlayingItem.value = null
        _nowPlayingEpisode.value = null
    }

    fun toggleFavorite(item: MediaItem): Boolean {
        return try {
            val result = repository.toggleFavorite(item)
            viewModelScope.launch(Dispatchers.IO) {
                _favorites.value = repository.getFavorites()
            }
            result
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun isFavorite(mediaId: String): Boolean {
        return _favorites.value.any { it.mediaId == mediaId }
    }

    // Downloads
    fun downloadItem(item: MediaItem) {
        val existing = _downloads.value.find { it.mediaId == item.id }
        if (existing != null) return

        val newDownload = DownloadItem(
            id = java.util.UUID.randomUUID().toString(),
            mediaId = item.id,
            title = item.name,
            type = item.type,
            posterUrl = item.streamIcon,
            progress = 0.05f,
            status = DownloadStatus.DOWNLOADING,
            fileSizeBytes = 1024L * 1024L * 750L,
            streamUrl = item.streamUrl
        )
        val updated = _downloads.value + newDownload
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveDownloads(updated)
        }
        _downloads.value = updated

        // Simulate download progress
        viewModelScope.launch {
            for (step in 1..10) {
                delay(1000)
                try {
                    val prog = step * 0.1f
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

    // Settings
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
        }
        _watchHistory.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        progressTrackingJob?.cancel()
        playerWrapper.release()
    }
}
