package com.example.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.local.QualityDetector
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToLong

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

sealed class SeekResult {
    data class Seeked(val targetPositionMs: Long, val durationMs: Long) : SeekResult()
    data object NotSeekable : SeekResult()
}

@OptIn(UnstableApi::class)
class ExoPlayerWrapper(private val context: Context) {

    companion object {
        const val SEEK_SETTLE_TOLERANCE_MS = 2_500L
        const val SEEK_SETTLE_TIMEOUT_MS = 3_000L

        fun isPlayerSeekable(
            isCommandAvailable: Boolean,
            isCurrentMediaItemSeekable: Boolean,
            durationMs: Long
        ): Boolean {
            return isCommandAvailable &&
                isCurrentMediaItemSeekable &&
                durationMs != C.TIME_UNSET &&
                durationMs > 0L
        }

        fun computeSeekTargetMs(targetMs: Long, durationMs: Long): Long {
            return if (durationMs != C.TIME_UNSET && durationMs > 0L) {
                targetMs.coerceIn(0L, durationMs)
            } else {
                targetMs.coerceAtLeast(0L)
            }
        }

        fun computeRelativeSeekTargetMs(
            currentPositionMs: Long,
            deltaMs: Long,
            durationMs: Long
        ): Long {
            val safeCurrent = currentPositionMs.coerceAtLeast(0L)
            val rawTarget = safeCurrent + deltaMs
            return computeSeekTargetMs(rawTarget, durationMs)
        }

        fun computeFractionSeekTargetMs(fraction: Float, durationMs: Long): Long {
            if (durationMs == C.TIME_UNSET || durationMs <= 0L) return 0L
            val clamped = fraction.coerceIn(0f, 1f)
            return (clamped.toDouble() * durationMs.toDouble())
                .roundToLong()
                .coerceIn(0L, durationMs)
        }
    }

    private val appContext = context.applicationContext

    val trackSelector: DefaultTrackSelector
        get() = KrasMediaPlaybackService.getOrCreateTrackSelector(appContext)

    // Callback invoked whenever real video height/tracks are detected during playback
    var onRealQualityDetected: ((String) -> Unit)? = null

    fun createLoadControl(bufferSizeSetting: String): DefaultLoadControl {
        return KrasMediaPlaybackService.createLoadControl(bufferSizeSetting)
    }

    private var exoPlayer: ExoPlayer? = null
    private var currentBufferSetting: String = "Large"
    private var attachedListenerPlayer: ExoPlayer? = null

    // MediaController connected to KrasMediaPlaybackService
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val _mediaController = MutableStateFlow<MediaController?>(null)
    val mediaController = _mediaController.asStateFlow()

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

    private val _isSeekable = MutableStateFlow(false)
    val isSeekable = _isSeekable.asStateFlow()

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
    private var isBackgroundAudioOnly = false

    @Volatile
    private var pendingSeekTargetMs: Long? = null

    @Volatile
    private var pendingSeekTimestampMs: Long = 0L

    @Volatile
    private var isUserScrubbing: Boolean = false

