package com.example.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@OptIn(UnstableApi::class)
class ExoPlayerWrapper(private val context: Context) {

    val trackSelector = DefaultTrackSelector(context)

    // Configurable buffer load control
    fun createLoadControl(bufferSizeSetting: String): DefaultLoadControl {
        val (minBufferMs, maxBufferMs) = when (bufferSizeSetting) {
            "Aggressive" -> Pair(30_000, 60_000)
            "Standard" -> Pair(10_000, 20_000)
            else -> Pair(20_000, 40_000) // Large default
        }

        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                1500, // min buffer for playback
                3000  // min buffer for playback after rebuffer
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    private var exoPlayer: ExoPlayer? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(true)
    val isBuffering = _isBuffering.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration = _duration.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    private var retryCount = 0
    private var lastMediaUrl: String? = null

    fun getPlayer(): ExoPlayer {
        if (exoPlayer == null) {
            initPlayer("Large")
        }
        return exoPlayer!!
    }

    fun initPlayer(bufferSetting: String = "Large"): ExoPlayer {
        exoPlayer?.release()

        val loadControl = createLoadControl(bufferSetting)
        val player = ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context))
            .build()

        player.addListener(object : Player.Listener {
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
                    }
                    Player.STATE_ENDED -> {
                        _isBuffering.value = false
                        _isPlaying.value = false
                    }
                    Player.STATE_IDLE -> {
                        _isBuffering.value = false
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                _isBuffering.value = false
                if (retryCount < 3 && lastMediaUrl != null) {
                    retryCount++
                    _errorMessage.value = "Reconnecting stream (attempt $retryCount/3)..."
                    player.prepare()
                    player.play()
                } else {
                    _errorMessage.value = "Playback error: ${error.message ?: "Stream unavailable"}"
                }
            }
        })

        exoPlayer = player
        return player
    }

    fun play(url: String, startPositionMs: Long = 0L) {
        lastMediaUrl = url
        retryCount = 0
        _errorMessage.value = null
        _isBuffering.value = true

        val player = getPlayer()
        val mediaItem = Media3Item.Builder()
            .setUri(url)
            .build()

        player.setMediaItem(mediaItem)
        if (startPositionMs > 0L) {
            player.seekTo(startPositionMs)
        }
        player.prepare()
        player.playWhenReady = true
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun seekForward(ms: Long = 10_000L) {
        val current = exoPlayer?.currentPosition ?: 0L
        val dur = exoPlayer?.duration ?: 0L
        val target = if (dur > 0) (current + ms).coerceAtMost(dur) else current + ms
        seekTo(target)
    }

    fun seekBackward(ms: Long = 10_000L) {
        val current = exoPlayer?.currentPosition ?: 0L
        val target = (current - ms).coerceAtLeast(0L)
        seekTo(target)
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        exoPlayer?.playbackParameters = PlaybackParameters(speed)
    }

    fun updatePosition() {
        exoPlayer?.let {
            _currentPosition.value = it.currentPosition
            if (it.duration > 0) {
                _duration.value = it.duration
            }
        }
    }

    fun release() {
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
    }
}
