package com.example.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.example.data.local.QualityDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Unified Media3 ExoPlayer UI state machine.
 */
enum class PlayerUiState {
    PREPARING,
    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR
}

data class VideoQualityVariant(
    val label: String,        // "Auto", "1080p", "720p", "480p", etc.
    val height: Int,          // 0 for Auto
    val width: Int = 0,
    val frameRate: Float = 0f,
    val bitrate: Int = 0,
    val groupIndex: Int = -1,
    val trackIndex: Int = -1
)

data class PlayerTrackOption(
    val id: String,
    val label: String,
    val language: String = "",
    val groupIndex: Int = -1,
    val trackIndex: Int = -1,
    val isOff: Boolean = false
)

@OptIn(UnstableApi::class)
class ExoPlayerWrapper(private val context: Context) {

    val trackSelector = DefaultTrackSelector(context)

    // Callback invoked whenever real video height/tracks are detected during playback
    var onRealQualityDetected: ((String) -> Unit)? = null

    // Configurable buffer load control tuned for fast initial start & smooth streaming
    fun createLoadControl(bufferSizeSetting: String): DefaultLoadControl {
        val (minBufferMs, maxBufferMs) = when (bufferSizeSetting) {
            "Aggressive" -> Pair(25_000, 50_000)
            "Standard" -> Pair(10_000, 20_000)
            else -> Pair(15_000, 35_000) // Large default
        }

        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                1000, // Fast start once 1s is buffered
                2000  // Rebuffer recovery threshold
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    private var exoPlayer: ExoPlayer? = null
    private var currentBufferSetting: String = "Large"

    private val _playerUiState = MutableStateFlow(PlayerUiState.PREPARING)
    val playerUiState = _playerUiState.asStateFlow()

    private val _hasRenderedFirstFrame = MutableStateFlow(false)
    val hasRenderedFirstFrame = _hasRenderedFirstFrame.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering = _isBuffering.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration = _duration.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    // Real detected video info for player overlay (e.g., "1080p 50 FPS")
    private val _realResolutionOverlay = MutableStateFlow("")
    val realResolutionOverlay = _realResolutionOverlay.asStateFlow()

    // Real detected canonical quality badge (e.g., "FHD | HEVC | 50 FPS")
    private val _detectedQualityBadge = MutableStateFlow("")
    val detectedQualityBadge = _detectedQualityBadge.asStateFlow()

    // Available HLS/manifest video quality variants ("Auto", "1080p", "720p", "480p", etc.)
    private val autoVariant = VideoQualityVariant(label = "Auto", height = 0)
    private val _availableVideoVariants = MutableStateFlow(listOf(autoVariant))
    val availableVideoVariants = _availableVideoVariants.asStateFlow()

    private val _selectedVideoVariant = MutableStateFlow(autoVariant)
    val selectedVideoVariant = _selectedVideoVariant.asStateFlow()

    // Audio & Subtitle tracks from Media3
    private val defaultAudioOption = PlayerTrackOption(id = "default_audio", label = "Original Audio (Default)")
    private val _availableAudioTracks = MutableStateFlow(listOf(defaultAudioOption))
    val availableAudioTracks = _availableAudioTracks.asStateFlow()

    private val _selectedAudioTrack = MutableStateFlow(defaultAudioOption)
    val selectedAudioTrack = _selectedAudioTrack.asStateFlow()

    private val offSubtitleOption = PlayerTrackOption(id = "sub_off", label = "Off", isOff = true)
    private val _availableSubtitleTracks = MutableStateFlow(listOf(offSubtitleOption))
    val availableSubtitleTracks = _availableSubtitleTracks.asStateFlow()

    private val _selectedSubtitleTrack = MutableStateFlow(offSubtitleOption)
    val selectedSubtitleTrack = _selectedSubtitleTrack.asStateFlow()

    private var retryCount = 0
    private var lastMediaUrl: String? = null
    private var currentVideoHeight = 0
    private var currentFrameRate = 0f
    private var currentVideoCodec = ""
    private var currentAudioChannels = 0
    private var wasPlayingBeforeLifecycleStop = false

    fun getPlayer(): ExoPlayer {
        if (exoPlayer == null) {
            initPlayer(currentBufferSetting)
        }
        return exoPlayer!!
    }

    fun initPlayer(bufferSetting: String = "Large"): ExoPlayer {
        val existing = exoPlayer
        if (existing != null && currentBufferSetting == bufferSetting) {
            return existing
        }
        currentBufferSetting = bufferSetting
        existing?.release()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("KrasTV/2.0 (Android; Media3 ExoPlayer)")
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)

        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val loadControl = createLoadControl(bufferSetting)

        val player = ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()

        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                _hasRenderedFirstFrame.value = true
                _isBuffering.value = false
                _errorMessage.value = null
                syncPlayerUiState(player)
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        _isBuffering.value = true
                    }
                    Player.STATE_READY -> {
                        _isBuffering.value = false
                        _duration.value = player.duration.coerceAtLeast(0L)
                        _errorMessage.value = null
                        retryCount = 0
                        // If stream is audio-only (no selected video track), mark first frame ready immediately
                        val hasSelectedVideo = player.currentTracks.groups.any {
                            it.type == C.TRACK_TYPE_VIDEO && it.isSelected
                        }
                        if (!hasSelectedVideo) {
                            _hasRenderedFirstFrame.value = true
                        }
                        inspectPlayerFormatsAndNotify(player)
                    }
                    Player.STATE_ENDED -> {
                        _isBuffering.value = false
                        _isPlaying.value = false
                    }
                    Player.STATE_IDLE -> {
                        _isBuffering.value = false
                    }
                }
                syncPlayerUiState(player)
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
                if (playing) {
                    _errorMessage.value = null
                }
                syncPlayerUiState(player)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                syncPlayerUiState(player)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                try {
                    if (videoSize.height > 0) {
                        currentVideoHeight = videoSize.height
                        inspectPlayerFormatsAndNotify(player)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                try {
                    extractTracksAndVariants(player, tracks)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (retryCount < 2 && !lastMediaUrl.isNullOrBlank()) {
                    retryCount++
                    _isBuffering.value = true
                    _playerUiState.value = if (_hasRenderedFirstFrame.value) {
                        PlayerUiState.BUFFERING
                    } else {
                        PlayerUiState.PREPARING
                    }
                    val resumePos = _currentPosition.value.coerceAtLeast(0L)
                    if (resumePos > 0L) {
                        player.seekTo(resumePos)
                    }
                    player.prepare()
                    player.playWhenReady = true
                } else {
                    _isBuffering.value = false
                    _isPlaying.value = false
                    _errorMessage.value = formatPlaybackError(error)
                    _playerUiState.value = PlayerUiState.ERROR
                }
            }
        })

        exoPlayer = player
        return player
    }

    private fun syncPlayerUiState(player: ExoPlayer) {
        if (_errorMessage.value != null) {
            _playerUiState.value = PlayerUiState.ERROR
            return
        }

        _playerUiState.value = when (player.playbackState) {
            Player.STATE_ENDED -> PlayerUiState.ENDED
            Player.STATE_BUFFERING -> {
                if (_hasRenderedFirstFrame.value) {
                    PlayerUiState.BUFFERING
                } else {
                    PlayerUiState.PREPARING
                }
            }
            Player.STATE_READY -> {
                if (player.isPlaying || player.playWhenReady) {
                    PlayerUiState.PLAYING
                } else {
                    PlayerUiState.PAUSED
                }
            }
            Player.STATE_IDLE -> {
                if (_hasRenderedFirstFrame.value) {
                    PlayerUiState.PAUSED
                } else {
                    PlayerUiState.PREPARING
                }
            }
            else -> PlayerUiState.PREPARING
        }
    }

    private fun formatPlaybackError(error: PlaybackException): String {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "Network connection timed out. Please check your connection and try again."
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "This stream is temporarily unavailable from the server."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FAILED ->
                "Unsupported video format or stream decoding error."
            else -> "Unable to play stream (${error.errorCodeName.lowercase().replace('_', ' ')})."
        }
    }

    private fun extractTracksAndVariants(player: ExoPlayer, tracks: Tracks) {
        try {
            val variantsByHeight = LinkedHashMap<Int, VideoQualityVariant>()
            val audioOptions = mutableListOf<PlayerTrackOption>()
            val subtitleOptions = mutableListOf(offSubtitleOption)
            var activeAudioOption: PlayerTrackOption? = null
            var activeSubtitleOption: PlayerTrackOption? = null
            var selectedTrackHeight = 0
            var selectedTrackFps = 0f
            var selectedCodec = ""
            var selectedAudioCh = 0

            tracks.groups.forEachIndexed { groupIndex, group ->
                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> {
                        for (trackIndex in 0 until group.length) {
                            val format = group.getTrackFormat(trackIndex)
                            val h = format.height
                            val w = format.width
                            val fps = if (format.frameRate > 0f) format.frameRate else 0f
                            val br = if (format.bitrate > 0) format.bitrate else 0

                            if (h > 0) {
                                val label = "${h}p"
                                val existing = variantsByHeight[h]
                                if (existing == null || br > existing.bitrate) {
                                    variantsByHeight[h] = VideoQualityVariant(
                                        label = label,
                                        height = h,
                                        width = w,
                                        frameRate = fps,
                                        bitrate = br,
                                        groupIndex = groupIndex,
                                        trackIndex = trackIndex
                                    )
                                }
                            }

                            if (group.isTrackSelected(trackIndex)) {
                                if (h > 0) selectedTrackHeight = h
                                if (fps > 0f) selectedTrackFps = fps
                                val mime = format.sampleMimeType.orEmpty()
                                val codecs = format.codecs.orEmpty()
                                selectedCodec = "$mime $codecs".trim()
                            }
                        }
                    }
                    C.TRACK_TYPE_AUDIO -> {
                        for (trackIndex in 0 until group.length) {
                            val format = group.getTrackFormat(trackIndex)
                            val ch = format.channelCount
                            val lang = format.language?.takeIf { it.isNotBlank() && it != "und" }?.uppercase() ?: "Track ${audioOptions.size + 1}"
                            val chLabel = when {
                                ch >= 6 -> "5.1 Surround"
                                ch == 2 -> "Stereo 2.0"
                                ch == 1 -> "Mono"
                                else -> ""
                            }
                            val label = listOfNotNull(
                                format.label?.takeIf { it.isNotBlank() },
                                lang,
                                chLabel.takeIf { it.isNotBlank() }
                            ).distinct().joinToString(" • ").ifBlank { "Audio Track ${audioOptions.size + 1}" }

                            val option = PlayerTrackOption(
                                id = "audio_${groupIndex}_$trackIndex",
                                label = label,
                                language = lang,
                                groupIndex = groupIndex,
                                trackIndex = trackIndex
                            )
                            audioOptions.add(option)

                            if (group.isTrackSelected(trackIndex)) {
                                if (ch > 0) {
                                    selectedAudioCh = ch
                                }
                                activeAudioOption = option
                            }
                        }
                    }
                    C.TRACK_TYPE_TEXT -> {
                        for (trackIndex in 0 until group.length) {
                            val format = group.getTrackFormat(trackIndex)
                            val lang = format.language?.takeIf { it.isNotBlank() && it != "und" }?.uppercase() ?: "CC ${subtitleOptions.size}"
                            val label = listOfNotNull(
                                format.label?.takeIf { it.isNotBlank() },
                                lang
                            ).distinct().joinToString(" • ").ifBlank { "Subtitle ${subtitleOptions.size}" }

                            val option = PlayerTrackOption(
                                id = "sub_${groupIndex}_$trackIndex",
                                label = label,
                                language = lang,
                                groupIndex = groupIndex,
                                trackIndex = trackIndex
                            )
                            subtitleOptions.add(option)

                            if (group.isTrackSelected(trackIndex)) {
                                activeSubtitleOption = option
                            }
                        }
                    }
                }
            }

            val sortedVariants = variantsByHeight.values.sortedByDescending { it.height }
            _availableVideoVariants.value = listOf(autoVariant) + sortedVariants

            if (audioOptions.isNotEmpty()) {
                _availableAudioTracks.value = audioOptions
                if (activeAudioOption != null) {
                    _selectedAudioTrack.value = activeAudioOption!!
                }
            } else {
                _availableAudioTracks.value = listOf(defaultAudioOption)
            }

            _availableSubtitleTracks.value = subtitleOptions
            if (activeSubtitleOption != null && !_selectedSubtitleTrack.value.isOff) {
                _selectedSubtitleTrack.value = activeSubtitleOption!!
            }

            if (selectedTrackHeight > 0) currentVideoHeight = selectedTrackHeight
            if (selectedTrackFps > 0f) currentFrameRate = selectedTrackFps
            if (selectedCodec.isNotBlank()) currentVideoCodec = selectedCodec
            if (selectedAudioCh > 0) currentAudioChannels = selectedAudioCh

            inspectPlayerFormatsAndNotify(player)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun inspectPlayerFormatsAndNotify(player: ExoPlayer) {
        try {
            val vFormat = player.videoFormat
            val aFormat = player.audioFormat

            val height = when {
                player.videoSize.height > 0 -> player.videoSize.height
                vFormat != null && vFormat.height > 0 -> vFormat.height
                else -> currentVideoHeight
            }
            if (height <= 0) return
            currentVideoHeight = height

            val fps = when {
                vFormat != null && vFormat.frameRate > 0f -> vFormat.frameRate
                else -> currentFrameRate
            }
            if (fps > 0f) currentFrameRate = fps

            val codecStr = if (vFormat != null) {
                "${vFormat.sampleMimeType.orEmpty()} ${vFormat.codecs.orEmpty()}".trim()
            } else {
                currentVideoCodec
            }
            if (codecStr.isNotBlank()) currentVideoCodec = codecStr

            val audioCh = if (aFormat != null && aFormat.channelCount > 0) {
                aFormat.channelCount
            } else {
                currentAudioChannels
            }
            if (audioCh > 0) currentAudioChannels = audioCh

            val overlayText = QualityDetector.formatPlayerResolutionOverlay(height, fps)
            _realResolutionOverlay.value = overlayText

            // Also ensure current height is present in availableVideoVariants if stream is progressive
            if (_availableVideoVariants.value.size <= 1 && height > 0) {
                val singleVariant = VideoQualityVariant(
                    label = "${height}p",
                    height = height,
                    width = player.videoSize.width,
                    frameRate = fps
                )
                _availableVideoVariants.value = listOf(autoVariant, singleVariant)
            }

            val canonicalQuality = QualityDetector.buildFromRealSpecs(
                height = height,
                frameRate = fps,
                codecOrMime = codecStr,
                audioChannels = audioCh
            )
            if (canonicalQuality.isNotBlank()) {
                _detectedQualityBadge.value = canonicalQuality
                onRealQualityDetected?.invoke(canonicalQuality)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun selectAudioTrack(option: PlayerTrackOption) {
        try {
            _selectedAudioTrack.value = option
            val player = exoPlayer ?: return
            val builder = trackSelector.parameters.buildUpon()
            builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            if (option.groupIndex >= 0 && option.trackIndex >= 0) {
                val group = player.currentTracks.groups.getOrNull(option.groupIndex)
                if (group != null && group.type == C.TRACK_TYPE_AUDIO && option.trackIndex in 0 until group.length) {
                    builder.setOverrideForType(
                        TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex))
                    )
                }
            }
            trackSelector.setParameters(builder)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun selectSubtitleTrack(option: PlayerTrackOption) {
        try {
            _selectedSubtitleTrack.value = option
            val player = exoPlayer ?: return
            val builder = trackSelector.parameters.buildUpon()
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
            if (option.isOff) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                if (option.groupIndex >= 0 && option.trackIndex >= 0) {
                    val group = player.currentTracks.groups.getOrNull(option.groupIndex)
                    if (group != null && group.type == C.TRACK_TYPE_TEXT && option.trackIndex in 0 until group.length) {
                        builder.setOverrideForType(
                            TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex))
                        )
                    }
                }
            }
            trackSelector.setParameters(builder)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun selectVideoQuality(variant: VideoQualityVariant) {
        try {
            _selectedVideoVariant.value = variant
            val player = exoPlayer ?: return
            val builder = trackSelector.parameters.buildUpon()
            if (variant.height <= 0 || variant.label.equals("Auto", ignoreCase = true)) {
                builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                builder.clearVideoSizeConstraints()
            } else {
                builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                val groups = player.currentTracks.groups
                val targetGroup = groups.getOrNull(variant.groupIndex)
                if (targetGroup != null && targetGroup.type == C.TRACK_TYPE_VIDEO &&
                    variant.trackIndex in 0 until targetGroup.length
                ) {
                    builder.setOverrideForType(
                        TrackSelectionOverride(targetGroup.mediaTrackGroup, listOf(variant.trackIndex))
                    )
                } else {
                    builder.setMaxVideoSize(Int.MAX_VALUE, variant.height)
                }
            }
            trackSelector.setParameters(builder)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Enters initial PREPARING state before or while resolving stream URL.
     */
    fun setPreparingState() {
        retryCount = 0
        _hasRenderedFirstFrame.value = false
        _errorMessage.value = null
        _isBuffering.value = true
        _isPlaying.value = false
        _playerUiState.value = PlayerUiState.PREPARING
    }

    /**
     * Fails gracefully into ERROR state so the user is never left on an infinite spinner.
     */
    fun failWithError(message: String) {
        exoPlayer?.stop()
        _isBuffering.value = false
        _isPlaying.value = false
        _errorMessage.value = message
        _playerUiState.value = PlayerUiState.ERROR
    }

    /**
     * Fallback invoked when Player is in STATE_READY and playing on a surface that did not emit onRenderedFirstFrame.
     */
    fun markFirstFrameRenderedFallback() {
        if (!_hasRenderedFirstFrame.value &&
            (_playerUiState.value == PlayerUiState.PLAYING || _playerUiState.value == PlayerUiState.PAUSED)
        ) {
            _hasRenderedFirstFrame.value = true
        }
    }

    fun play(url: String, startPositionMs: Long = 0L) {
        if (url.isBlank()) {
            failWithError("Stream URL is unavailable for this title.")
            return
        }

        val isSameUrl = (lastMediaUrl == url && exoPlayer != null && _errorMessage.value == null)
        lastMediaUrl = url
        retryCount = 0
        currentVideoHeight = 0
        currentFrameRate = 0f
        currentVideoCodec = ""
        currentAudioChannels = 0
        _realResolutionOverlay.value = ""
        _detectedQualityBadge.value = ""
        _availableVideoVariants.value = listOf(autoVariant)
        _selectedVideoVariant.value = autoVariant
        _availableAudioTracks.value = listOf(defaultAudioOption)
        _selectedAudioTrack.value = defaultAudioOption
        _availableSubtitleTracks.value = listOf(offSubtitleOption)
        _selectedSubtitleTrack.value = offSubtitleOption
        _errorMessage.value = null
        _hasRenderedFirstFrame.value = false
        _isBuffering.value = true
        _currentPosition.value = startPositionMs.coerceAtLeast(0L)
        _duration.value = 0L
        _playerUiState.value = PlayerUiState.PREPARING

        val player = getPlayer()
        if (!isSameUrl || player.mediaItemCount == 0) {
            val mediaItem = Media3Item.Builder()
                .setUri(url)
                .build()
            player.setMediaItem(mediaItem)
        }
        if (startPositionMs > 0L) {
            player.seekTo(startPositionMs)
        }
        player.playWhenReady = true
        player.prepare()
    }

    /**
     * Retries the current stream inside the same player instance and screen.
     */
    fun retry() {
        val url = lastMediaUrl
        if (url.isNullOrBlank()) {
            failWithError("No stream URL available to retry.")
            return
        }
        retryCount = 0
        _errorMessage.value = null
        _isBuffering.value = true
        _playerUiState.value = if (_hasRenderedFirstFrame.value) {
            PlayerUiState.BUFFERING
        } else {
            PlayerUiState.PREPARING
        }

        val player = getPlayer()
        if (player.mediaItemCount == 0) {
            player.setMediaItem(Media3Item.fromUri(url))
        }
        val resumePos = _currentPosition.value.coerceAtLeast(0L)
        if (resumePos > 0L) {
            player.seekTo(resumePos)
        }
        player.playWhenReady = true
        player.prepare()
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        _currentPosition.value = target
        exoPlayer?.seekTo(target)
    }

    fun seekForward(ms: Long = 10_000L) {
        val current = exoPlayer?.currentPosition ?: _currentPosition.value
        val dur = exoPlayer?.duration ?: _duration.value
        val target = if (dur > 0) (current + ms).coerceAtMost(dur) else current + ms
        seekTo(target)
    }

    fun seekBackward(ms: Long = 10_000L) {
        val current = exoPlayer?.currentPosition ?: _currentPosition.value
        val target = (current - ms).coerceAtLeast(0L)
        seekTo(target)
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (_playerUiState.value == PlayerUiState.ERROR) {
            retry()
            return
        }
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0L)
            player.playWhenReady = true
            player.play()
            return
        }
        if (player.isPlaying) {
            player.pause()
        } else {
            player.playWhenReady = true
            player.play()
        }
    }

    fun pauseForLifecycle() {
        val player = exoPlayer ?: return
        wasPlayingBeforeLifecycleStop = player.isPlaying || player.playWhenReady
        if (player.isPlaying) {
            player.pause()
        }
    }

    fun resumeForLifecycle() {
        val player = exoPlayer ?: return
        if (wasPlayingBeforeLifecycleStop && _playerUiState.value != PlayerUiState.ERROR) {
            player.playWhenReady = true
            player.play()
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        exoPlayer?.playbackParameters = PlaybackParameters(speed)
    }

    fun updatePosition() {
        exoPlayer?.let {
            _currentPosition.value = it.currentPosition.coerceAtLeast(0L)
            if (it.duration > 0) {
                _duration.value = it.duration
            }
        }
    }

    fun stop() {
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        lastMediaUrl = null
        _isPlaying.value = false
        _isBuffering.value = false
        _hasRenderedFirstFrame.value = false
        _errorMessage.value = null
        _playerUiState.value = PlayerUiState.PREPARING
    }

    fun release() {
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
    }
}