    private val playerListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            _hasRenderedFirstFrame.value = true
            _isBuffering.value = false
            _errorMessage.value = null
            exoPlayer?.let {
                refreshSeekabilityAndDuration(it)
                syncPlayerUiState(it)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            val player = exoPlayer ?: return
            refreshSeekabilityAndDuration(player)
            when (state) {
                Player.STATE_BUFFERING -> {
                    _isBuffering.value = true
                }
                Player.STATE_READY -> {
                    _isBuffering.value = false
                    _errorMessage.value = null
                    retryCount = 0
                    syncPositionWithPendingGuard(player)
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
                    syncPositionWithPendingGuard(player)
                }
                Player.STATE_IDLE -> {
                    _isBuffering.value = false
                }
            }
            syncPlayerUiState(player)
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            exoPlayer?.let { refreshSeekabilityAndDuration(it) }
        }

        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
            exoPlayer?.let { refreshSeekabilityAndDuration(it) }
        }

        override fun onMediaItemTransition(mediaItem: Media3Item?, reason: Int) {
            pendingSeekTargetMs = null
            pendingSeekTimestampMs = 0L
            exoPlayer?.let { refreshSeekabilityAndDuration(it) }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            val player = exoPlayer ?: return
            refreshSeekabilityAndDuration(player)
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                val resolvedPos = newPosition.positionMs.coerceAtLeast(0L)
                if (reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                    pendingSeekTargetMs = resolvedPos
                    pendingSeekTimestampMs = System.currentTimeMillis()
                }
                if (!isUserScrubbing) {
                    _currentPosition.value = resolvedPos
                }
            }
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            _isPlaying.value = playing
            if (playing) {
                _errorMessage.value = null
            }
            exoPlayer?.let { syncPlayerUiState(it) }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            exoPlayer?.let { syncPlayerUiState(it) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val player = exoPlayer ?: return
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
            val player = exoPlayer ?: return
            try {
                extractTracksAndVariants(player, tracks)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val player = exoPlayer ?: return
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
    }

    /**
     * Returns the single shared [ExoPlayer] instance owned by [KrasMediaPlaybackService].
     */
    fun getPlayer(): ExoPlayer {
        val current = exoPlayer
        if (current != null) {
            return current
        }
        return initPlayer(currentBufferSetting)
    }

    /**
     * Returns the connected [MediaController] when available, or the underlying [ExoPlayer]
     * so UI components (`PlayerView`) are always bound to the active MediaSession / single ExoPlayer.
     */
    fun getActivePlayer(): Player {
        return _mediaController.value ?: getPlayer()
    }

    /**
     * Connects to [KrasMediaPlaybackService] via [MediaController] if not already connected.
     */
    fun ensureMediaControllerConnected() {
        if (_mediaController.value != null || controllerFuture != null) return
        try {
            val sessionToken = SessionToken(
                appContext,
                KrasMediaPlaybackService.createSessionComponentName(appContext)
            )
            val future = MediaController.Builder(appContext, sessionToken).buildAsync()
            controllerFuture = future
            future.addListener(
                {
                    try {
                        _mediaController.value = future.get()
                    } catch (_: Exception) {
                        controllerFuture = null
                    }
                },
                MoreExecutors.directExecutor()
            )
        } catch (_: Exception) {
            controllerFuture = null
        }
    }

    fun initPlayer(bufferSetting: String = "Large"): ExoPlayer {
        val existing = exoPlayer
        if (existing != null && currentBufferSetting == bufferSetting &&
            KrasMediaPlaybackService.peekSharedPlayer() === existing
        ) {
            return existing
        }
        currentBufferSetting = bufferSetting

        val player = KrasMediaPlaybackService.getOrCreateSharedPlayer(
            context = appContext,
            bufferSetting = bufferSetting
        )

        if (attachedListenerPlayer !== player) {
            attachedListenerPlayer?.removeListener(playerListener)
            player.addListener(playerListener)
            attachedListenerPlayer = player
        }

        exoPlayer = player
        refreshSeekabilityAndDuration(player)
        ensureMediaControllerConnected()
        return player
    }

    @androidx.annotation.VisibleForTesting
    internal fun attachPlayerForTesting(player: ExoPlayer) {
        if (attachedListenerPlayer !== player) {
            attachedListenerPlayer?.removeListener(playerListener)
            player.addListener(playerListener)
            attachedListenerPlayer = player
        }
        exoPlayer = player
        refreshSeekabilityAndDuration(player)
    }

    private fun refreshSeekabilityAndDuration(player: Player) {
        val rawDuration = player.duration
        val validDuration = if (rawDuration != C.TIME_UNSET && rawDuration > 0L) rawDuration else 0L
        if (validDuration > 0L) {
            _duration.value = validDuration
        } else if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE) {
            _duration.value = 0L
        } else if (rawDuration == C.TIME_UNSET && player.playbackState == Player.STATE_READY) {
            // Live / unbounded stream with unknown duration once ready
            _duration.value = 0L
        }

        val effectiveDuration = if (validDuration > 0L) validDuration else _duration.value
        val canSeek = player.mediaItemCount > 0 &&
            player.playbackState != Player.STATE_IDLE &&
            isPlayerSeekable(
                isCommandAvailable = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
                isCurrentMediaItemSeekable = player.isCurrentMediaItemSeekable,
                durationMs = effectiveDuration
            )
        _isSeekable.value = canSeek
    }

    private fun syncPositionWithPendingGuard(player: Player) {
        if (isUserScrubbing) return
        val rawPos = player.currentPosition.coerceAtLeast(0L)
        val pending = pendingSeekTargetMs
        if (pending != null) {
            val elapsed = System.currentTimeMillis() - pendingSeekTimestampMs
            val settledNearTarget = abs(rawPos - pending) <= SEEK_SETTLE_TOLERANCE_MS
            val timedOut = elapsed >= SEEK_SETTLE_TIMEOUT_MS
            val isReadyAndSettled = player.playbackState == Player.STATE_READY && (settledNearTarget || timedOut)
            val isEndedAfterSeek = player.playbackState == Player.STATE_ENDED && (settledNearTarget || timedOut)
            if (isReadyAndSettled || isEndedAfterSeek || (timedOut && player.playbackState != Player.STATE_BUFFERING)) {
                pendingSeekTargetMs = null
                pendingSeekTimestampMs = 0L
                _currentPosition.value = rawPos
            } else {
                _currentPosition.value = pending
            }
            return
        }
        _currentPosition.value = rawPos
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
     * Enables or disables background audio-only decoding when Live TV continues playing
     * while the Activity is in the background (Home pressed or screen locked).
     * Disabling the video renderer while backgrounded prevents SurfaceView codec stalls
     * and saves battery while keeping Live TV audio uninterrupted.
     */
    fun setBackgroundAudioOnlyMode(audioOnly: Boolean) {
        if (isBackgroundAudioOnly == audioOnly) return
        isBackgroundAudioOnly = audioOnly
        try {
            val builder = trackSelector.parameters.buildUpon()
            builder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)
            trackSelector.setParameters(builder)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isInBackgroundAudioOnlyMode(): Boolean = isBackgroundAudioOnly

    /**
     * Enters initial PREPARING state before or while resolving stream URL.
     */
    fun setPreparingState() {
        retryCount = 0
        pendingSeekTargetMs = null
        pendingSeekTimestampMs = 0L
        isUserScrubbing = false
        _hasRenderedFirstFrame.value = false
        _errorMessage.value = null
        _isBuffering.value = true
        _isPlaying.value = false
        _isSeekable.value = false
        _duration.value = 0L
        _currentPosition.value = 0L
        _playerUiState.value = PlayerUiState.PREPARING
    }

    /**
     * Fails gracefully into ERROR state so the user is never left on an infinite spinner.
     */
    fun failWithError(message: String) {
        pendingSeekTargetMs = null
        pendingSeekTimestampMs = 0L
        isUserScrubbing = false
        _isSeekable.value = false
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

    fun play(
        url: String,
        startPositionMs: Long = 0L,
        metadata: PlaybackNotificationMetadata? = null,
        keepLastFrameUntilReady: Boolean = false
    ) {
        if (url.isBlank()) {
            failWithError("Stream URL is unavailable for this title.")
            return
        }

        if (metadata != null) {
            KrasMediaPlaybackService.updateNotificationMetadata(metadata)
        }
        KrasMediaPlaybackService.ensureServiceStarted(appContext)
        ensureMediaControllerConnected()

        if (isBackgroundAudioOnly) {
            setBackgroundAudioOnlyMode(false)
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
        if (!keepLastFrameUntilReady || !_hasRenderedFirstFrame.value) {
            _hasRenderedFirstFrame.value = false
        }
        _isBuffering.value = true
        val clampedStart = startPositionMs.coerceAtLeast(0L)
        _currentPosition.value = clampedStart
        _duration.value = 0L
        _isSeekable.value = false
        isUserScrubbing = false
        if (clampedStart > 0L) {
            pendingSeekTargetMs = clampedStart
            pendingSeekTimestampMs = System.currentTimeMillis()
        } else {
            pendingSeekTargetMs = null
            pendingSeekTimestampMs = 0L
        }
        _playerUiState.value = if (keepLastFrameUntilReady && _hasRenderedFirstFrame.value) {
            PlayerUiState.BUFFERING
        } else {
            PlayerUiState.PREPARING
        }

        val player = getPlayer()
        val effectiveMetadata = metadata ?: KrasMediaPlaybackService.currentMetadata
        if (!isSameUrl || player.mediaItemCount == 0) {
            val mediaItem = KrasMediaPlaybackService.buildMedia3Item(url, effectiveMetadata)
            player.setMediaItem(mediaItem, clampedStart)
        } else if (metadata != null && player.mediaItemCount > 0) {
            val mediaItem = KrasMediaPlaybackService.buildMedia3Item(url, metadata)
            player.replaceMediaItem(player.currentMediaItemIndex, mediaItem)
            if (clampedStart > 0L) {
                player.seekTo(clampedStart)
            }
        } else if (clampedStart > 0L) {
            player.seekTo(clampedStart)
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
            player.setMediaItem(KrasMediaPlaybackService.buildMedia3Item(url))
        }
        val resumePos = _currentPosition.value.coerceAtLeast(0L)
        if (resumePos > 0L) {
            player.seekTo(resumePos)
        }
        player.playWhenReady = true
        player.prepare()
    }

    fun setScrubbing(scrubbing: Boolean) {
        isUserScrubbing = scrubbing
    }

    fun canSeekCurrentMedia(): Boolean {
        val player = exoPlayer ?: return false
        refreshSeekabilityAndDuration(player)
        val rawDuration = player.duration
        val effectiveDuration = if (rawDuration != C.TIME_UNSET && rawDuration > 0L) rawDuration else _duration.value
        return player.mediaItemCount > 0 &&
            player.playbackState != Player.STATE_IDLE &&
            isPlayerSeekable(
                isCommandAvailable = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
                isCurrentMediaItemSeekable = player.isCurrentMediaItemSeekable,
                durationMs = effectiveDuration
            )
    }

    fun seekTo(positionMs: Long): SeekResult {
        val player = exoPlayer ?: return SeekResult.NotSeekable
        refreshSeekabilityAndDuration(player)

        val rawDuration = player.duration
        val effectiveDuration = if (rawDuration != C.TIME_UNSET && rawDuration > 0L) rawDuration else _duration.value
        val canSeek = player.mediaItemCount > 0 &&
            player.playbackState != Player.STATE_IDLE &&
            isPlayerSeekable(
                isCommandAvailable = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
                isCurrentMediaItemSeekable = player.isCurrentMediaItemSeekable,
                durationMs = effectiveDuration
            )
        if (!canSeek) {
            return SeekResult.NotSeekable
        }

        val target = computeSeekTargetMs(positionMs, effectiveDuration)
        pendingSeekTargetMs = target
        pendingSeekTimestampMs = System.currentTimeMillis()
        _duration.value = effectiveDuration
        _currentPosition.value = target
        player.seekTo(target)
        return SeekResult.Seeked(targetPositionMs = target, durationMs = effectiveDuration)
    }

    fun seekToFraction(fraction: Float): SeekResult {
        val player = exoPlayer ?: return SeekResult.NotSeekable
        refreshSeekabilityAndDuration(player)

        val rawDuration = player.duration
        val effectiveDuration = if (rawDuration != C.TIME_UNSET && rawDuration > 0L) rawDuration else _duration.value
        val canSeek = player.mediaItemCount > 0 &&
            player.playbackState != Player.STATE_IDLE &&
            isPlayerSeekable(
                isCommandAvailable = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
                isCurrentMediaItemSeekable = player.isCurrentMediaItemSeekable,
                durationMs = effectiveDuration
            )
        if (!canSeek) {
            return SeekResult.NotSeekable
        }

        val target = computeFractionSeekTargetMs(fraction, effectiveDuration)
        return seekTo(target)
    }

    fun seekForward(ms: Long = 10_000L): SeekResult {
        val player = exoPlayer ?: return SeekResult.NotSeekable
        refreshSeekabilityAndDuration(player)

        val rawDuration = player.duration
        val effectiveDuration = if (rawDuration != C.TIME_UNSET && rawDuration > 0L) rawDuration else _duration.value
        val canSeek = player.mediaItemCount > 0 &&
            player.playbackState != Player.STATE_IDLE &&
            isPlayerSeekable(
                isCommandAvailable = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
                isCurrentMediaItemSeekable = player.isCurrentMediaItemSeekable,
                durationMs = effectiveDuration
            )
        if (!canSeek) {
            return SeekResult.NotSeekable
        }

        val basePos = resolveBaseSeekPosition(player)
        val target = computeRelativeSeekTargetMs(basePos, ms.coerceAtLeast(0L), effectiveDuration)
        return seekTo(target)
    }

    fun seekBackward(ms: Long = 10_000L): SeekResult {
        val player = exoPlayer ?: return SeekResult.NotSeekable
        refreshSeekabilityAndDuration(player)

        val rawDuration = player.duration
        val effectiveDuration = if (rawDuration != C.TIME_UNSET && rawDuration > 0L) rawDuration else _duration.value
        val canSeek = player.mediaItemCount > 0 &&
            player.playbackState != Player.STATE_IDLE &&
            isPlayerSeekable(
                isCommandAvailable = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
                isCurrentMediaItemSeekable = player.isCurrentMediaItemSeekable,
                durationMs = effectiveDuration
            )
        if (!canSeek) {
            return SeekResult.NotSeekable
        }

        val basePos = resolveBaseSeekPosition(player)
        val target = computeRelativeSeekTargetMs(basePos, -ms.coerceAtLeast(0L), effectiveDuration)
        return seekTo(target)
    }

    private fun resolveBaseSeekPosition(player: ExoPlayer): Long {
        val rawPos = player.currentPosition.coerceAtLeast(0L)
        val pending = pendingSeekTargetMs ?: return rawPos
        val elapsed = System.currentTimeMillis() - pendingSeekTimestampMs
        val isRecentUnsettledSeek = elapsed < SEEK_SETTLE_TIMEOUT_MS &&
            (player.playbackState == Player.STATE_BUFFERING || abs(rawPos - pending) > SEEK_SETTLE_TOLERANCE_MS)
        return if (isRecentUnsettledSeek) pending else rawPos
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (_playerUiState.value == PlayerUiState.ERROR) {
            retry()
            return
        }
        if (player.playbackState == Player.STATE_ENDED) {
            pendingSeekTargetMs = 0L
            pendingSeekTimestampMs = System.currentTimeMillis()
            _currentPosition.value = 0L
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
        if (player.isPlaying || player.playWhenReady) {
            player.pause()
        }
    }

    fun resumeForLifecycle() {
        val player = exoPlayer ?: return
        if (wasPlayingBeforeLifecycleStop && _playerUiState.value != PlayerUiState.ERROR) {
            wasPlayingBeforeLifecycleStop = false
            player.playWhenReady = true
            player.play()
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        exoPlayer?.playbackParameters = PlaybackParameters(speed)
    }

    fun updatePosition() {
        val player = exoPlayer ?: return
        refreshSeekabilityAndDuration(player)
        syncPositionWithPendingGuard(player)
    }

    fun stop() {
        wasPlayingBeforeLifecycleStop = false
        pendingSeekTargetMs = null
        pendingSeekTimestampMs = 0L
        isUserScrubbing = false
        if (isBackgroundAudioOnly) {
            setBackgroundAudioOnlyMode(false)
        }
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        lastMediaUrl = null
        _isPlaying.value = false
        _isBuffering.value = false
        _isSeekable.value = false
        _duration.value = 0L
        _currentPosition.value = 0L
        _hasRenderedFirstFrame.value = false
        _errorMessage.value = null
        _playerUiState.value = PlayerUiState.PREPARING
    }

    fun release() {
        wasPlayingBeforeLifecycleStop = false
        pendingSeekTargetMs = null
        pendingSeekTimestampMs = 0L
        isUserScrubbing = false
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        _mediaController.value = null
        attachedListenerPlayer?.removeListener(playerListener)
        attachedListenerPlayer = null
        exoPlayer = null
        KrasMediaPlaybackService.releaseSharedPlayer()
    }
}
