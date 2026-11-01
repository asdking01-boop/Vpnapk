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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
                CinematicSoundPlayer.playCinematicIntro()
            }
        }
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            _accounts.value = repository.getAccounts()
            val active = repository.getActiveAccount()
            _activeAccount.value = active
            _favorites.value = repository.getFavorites()
            _watchHistory.value = repository.getWatchHistory()
            _downloads.value = repository.getDownloads()

            // Initialize player with buffer setting
            playerWrapper.initPlayer(_userSettings.value.bufferSize)

            loadContentForAccount(active)
        }
    }

    fun switchAccount(accountId: String) {
        viewModelScope.launch {
            repository.setActiveAccount(accountId)
            _accounts.value = repository.getAccounts()
            val active = repository.getActiveAccount()
            _activeAccount.value = active
            loadContentForAccount(active)
        }
    }

    fun login(serverUrl: String, user: String, pass: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            val result = apiClient.authenticate(serverUrl, user, pass)
            result.onSuccess { account ->
                repository.addAccount(account)
                _accounts.value = repository.getAccounts()
                _activeAccount.value = account
                loadContentForAccount(account)
                _isLoading.value = false
                onResult(true, null)
            }.onFailure { error ->
                _isLoading.value = false
                _errorMessage.value = error.message
                onResult(false, error.message)
            }
        }
    }

    fun loginDemoMode() {
        viewModelScope.launch {
            repository.addAccount(SampleData.demoAccount)
            _accounts.value = repository.getAccounts()
            _activeAccount.value = SampleData.demoAccount
            loadContentForAccount(SampleData.demoAccount)
        }
    }

    fun removeAccount(accountId: String) {
        viewModelScope.launch {
            repository.removeAccount(accountId)
            _accounts.value = repository.getAccounts()
            val active = repository.getActiveAccount()
            _activeAccount.value = active
            loadContentForAccount(active)
        }
    }

    fun refreshContent() {
        val active = _activeAccount.value ?: return
        viewModelScope.launch {
            _isRefreshing.value = true
            loadContentForAccount(active)
            _isRefreshing.value = false
        }
    }

    private suspend fun loadContentForAccount(account: Account) {
        _isLoading.value = true
        _errorMessage.value = null

        if (account.isDemo) {
            // Load rich curated demo catalog
            _liveCategories.value = SampleData.liveCategories
            _liveStreams.value = SampleData.sampleLiveStreams
            _movieCategories.value = SampleData.movieCategories
            _movies.value = SampleData.sampleMovies
            _seriesCategories.value = SampleData.seriesCategories
            _series.value = SampleData.sampleSeries
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

            _liveCategories.value = if (liveCats.isNotEmpty()) liveCats else SampleData.liveCategories
            _liveStreams.value = if (liveItems.isNotEmpty()) liveItems else SampleData.sampleLiveStreams
            _movieCategories.value = if (vodCats.isNotEmpty()) vodCats else SampleData.movieCategories
            _movies.value = if (vodItems.isNotEmpty()) vodItems else SampleData.sampleMovies
            _seriesCategories.value = if (seriesCats.isNotEmpty()) seriesCats else SampleData.seriesCategories
            _series.value = if (seriesItems.isNotEmpty()) seriesItems else SampleData.sampleSeries
        } catch (e: Exception) {
            _errorMessage.value = "Failed to fetch from server: ${e.message}"
            // Fallback to sample data so app continues running
            _liveCategories.value = SampleData.liveCategories
            _liveStreams.value = SampleData.sampleLiveStreams
            _movieCategories.value = SampleData.movieCategories
            _movies.value = SampleData.sampleMovies
            _seriesCategories.value = SampleData.seriesCategories
            _series.value = SampleData.sampleSeries
        } finally {
            _isLoading.value = false
        }
    }

    fun loadSeriesDetails(seriesItem: MediaItem) {
        viewModelScope.launch {
            _isLoading.value = true
            val active = _activeAccount.value
            if (active != null && !active.isDemo) {
                val details = apiClient.getSeriesDetails(active, seriesItem.streamId)
                if (details != null) {
                    _currentSeriesDetails.value = details
                    _isLoading.value = false
                    return@launch
                }
            }
            // Fallback to rich sample series structure
            _currentSeriesDetails.value = SampleData.getSampleSeriesDetails(seriesItem.streamId)
            _isLoading.value = false
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
                    repository.saveWatchProgress(progress)
                    _watchHistory.value = repository.getWatchHistory()
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
        val result = repository.toggleFavorite(item)
        _favorites.value = repository.getFavorites()
        return result
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
            fileSizeBytes = 1024L * 1024L * 750L, // 750MB
            streamUrl = item.streamUrl
        )
        val updated = _downloads.value + newDownload
        repository.saveDownloads(updated)
        _downloads.value = updated

        // Simulate background download progress
        viewModelScope.launch {
            for (step in 1..10) {
                delay(1000)
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
                repository.saveDownloads(list)
                _downloads.value = list
            }
        }
    }

    // Settings
    fun updateSettings(newSettings: UserSettings) {
        repository.saveUserSettings(newSettings)
        _userSettings.value = newSettings
        playerWrapper.initPlayer(newSettings.bufferSize)
    }

    fun clearCache() {
        repository.clearCache()
        _watchHistory.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        progressTrackingJob?.cancel()
        playerWrapper.release()
    }
}
