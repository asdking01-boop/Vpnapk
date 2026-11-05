package com.example.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.R

/**
 * Metadata payload describing the currently playing Live TV channel or VOD item
 * for Android System Media Controls and Samsung One UI Media Panel.
 */
data class PlaybackNotificationMetadata(
    val mediaId: String = "",
    val title: String = "KRAS TV",
    val subtitle: String = "KRAS TV • Live TV",
    val artworkUrl: String = "",
    val isLive: Boolean = true
)

/**
 * Lifecycle-aware AndroidX Media3 [MediaSessionService] that owns the single [ExoPlayer] instance,
 * provides foreground media playback with Android & Samsung system media controls, and exposes
 * a [MediaSession] for [androidx.media3.session.MediaController] clients.
 */
@OptIn(UnstableApi::class)
class KrasMediaPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)

        val player = getOrCreateSharedPlayer(applicationContext, currentBufferSetting)
        val forwardingPlayer = KrasSessionForwardingPlayer(player)
        activeForwardingPlayer = forwardingPlayer

        val sessionActivityPendingIntent = createContentPendingIntent(this)

        val notificationProvider = DefaultMediaNotificationProvider.Builder(applicationContext)
            .setChannelId(NOTIFICATION_CHANNEL_ID)
            .setChannelName(R.string.app_name)
            .build().apply {
                setSmallIcon(R.drawable.ic_launcher_foreground)
            }
        setMediaNotificationProvider(notificationProvider)

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setId(MEDIA_SESSION_ID)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCallback(KrasMediaSessionCallback())
            .build()

        activeServiceInstance = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXPLICIT_STOP) {
            mainHandler.post {
                onExplicitStopCallback?.invoke()
                sharedExoPlayer?.stop()
                sharedExoPlayer?.clearMediaItems()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null ||
            !player.playWhenReady ||
            player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED ||
            !backgroundPlaybackEnabled
        ) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        activeServiceInstance = null
        mediaSession?.run {
            release()
        }
        mediaSession = null
        activeForwardingPlayer = null
        // Release the underlying ExoPlayer if no active playback is running
        val player = sharedExoPlayer
        if (player != null && !player.isPlaying && !player.playWhenReady) {
            releaseSharedPlayer()
        }
        super.onDestroy()
    }

    private inner class KrasMediaSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val baseResult = super.onConnect(session, controller)
            val playerCommands = buildSupportedPlayerCommands(baseResult.availablePlayerCommands)
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(baseResult.availableSessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }
    }

    /**
     * ForwardingPlayer that maps system/Samsung media notification Previous & Next actions
     * directly to KRAS TV Live channel switching when a channel list is available, and removes
     * Previous/Next commands when channel switching is not applicable so no non-functional buttons appear.
     */
    internal class KrasSessionForwardingPlayer(
        val delegatePlayer: ExoPlayer
    ) : ForwardingPlayer(delegatePlayer) {

        override fun getAvailableCommands(): Player.Commands {
            return buildSupportedPlayerCommands(super.getAvailableCommands())
        }

        override fun isCommandAvailable(command: Int): Boolean {
            return when (command) {
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                    canSkipChannelPreviousNext || super.isCommandAvailable(command)
                }
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP -> true
                else -> super.isCommandAvailable(command)
            }
        }

        override fun hasPreviousMediaItem(): Boolean {
            return canSkipChannelPreviousNext || super.hasPreviousMediaItem()
        }

        override fun hasNextMediaItem(): Boolean {
            return canSkipChannelPreviousNext || super.hasNextMediaItem()
        }

        override fun seekToPrevious() {
            val prevCb = onSkipToPreviousCallback
            if (canSkipChannelPreviousNext && prevCb != null) {
                mainHandler.post { prevCb.invoke() }
            } else {
                super.seekToPrevious()
            }
        }

        override fun seekToPreviousMediaItem() {
            val prevCb = onSkipToPreviousCallback
            if (canSkipChannelPreviousNext && prevCb != null) {
                mainHandler.post { prevCb.invoke() }
            } else {
                super.seekToPreviousMediaItem()
            }
        }

        override fun seekToNext() {
            val nextCb = onSkipToNextCallback
            if (canSkipChannelPreviousNext && nextCb != null) {
                mainHandler.post { nextCb.invoke() }
            } else {
                super.seekToNext()
            }
        }

        override fun seekToNextMediaItem() {
            val nextCb = onSkipToNextCallback
            if (canSkipChannelPreviousNext && nextCb != null) {
                mainHandler.post { nextCb.invoke() }
            } else {
                super.seekToNextMediaItem()
            }
        }

        override fun stop() {
            super.stop()
            mainHandler.post {
                onExplicitStopCallback?.invoke()
            }
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "kras_tv_media_playback"
        const val MEDIA_SESSION_ID = "kras_tv_primary_media_session"
        const val EXTRA_FROM_MEDIA_NOTIFICATION = "extra_from_media_notification"
        const val ACTION_EXPLICIT_STOP = "com.example.player.ACTION_EXPLICIT_STOP"

        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var sharedExoPlayer: ExoPlayer? = null

        @Volatile
        private var sharedTrackSelector: DefaultTrackSelector? = null

        @Volatile
        private var currentBufferSetting: String = "Large"

        @Volatile
        private var activeServiceInstance: KrasMediaPlaybackService? = null

        @Volatile
        private var activeForwardingPlayer: KrasSessionForwardingPlayer? = null

        @Volatile
        var backgroundPlaybackEnabled: Boolean = true

        @Volatile
        var canSkipChannelPreviousNext: Boolean = false
            private set

        @Volatile
        var onSkipToPreviousCallback: (() -> Unit)? = null

        @Volatile
        var onSkipToNextCallback: (() -> Unit)? = null

        @Volatile
        var onExplicitStopCallback: (() -> Unit)? = null

        @Volatile
        var currentMetadata: PlaybackNotificationMetadata = PlaybackNotificationMetadata()
            private set

        fun getOrCreateTrackSelector(context: Context): DefaultTrackSelector {
            val existing = sharedTrackSelector
            if (existing != null) return existing
            synchronized(this) {
                val again = sharedTrackSelector
                if (again != null) return again
                val created = DefaultTrackSelector(context.applicationContext)
                sharedTrackSelector = created
                return created
            }
        }

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

        /**
         * Returns the single shared [ExoPlayer] instance owned by [KrasMediaPlaybackService].
         * Never creates a duplicate player when entering or leaving the background.
         */
        @Synchronized
        fun getOrCreateSharedPlayer(
            context: Context,
            bufferSetting: String = currentBufferSetting,
            onNewPlayerCreated: ((ExoPlayer) -> Unit)? = null
        ): ExoPlayer {
            val appContext = context.applicationContext
            val existing = sharedExoPlayer
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

            val dataSourceFactory = DefaultDataSource.Factory(appContext, httpDataSourceFactory)
            val loadControl = createLoadControl(bufferSetting)
            val selector = getOrCreateTrackSelector(appContext)

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()

            val player = ExoPlayer.Builder(appContext)
                .setTrackSelector(selector)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build()

            sharedExoPlayer = player
            onNewPlayerCreated?.invoke(player)

            // If MediaSession is already active, rebind it to the updated single ExoPlayer
            activeServiceInstance?.mediaSession?.let { session ->
                val forwarding = KrasSessionForwardingPlayer(player)
                activeForwardingPlayer = forwarding
                session.player = forwarding
            }

            return player
        }

        fun peekSharedPlayer(): ExoPlayer? = sharedExoPlayer

        @Synchronized
        fun releaseSharedPlayer() {
            try {
                sharedExoPlayer?.stop()
                sharedExoPlayer?.release()
            } catch (_: Exception) {
            } finally {
                sharedExoPlayer = null
            }
        }

        fun updateChannelNavigationSupport(
            canSkipPrevNext: Boolean,
            onPrevious: (() -> Unit)?,
            onNext: (() -> Unit)?,
            onStop: (() -> Unit)? = onExplicitStopCallback
        ) {
            val changed = canSkipChannelPreviousNext != canSkipPrevNext
            canSkipChannelPreviousNext = canSkipPrevNext
            onSkipToPreviousCallback = onPrevious
            onSkipToNextCallback = onNext
            if (onStop != null) {
                onExplicitStopCallback = onStop
            }
            if (changed) {
                refreshSessionCommands()
            }
        }

        fun updateNotificationMetadata(metadata: PlaybackNotificationMetadata) {
            currentMetadata = metadata
            val player = sharedExoPlayer ?: return
            mainHandler.post {
                try {
                    if (player.mediaItemCount > 0) {
                        val currentItem = player.currentMediaItem
                        val currentUri = currentItem?.localConfiguration?.uri
                        if (currentUri != null) {
                            val updatedItem = buildMedia3Item(currentUri.toString(), metadata)
                            player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        fun buildMedia3Item(
            url: String,
            metadata: PlaybackNotificationMetadata = currentMetadata
        ): Media3Item {
            val title = metadata.title.ifBlank { "KRAS TV" }
            val subtitle = metadata.subtitle.ifBlank {
                if (metadata.isLive) "KRAS TV • Live TV" else "KRAS TV"
            }

            val metaBuilder = MediaMetadata.Builder()
                .setTitle(title)
                .setDisplayTitle(title)
                .setArtist("KRAS TV")
                .setSubtitle(subtitle)
                .setAlbumTitle(subtitle)
                .setStation("KRAS TV")
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(
                    if (metadata.isLive) MediaMetadata.MEDIA_TYPE_TV_CHANNEL else MediaMetadata.MEDIA_TYPE_VIDEO
                )

            if (metadata.artworkUrl.isNotBlank()) {
                runCatching { Uri.parse(metadata.artworkUrl.trim()) }.getOrNull()?.let { uri ->
                    metaBuilder.setArtworkUri(uri)
                }
            }

            return Media3Item.Builder()
                .setMediaId(metadata.mediaId.ifBlank { url })
                .setUri(url)
                .setMediaMetadata(metaBuilder.build())
                .build()
        }

        private fun buildSupportedPlayerCommands(baseCommands: Player.Commands): Player.Commands {
            val builder = baseCommands.buildUpon()
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_STOP)
                .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_GET_METADATA)

            if (canSkipChannelPreviousNext) {
                builder
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            } else {
                builder
                    .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            }
            return builder.build()
        }

        private fun refreshSessionCommands() {
            val service = activeServiceInstance ?: return
            val session = service.mediaSession ?: return
            mainHandler.post {
                try {
                    val base = session.player.availableCommands
                    val updatedPlayerCommands = buildSupportedPlayerCommands(base)
                    session.connectedControllers.forEach { controller ->
                        session.setAvailableCommands(
                            controller,
                            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,
                            updatedPlayerCommands
                        )
                    }
                } catch (_: Exception) {
                }
            }
        }

        fun ensureServiceStarted(context: Context) {
            try {
                val appContext = context.applicationContext
                val intent = Intent(appContext, KrasMediaPlaybackService::class.java)
                appContext.startService(intent)
            } catch (_: Exception) {
            }
        }

        fun stopServiceIfIdle(context: Context) {
            try {
                val appContext = context.applicationContext
                val intent = Intent(appContext, KrasMediaPlaybackService::class.java)
                appContext.stopService(intent)
            } catch (_: Exception) {
            }
        }

        fun createSessionComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, KrasMediaPlaybackService::class.java)
        }

        fun createContentPendingIntent(context: Context): PendingIntent {
            val appContext = context.applicationContext
            val intent = Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_FROM_MEDIA_NOTIFICATION, true)
            }
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            return PendingIntent.getActivity(appContext, 1001, intent, pendingFlags)
        }

        private fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (nm != null && nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        context.getString(R.string.app_name),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "KRAS TV Live TV and Media Playback Controls"
                        setShowBadge(false)
                    }
                    nm.createNotificationChannel(channel)
                }
            }
        }
    }
}
