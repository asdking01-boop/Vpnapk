package com.example.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.provider.Settings
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.data.local.KrasRepository
import com.example.data.model.*
import com.example.player.ExoPlayerWrapper
import com.example.player.PlayerUiState
import com.example.player.VideoQualityVariant
import com.example.player.PlayerImmersiveHelper
import com.example.ui.components.KrasLogo
import com.example.ui.components.ResolutionBadge
import com.example.ui.components.liquidGlassPanel
import com.example.ui.components.liquidPressClickable
import com.example.ui.theme.*
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    mediaItem: MediaItem,
    episode: Episode?,
    playerWrapper: ExoPlayerWrapper,
    userSettings: UserSettings = UserSettings(),
    onClose: () -> Unit,
    liveChannels: List<MediaItem>,
    onSwitchChannel: (MediaItem) -> Unit,
    onDownloadClick: () -> Unit,
    modifier: Modifier = Modifier,
    seriesDetails: SeriesDetails? = null,
    moviesPlaylist: List<MediaItem> = emptyList(),
    onPlayEpisode: ((Episode) -> Unit)? = null,
    onRetry: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val repository = remember { KrasRepository(context) }

    // Immersive Fullscreen & Do Not Disturb management while playing
    DisposableEffect(Unit) {
        PlayerImmersiveHelper.enterImmersiveMode(activity)
        if (userSettings.silenceNotificationsWhileWatching) {
            PlayerImmersiveHelper.enableDndIfAllowed(context, repository)
        }
        onDispose {
            PlayerImmersiveHelper.exitImmersiveMode(activity)
            if (userSettings.silenceNotificationsWhileWatching) {
                PlayerImmersiveHelper.restoreDnd(context, repository)
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Re-apply immersive fullscreen on Window Focus change
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) {
                PlayerImmersiveHelper.enterImmersiveMode(activity)
            }
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose {
            view.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
        }
    }

    // Lifecycle-aware player & immersive management
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    PlayerImmersiveHelper.enterImmersiveMode(activity)
                    playerWrapper.resumeForLifecycle()
                }
                Lifecycle.Event.ON_STOP -> {
                    if (activity?.isInPictureInPictureMode != true) {
                        playerWrapper.pauseForLifecycle()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val playerUiState by playerWrapper.playerUiState.collectAsState()
    val hasRenderedFirstFrame by playerWrapper.hasRenderedFirstFrame.collectAsState()
    val isPlaying by playerWrapper.isPlaying.collectAsState()
    val isBuffering by playerWrapper.isBuffering.collectAsState()
    val currentPos by playerWrapper.currentPosition.collectAsState()
    val duration by playerWrapper.duration.collectAsState()
    val errorMessage by playerWrapper.errorMessage.collectAsState()
    val realResolutionOverlay by playerWrapper.realResolutionOverlay.collectAsState()
    val detectedQualityBadge by playerWrapper.detectedQualityBadge.collectAsState()
    val availableVariants by playerWrapper.availableVideoVariants.collectAsState()
    val selectedVariant by playerWrapper.selectedVideoVariant.collectAsState()
    val availableAudioTracks by playerWrapper.availableAudioTracks.collectAsState()
    val selectedAudioTrack by playerWrapper.selectedAudioTrack.collectAsState()
    val availableSubtitleTracks by playerWrapper.availableSubtitleTracks.collectAsState()
    val selectedSubtitleTrack by playerWrapper.selectedSubtitleTrack.collectAsState()

    val effectiveQualityString = remember(detectedQualityBadge, episode?.quality, mediaItem.quality, mediaItem.resolution) {
        detectedQualityBadge.ifBlank {
            episode?.quality?.ifBlank { null }
                ?: mediaItem.quality.ifBlank { mediaItem.resolution }
        }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    var currentAspectRatio by remember { mutableStateOf(userSettings.defaultAspectRatio) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var isFullscreenLandscape by remember { mutableStateOf(isLandscape) }

    // Delayed small red spinner: only shown if preparation takes > 600ms (or mid-stream buffering > 350ms),
    // and removed immediately when the first video frame is rendered.
    var showDelayedSpinner by remember { mutableStateOf(false) }

    LaunchedEffect(playerUiState, hasRenderedFirstFrame, mediaItem.id, episode?.id) {
        when {
            playerUiState == PlayerUiState.ERROR -> {
                showDelayedSpinner = false
            }
            playerUiState == PlayerUiState.PREPARING && !hasRenderedFirstFrame -> {
                showDelayedSpinner = false
                delay(600L)
                showDelayedSpinner = true
            }
            playerUiState == PlayerUiState.BUFFERING -> {
                showDelayedSpinner = false
                delay(350L)
                showDelayedSpinner = true
            }
            else -> {
                showDelayedSpinner = false
            }
        }
    }

    // Fallback for software/headless surfaces where STATE_READY is active
    LaunchedEffect(playerUiState, isPlaying, hasRenderedFirstFrame) {
        if ((playerUiState == PlayerUiState.PLAYING || playerUiState == PlayerUiState.PAUSED) && !hasRenderedFirstFrame) {
            delay(250L)
            playerWrapper.markFirstFrameRenderedFallback()
        }
    }

    // Watchdog timeout so the user is never left stuck on an infinite spinner
    LaunchedEffect(playerUiState, hasRenderedFirstFrame, mediaItem.id, episode?.id) {
        if (playerUiState == PlayerUiState.PREPARING && !hasRenderedFirstFrame) {
            delay(25_000L)
            if (playerWrapper.playerUiState.value == PlayerUiState.PREPARING &&
                !playerWrapper.hasRenderedFirstFrame.value
            ) {
                playerWrapper.failWithError("Stream connection timed out. Please try again.")
            }
        }
    }

    // Display controls when playback becomes ready
    var wasPreparing by remember(mediaItem.id, episode?.id) { mutableStateOf(true) }
    LaunchedEffect(playerUiState) {
        if (playerUiState == PlayerUiState.PREPARING) {
            wasPreparing = true
        } else if (wasPreparing && (playerUiState == PlayerUiState.PLAYING || playerUiState == PlayerUiState.PAUSED)) {
            wasPreparing = false
            controlsVisible = true
        }
    }

    // Interactive scrubbing state for smooth timeline seeking
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    // Subtle visual cue when 10s rewind / forward is triggered
    var seekFeedbackText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(seekFeedbackText) {
        if (seekFeedbackText != null) {
            delay(650)
            seekFeedbackText = null
        }
    }

    // Dialog / Sheet states
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showAudioSubtitleDialog by remember { mutableStateOf(false) }
    var showPlaylistDialog by remember { mutableStateOf(false) }
    var showCastDialog by remember { mutableStateOf(false) }

    val anyModalOpen = showSpeedDialog || showQualityDialog || showAudioSubtitleDialog || showPlaylistDialog || showCastDialog

    // Auto-hide controls after 3.5 seconds of inactivity while video is actively playing
    LaunchedEffect(controlsVisible, playerUiState, isPlaying, isScrubbing, anyModalOpen) {
        if (controlsVisible && playerUiState == PlayerUiState.PLAYING && isPlaying && !isScrubbing && !anyModalOpen) {
            delay(3500L)
            controlsVisible = false
        }
    }

    // Gentle breathing red glow for the center Play/Pause button
    val glowTransition = rememberInfiniteTransition(label = "player_red_glow")
    val glowPulse by glowTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_pulse"
    )

    // Subtle fade-in of the video surface when the first video frame is rendered
    val firstFrameShutterAlpha by animateFloatAsState(
        targetValue = if (hasRenderedFirstFrame) 0f else 1f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "first_frame_shutter_alpha"
    )

    val isSpinnerVisible = showDelayedSpinner &&
        playerUiState != PlayerUiState.ERROR &&
        (playerUiState == PlayerUiState.PREPARING || playerUiState == PlayerUiState.BUFFERING || (isBuffering && !hasRenderedFirstFrame))

    val showCenterControls = playerUiState != PlayerUiState.PREPARING &&
        playerUiState != PlayerUiState.BUFFERING &&
        playerUiState != PlayerUiState.ERROR &&
        !isSpinnerVisible

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                controlsVisible = !controlsVisible
            }
            .testTag("player_screen")
    ) {
        // 1. Video Surface (Media3 ExoPlayer PlayerView) - same instance preserved throughout preparation, buffering & playback
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = playerWrapper.getPlayer()
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    resizeMode = when (currentAspectRatio) {
                        AspectRatioMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        AspectRatioMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        AspectRatioMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        AspectRatioMode.RATIO_16_9 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        AspectRatioMode.RATIO_4_3 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                }
            },
            update = { playerView ->
                val activePlayer = playerWrapper.getPlayer()
                if (playerView.player !== activePlayer) {
                    playerView.player = activePlayer
                }
                playerView.resizeMode = when (currentAspectRatio) {
                    AspectRatioMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    AspectRatioMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    AspectRatioMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    AspectRatioMode.RATIO_16_9 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    AspectRatioMode.RATIO_4_3 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Pure black video surface before the first frame is rendered, with subtle fade-out on first frame
        if (firstFrameShutterAlpha > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = firstFrameShutterAlpha }
                    .background(Color.Black)
            )
        }

        // 2. Small Elegant Red Spinner directly over the video surface (shown only if preparation > 600ms or mid-stream buffering > 350ms)
        AnimatedVisibility(
            visible = isSpinnerVisible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.Center)
                .testTag("player_loading_spinner")
        ) {
            CircularProgressIndicator(
                color = KrasRed,
                trackColor = Color.White.copy(alpha = 0.14f),
                strokeWidth = 3.dp,
                modifier = Modifier.size(38.dp)
            )
        }

        // 3. Minimal In-Player Error State with Retry Button (inside the same player screen)
        AnimatedVisibility(
            visible = playerUiState == PlayerUiState.ERROR,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180)),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .widthIn(max = 380.dp)
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = "Playback Error",
                    tint = KrasRedBright,
                    modifier = Modifier.size(36.dp)
                )
                Text(
                    text = errorMessage ?: "Unable to play this stream right now.",
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 14.sp,
                    fontFamily = PlusJakartaSansFontFamily,
                    fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = {
                        if (onRetry != null) {
                            onRetry()
                        } else {
                            playerWrapper.retry()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = KrasRed),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp),
                    modifier = Modifier.testTag("player_retry_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Retry",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 4. Main Cinematic Controls Overlay (Top Bar, Center Controls, Bottom Timeline & Pill Bar)
        AnimatedVisibility(
            visible = controlsVisible || playerUiState == PlayerUiState.ERROR,
            enter = fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(220, easing = FastOutSlowInEasing)),
            modifier = Modifier.fillMaxSize()
        ) {
            val horizontalEdgePadding = if (isLandscape) 28.dp else 18.dp
            val verticalEdgePadding = if (isLandscape) 14.dp else 16.dp

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val topScrim = Brush.verticalGradient(
                            0.0f to Color.Black.copy(alpha = 0.86f),
                            0.26f to Color.Black.copy(alpha = 0.28f),
                            0.50f to Color.Black.copy(alpha = 0.35f),
                            0.74f to Color.Black.copy(alpha = 0.45f),
                            1.0f to Color.Black.copy(alpha = 0.92f)
                        )
                        val ambientRedGlow = Brush.radialGradient(
                            colors = listOf(
                                KrasRed.copy(alpha = 0.14f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.5f, size.height * 0.5f),
                            radius = size.minDimension * 0.55f
                        )
                        onDrawBehind {
                            drawRect(topScrim)
                            drawRect(ambientRedGlow)
                        }
                    }
            ) {
                // =========================================================================
                // TOP CONTROL BAR: Back | KRAS TV Logo + Title & Specs | Cast | CC | Fullscreen
                // =========================================================================
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .displayCutoutPadding()
                        .statusBarsPadding()
                        .padding(horizontal = horizontalEdgePadding, vertical = verticalEdgePadding),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Side: Back Button + KRAS TV Custom Logo + Stream Title & Badges
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        // Back Arrow Button
                        PlayerGlassIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            onClick = onClose,
                            size = 42.dp,
                            iconSize = 20.dp,
                            modifier = Modifier.testTag("player_back_button")
                        )

                        // KRAS TV Custom Wordmark Logo
                        KrasLogo(
                            height = if (isLandscape) 22.dp else 20.dp,
                            modifier = Modifier.testTag("player_kras_logo")
                        )

                        // Subtle Vertical Divider
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(22.dp)
                                .background(Color.White.copy(alpha = 0.22f))
                        )

                        // Media Title + Episode Info + Real Resolution Badge
                        Column(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = episode?.title ?: mediaItem.name,
                                color = Color.White,
                                fontSize = if (isLandscape) 15.sp else 13.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(2.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (episode != null) {
                                    Text(
                                        text = "S${episode.seasonNumber}:E${episode.episodeNum}",
                                        color = KrasRedBright,
                                        fontSize = 10.sp,
                                        fontFamily = OutfitFontFamily,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                ResolutionBadge(
                                    resolution = effectiveQualityString,
                                    isLive = mediaItem.type == MediaType.LIVE,
                                    maxExtraTags = if (isLandscape) 2 else 1
                                )

                                if (realResolutionOverlay.isNotBlank()) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color.White.copy(alpha = 0.14f))
                                            .border(0.5.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 5.dp, vertical = 1.5.dp)
                                            .testTag("player_real_resolution_overlay")
                                    ) {
                                        Text(
                                            text = realResolutionOverlay,
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontFamily = OutfitFontFamily,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    // Right Side: Cast | Subtitles / CC | Aspect Ratio | Fullscreen
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Aspect Ratio Cycle Chip (compact glass pill)
                        Box(
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color(0x9912141E))
                                .border(1.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(18.dp))
                                .liquidPressClickable {
                                    currentAspectRatio = when (currentAspectRatio) {
                                        AspectRatioMode.FIT -> AspectRatioMode.FILL
                                        AspectRatioMode.FILL -> AspectRatioMode.STRETCH
                                        AspectRatioMode.STRETCH -> AspectRatioMode.FIT
                                        else -> AspectRatioMode.FIT
                                    }
                                }
                                .padding(horizontal = 10.dp)
                                .testTag("player_aspect_ratio_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AspectRatio,
                                    contentDescription = "Aspect Ratio",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = currentAspectRatio.name,
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Cast Button
                        PlayerGlassIconButton(
                            icon = Icons.Default.Cast,
                            contentDescription = "Cast to Device",
                            onClick = { showCastDialog = true },
                            size = 40.dp,
                            iconSize = 19.dp,
                            modifier = Modifier.testTag("player_cast_button")
                        )

                        // Subtitle / CC & Audio Button
                        Box {
                            PlayerGlassIconButton(
                                icon = Icons.Default.Subtitles,
                                contentDescription = "Subtitles and Audio",
                                onClick = { showAudioSubtitleDialog = true },
                                size = 40.dp,
                                iconSize = 19.dp,
                                isHighlighted = !selectedSubtitleTrack.isOff,
                                modifier = Modifier.testTag("player_subtitles_button")
                            )
                            if (!selectedSubtitleTrack.isOff) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(3.dp)
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(KrasRedBright)
                                )
                            }
                        }

                        // Fullscreen / Orientation Toggle Button
                        PlayerGlassIconButton(
                            icon = if (isFullscreenLandscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = "Toggle Fullscreen",
                            onClick = {
                                isFullscreenLandscape = !isFullscreenLandscape
                                activity?.requestedOrientation = if (isFullscreenLandscape) {
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                } else {
                                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                }
                                PlayerImmersiveHelper.enterImmersiveMode(activity)
                            },
                            size = 40.dp,
                            iconSize = 21.dp,
                            modifier = Modifier.testTag("player_fullscreen_button")
                        )
                    }
                }

                // =========================================================================
                // CENTER PLAYER CONTROLS: 10s Rewind | Glowing Play/Pause | 10s Forward
                // Hidden during initial preparation and while spinner is active so they never overlap
                // =========================================================================
                AnimatedVisibility(
                    visible = showCenterControls,
                    enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.92f, animationSpec = tween(180)),
                    exit = fadeOut(tween(140)),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(if (isLandscape) 44.dp else 32.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left: Smooth 10-Second Rewind Button
                            CenterSeekButton(
                                icon = Icons.Default.Replay10,
                                contentDescription = "Rewind 10 seconds",
                                onClick = {
                                    playerWrapper.seekBackward(10_000)
                                    seekFeedbackText = "-10s"
                                },
                                modifier = Modifier.testTag("player_rewind_button")
                            )

                            // Center: Large Circular Play/Pause Button with Dark Translucent Glass & Red Glowing Border
                            CenterPlayPauseButton(
                                isPlaying = isPlaying,
                                isEnded = playerUiState == PlayerUiState.ENDED,
                                glowPulse = glowPulse,
                                onClick = { playerWrapper.togglePlayPause() },
                                modifier = Modifier.testTag("player_play_pause_button")
                            )

                            // Right: Smooth 10-Second Forward Button
                            CenterSeekButton(
                                icon = Icons.Default.Forward10,
                                contentDescription = "Forward 10 seconds",
                                onClick = {
                                    playerWrapper.seekForward(10_000)
                                    seekFeedbackText = "+10s"
                                },
                                modifier = Modifier.testTag("player_forward_button")
                            )
                        }

                        // Subtle seek feedback pill (-10s / +10s)
                        AnimatedVisibility(
                            visible = seekFeedbackText != null,
                            enter = fadeIn(tween(120)) + scaleIn(initialScale = 0.85f),
                            exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.9f)
                        ) {
                            if (seekFeedbackText != null) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 10.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(KrasRed.copy(alpha = 0.85f))
                                        .border(0.8.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(50))
                                        .padding(horizontal = 12.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = seekFeedbackText!!,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontFamily = OutfitFontFamily,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                // =========================================================================
                // BOTTOM PANEL: Live Channel Strip (optional) + Timeline + 4 Equal Pill Buttons
                // =========================================================================
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .displayCutoutPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = horizontalEdgePadding, vertical = verticalEdgePadding),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Quick Live TV Channel Carousel when watching Live TV
                    if (mediaItem.type == MediaType.LIVE && liveChannels.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(liveChannels.take(25), key = { it.id }) { ch ->
                                val isSelected = ch.id == mediaItem.id
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            if (isSelected) {
                                                Brush.horizontalGradient(listOf(KrasRed, KrasRedDark))
                                            } else {
                                                Brush.horizontalGradient(
                                                    listOf(
                                                        Color(0x99141824),
                                                        Color(0x990D1018)
                                                    )
                                                )
                                            }
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = if (isSelected) Color.White.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(50)
                                        )
                                        .clickable { onSwitchChannel(ch) }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(Color.White)
                                        )
                                    }
                                    Text(
                                        text = ch.name,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontFamily = OutfitFontFamily,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    // PLAYBACK TIMELINE: Current Time | Thin Red Progress Bar + Smooth Circular Handle | Total Duration
                    val safeDuration = duration.coerceAtLeast(1L)
                    val safePosition = currentPos.coerceIn(0L, safeDuration)
                    val liveProgressFraction = if (mediaItem.type == MediaType.LIVE && duration <= 0L) {
                        1f
                    } else {
                        (safePosition.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
                    }
                    val displayedFraction = if (isScrubbing) scrubFraction else liveProgressFraction
                    val smoothFraction by animateFloatAsState(
                        targetValue = displayedFraction,
                        animationSpec = tween(durationMillis = if (isScrubbing) 0 else 150),
                        label = "timeline_progress"
                    )
                    val displayedTimeMs = if (isScrubbing) {
                        (scrubFraction * safeDuration).toLong()
                    } else {
                        currentPos.coerceAtLeast(0L)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Current playback time on the left
                        Text(
                            text = formatTime(displayedTimeMs),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.widthIn(min = 44.dp)
                        )

                        // Thin elegant progress bar with Red indicator and smooth circular seek handle
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val thumbScale by animateFloatAsState(
                                targetValue = if (isScrubbing) 1.35f else 1.0f,
                                animationSpec = spring(stiffness = Spring.StiffnessMedium),
                                label = "thumb_scale"
                            )

                            // Custom sleek track & glowing circular thumb drawn behind the interactive Slider
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(22.dp)
                                    .drawWithCache {
                                        val trackHeight = 4.dp.toPx()
                                        val trackRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
                                        val activeGrad = Brush.horizontalGradient(
                                            colors = listOf(KrasRedDark, KrasRed, KrasRedBright)
                                        )
                                        onDrawBehind {
                                            val cy = size.height / 2f
                                            val trackTop = cy - trackHeight / 2f
                                            // Inactive track
                                            drawRoundRect(
                                                color = Color.White.copy(alpha = 0.24f),
                                                topLeft = Offset(0f, trackTop),
                                                size = androidx.compose.ui.geometry.Size(size.width, trackHeight),
                                                cornerRadius = trackRadius
                                            )
                                            // Active red progress indicator
                                            val activeWidth = (size.width * smoothFraction).coerceIn(0f, size.width)
                                            if (activeWidth > 0f) {
                                                drawRoundRect(
                                                    brush = activeGrad,
                                                    topLeft = Offset(0f, trackTop),
                                                    size = androidx.compose.ui.geometry.Size(activeWidth, trackHeight),
                                                    cornerRadius = trackRadius
                                                )
                                            }
                                            // Subtle red glow halo around circular seek handle
                                            val thumbX = activeWidth.coerceIn(6.dp.toPx(), (size.width - 6.dp.toPx()).coerceAtLeast(6.dp.toPx()))
                                            drawCircle(
                                                color = KrasRed.copy(alpha = 0.42f),
                                                radius = 10.dp.toPx() * thumbScale,
                                                center = Offset(thumbX, cy)
                                            )
                                            // Crisp circular seek handle with white core & red rim
                                            drawCircle(
                                                color = KrasRedBright,
                                                radius = 6.5.dp.toPx() * thumbScale,
                                                center = Offset(thumbX, cy)
                                            )
                                            drawCircle(
                                                color = Color.White,
                                                radius = 3.dp.toPx() * thumbScale,
                                                center = Offset(thumbX, cy)
                                            )
                                        }
                                    }
                            )

                            // Functional ExoPlayer Slider (transparent colors so our custom track/handle shines while preserving full touch/drag & testTag)
                            Slider(
                                value = displayedFraction,
                                onValueChange = { frac ->
                                    isScrubbing = true
                                    scrubFraction = frac.coerceIn(0f, 1f)
                                    val target = (scrubFraction * safeDuration).toLong()
                                    playerWrapper.seekTo(target)
                                },
                                onValueChangeFinished = {
                                    isScrubbing = false
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.Transparent,
                                    activeTrackColor = Color.Transparent,
                                    inactiveTrackColor = Color.Transparent
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("player_seek_bar")
                            )
                        }

                        // Total duration on the right (or LIVE badge if live stream has no fixed duration)
                        if (mediaItem.type == MediaType.LIVE && duration <= 0L) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(KrasRed.copy(alpha = 0.22f))
                                    .border(0.8.dp, KrasRedBright.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(KrasRedBright)
                                )
                                Text(
                                    text = "LIVE",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        } else {
                            Text(
                                text = formatTime(safeDuration),
                                color = TextSecondary,
                                fontSize = 12.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.widthIn(min = 44.dp)
                            )
                        }
                    }

                    // =====================================================================
                    // BOTTOM SETTINGS BAR: 4 Equally Sized Rounded Pill Buttons
                    // 1. Speed (1.0x) | 2. Quality (Auto) | 3. Download | 4. Playlist/Episodes
                    // =====================================================================
                    val qualityPillLabel = remember(selectedVariant, realResolutionOverlay) {
                        if (selectedVariant.label.equals("Auto", ignoreCase = true) && realResolutionOverlay.isNotBlank()) {
                            "Auto (${realResolutionOverlay.substringBefore(" ")})"
                        } else {
                            selectedVariant.label
                        }
                    }

                    val playlistPillLabel = remember(mediaItem.type) {
                        when (mediaItem.type) {
                            MediaType.SERIES -> "Episodes"
                            MediaType.LIVE -> "Channels"
                            MediaType.MOVIE -> "Playlist"
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Playback Speed Pill Button
                        BottomSettingPillButton(
                            icon = Icons.Default.Speed,
                            label = "${playbackSpeed}x",
                            onClick = { showSpeedDialog = true },
                            isActive = playbackSpeed != 1.0f,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("player_speed_button")
                        )

                        // 2. Video Quality Pill Button
                        BottomSettingPillButton(
                            icon = Icons.Default.HighQuality,
                            label = qualityPillLabel,
                            onClick = { showQualityDialog = true },
                            isActive = !selectedVariant.label.equals("Auto", ignoreCase = true),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("player_quality_button")
                        )

                        // 3. Download Pill Button
                        BottomSettingPillButton(
                            icon = Icons.Default.FileDownload,
                            label = "Download",
                            onClick = {
                                onDownloadClick()
                                Toast.makeText(
                                    context,
                                    "Added \"${episode?.title ?: mediaItem.name}\" to Downloads",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("player_download_button")
                        )

                        // 4. Playlist / Episodes Pill Button
                        BottomSettingPillButton(
                            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                            label = playlistPillLabel,
                            onClick = { showPlaylistDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("player_playlist_button")
                        )
                    }
                }
            }
        }
    }

    // =========================================================================
    // DIALOGS & SHEETS (Speed, Quality, Audio & Subtitles, Playlist/Episodes, Cast)
    // =========================================================================

    // 1. Playback Speed Dialog
    if (showSpeedDialog) {
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Speed, contentDescription = null, tint = KrasRedBright)
                    Text(
                        text = "Playback Speed",
                        color = Color.White,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                        val isSelected = (playbackSpeed == speed)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) KrasRed.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f))
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) KrasRedBright else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    playbackSpeed = speed
                                    playerWrapper.setPlaybackSpeed(speed)
                                    showSpeedDialog = false
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (speed == 1.0f) "1.0x (Normal)" else "${speed}x",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    playbackSpeed = speed
                                    playerWrapper.setPlaybackSpeed(speed)
                                    showSpeedDialog = false
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = KrasRedBright,
                                    unselectedColor = TextSecondary
                                )
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedDialog = false }) {
                    Text("Close", color = TextSecondary)
                }
            },
            containerColor = CinemaDark,
            shape = RoundedCornerShape(22.dp)
        )
    }

    // 2. Video Quality Selector Dialog (Auto + real detected stream variants)
    if (showQualityDialog) {
        val variantsToShow = remember(availableVariants) {
            if (availableVariants.size > 1) {
                availableVariants
            } else {
                listOf(
                    VideoQualityVariant("Auto", 0),
                    VideoQualityVariant("1080p", 1080),
                    VideoQualityVariant("720p", 720),
                    VideoQualityVariant("480p", 480)
                )
            }
        }
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.HighQuality, contentDescription = null, tint = KrasRedBright)
                    Text(
                        text = "Select Video Quality",
                        color = Color.White,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (realResolutionOverlay.isNotBlank()) {
                        Text(
                            text = "Active stream output: $realResolutionOverlay",
                            color = KrasRedBright,
                            fontSize = 12.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    variantsToShow.forEach { variant ->
                        val isSelected = (selectedVariant.label == variant.label)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) KrasRed.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f))
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) KrasRedBright else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    playerWrapper.selectVideoQuality(variant)
                                    showQualityDialog = false
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (variant.height == 0 && realResolutionOverlay.isNotBlank()) {
                                    "Auto ($realResolutionOverlay)"
                                } else {
                                    variant.label
                                },
                                color = Color.White,
                                fontSize = 14.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    playerWrapper.selectVideoQuality(variant)
                                    showQualityDialog = false
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = KrasRedBright,
                                    unselectedColor = TextSecondary
                                )
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQualityDialog = false }) {
                    Text("Close", color = TextSecondary)
                }
            },
            containerColor = CinemaDark,
            shape = RoundedCornerShape(22.dp)
        )
    }

    // 3. Audio & Subtitles Track Selector Dialog (Connected to Media3 TrackSelector)
    if (showAudioSubtitleDialog) {
        AlertDialog(
            onDismissRequest = { showAudioSubtitleDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Subtitles, contentDescription = null, tint = KrasRedBright)
                    Text(
                        text = "Audio & Subtitles",
                        color = Color.White,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "AUDIO TRACKS",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                    availableAudioTracks.forEach { track ->
                        val isSel = (selectedAudioTrack.id == track.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSel) KrasRed.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f))
                                .clickable { playerWrapper.selectAudioTrack(track) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = track.label,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            RadioButton(
                                selected = isSel,
                                onClick = { playerWrapper.selectAudioTrack(track) },
                                colors = RadioButtonDefaults.colors(selectedColor = KrasRedBright)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "SUBTITLES / CC",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                    availableSubtitleTracks.forEach { sub ->
                        val isSel = (selectedSubtitleTrack.id == sub.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSel) KrasRed.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f))
                                .clickable { playerWrapper.selectSubtitleTrack(sub) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = sub.label,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            RadioButton(
                                selected = isSel,
                                onClick = { playerWrapper.selectSubtitleTrack(sub) },
                                colors = RadioButtonDefaults.colors(selectedColor = KrasRedBright)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showAudioSubtitleDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = KrasRed),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Done", color = Color.White, fontFamily = OutfitFontFamily, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = CinemaDark,
            shape = RoundedCornerShape(22.dp)
        )
    }

    // 4. Playlist / Episodes / Live Channels Drawer Modal
    if (showPlaylistDialog) {
        val allSeriesEpisodes = remember(seriesDetails) {
            seriesDetails?.seasons?.flatMap { it.episodes }.orEmpty()
        }

        AlertDialog(
            onDismissRequest = { showPlaylistDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                        contentDescription = null,
                        tint = KrasRedBright
                    )
                    Text(
                        text = when (mediaItem.type) {
                            MediaType.SERIES -> "Episodes • ${mediaItem.name}"
                            MediaType.LIVE -> "Live TV Channels (${liveChannels.size})"
                            MediaType.MOVIE -> "Up Next in Cinema"
                        },
                        color = Color.White,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            text = {
                Box(modifier = Modifier.heightIn(max = 340.dp)) {
                    when {
                        mediaItem.type == MediaType.SERIES && allSeriesEpisodes.isNotEmpty() -> {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(allSeriesEpisodes, key = { it.id }) { ep ->
                                    val isCurrent = (episode?.id == ep.id)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (isCurrent) KrasRed.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.06f))
                                            .border(
                                                1.dp,
                                                if (isCurrent) KrasRedBright else GlassBorder,
                                                RoundedCornerShape(12.dp)
                                            )
                                            .clickable {
                                                showPlaylistDialog = false
                                                onPlayEpisode?.invoke(ep)
                                            }
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayCircleFilled,
                                            contentDescription = null,
                                            tint = if (isCurrent) KrasRedBright else Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "S${ep.seasonNumber}:E${ep.episodeNum} • ${ep.title}",
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontFamily = OutfitFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (ep.plot.isNotBlank()) {
                                                Text(
                                                    text = ep.plot,
                                                    color = TextSecondary,
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        mediaItem.type == MediaType.LIVE && liveChannels.isNotEmpty() -> {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(liveChannels.take(60), key = { it.id }) { ch ->
                                    val isCurrent = (ch.id == mediaItem.id)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (isCurrent) KrasRed.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.06f))
                                            .border(
                                                1.dp,
                                                if (isCurrent) KrasRedBright else GlassBorder,
                                                RoundedCornerShape(12.dp)
                                            )
                                            .clickable {
                                                showPlaylistDialog = false
                                                onSwitchChannel(ch)
                                            }
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        val iconUrl = ch.streamIcon.ifBlank { ch.backdrop }.takeIf { it.isNotBlank() }
                                        if (iconUrl != null) {
                                            AsyncImage(
                                                model = iconUrl,
                                                contentDescription = ch.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(CinemaBlack)
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = ch.name,
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontFamily = OutfitFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = ch.categoryName,
                                                color = TextSecondary,
                                                fontSize = 11.sp,
                                                maxLines = 1
                                            )
                                        }
                                        ResolutionBadge(
                                            resolution = ch.quality.ifBlank { ch.resolution },
                                            isLive = true
                                        )
                                    }
                                }
                            }
                        }

                        else -> {
                            val listItems = moviesPlaylist.ifEmpty { liveChannels }.take(30)
                            if (listItems.isEmpty()) {
                                Text(
                                    text = "Now playing: ${episode?.title ?: mediaItem.name}",
                                    color = TextSecondary,
                                    fontSize = 13.sp
                                )
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(listItems, key = { it.id }) { item ->
                                        val isCurrent = (item.id == mediaItem.id)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(if (isCurrent) KrasRed.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.06f))
                                                .border(
                                                    1.dp,
                                                    if (isCurrent) KrasRedBright else GlassBorder,
                                                    RoundedCornerShape(12.dp)
                                                )
                                                .clickable {
                                                    showPlaylistDialog = false
                                                    onSwitchChannel(item)
                                                }
                                                .padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Movie,
                                                contentDescription = null,
                                                tint = if (isCurrent) KrasRedBright else Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Text(
                                                text = item.name,
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontFamily = OutfitFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            ResolutionBadge(
                                                resolution = item.quality.ifBlank { item.resolution },
                                                isLive = false
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlaylistDialog = false }) {
                    Text("Close", color = TextSecondary)
                }
            },
            containerColor = CinemaDark,
            shape = RoundedCornerShape(22.dp)
        )
    }

    // 5. Cast / Wireless Display Dialog
    if (showCastDialog) {
        AlertDialog(
            onDismissRequest = { showCastDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Cast, contentDescription = null, tint = KrasRedBright)
                    Text(
                        text = "Cast to Smart TV",
                        color = Color.White,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Stream \"${episode?.title ?: mediaItem.name}\" to a Smart TV, Chromecast, or Wireless Display on the same Wi-Fi network.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                            .clickable {
                                showCastDialog = false
                                try {
                                    val castIntent = Intent(Settings.ACTION_CAST_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(castIntent)
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Wireless Display settings unavailable on this device", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.ConnectedTv, contentDescription = null, tint = KrasRedBright)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Open Wireless Cast Display",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Connect to Chromecast / Miracast TV",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }

                    val activeStreamUrl = episode?.streamUrl?.ifBlank { mediaItem.streamUrl } ?: mediaItem.streamUrl
                    if (activeStreamUrl.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("KRAS TV Stream URL", activeStreamUrl))
                                    Toast.makeText(context, "Stream URL copied for external DLNA/Cast player", Toast.LENGTH_SHORT).show()
                                    showCastDialog = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color.White)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Copy Direct Stream URL",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Use with external DLNA or network receivers",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCastDialog = false }) {
                    Text("Close", color = TextSecondary)
                }
            },
            containerColor = CinemaDark,
            shape = RoundedCornerShape(22.dp)
        )
    }
}

/**
 * Circular translucent glassmorphic top-bar icon button with subtle 3D specular border.
 */
@Composable
private fun PlayerGlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    isHighlighted: Boolean = false
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .drawWithCache {
                val r = this.size.minDimension / 2f
                val fillBrush = Brush.verticalGradient(
                    colors = if (isHighlighted) {
                        listOf(KrasRed.copy(alpha = 0.45f), Color(0xCC12141E))
                    } else {
                        listOf(Color(0xB31E2230), Color(0xCC0D0F17))
                    }
                )
                val rimBrush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.45f),
                        Color.White.copy(alpha = 0.12f),
                        if (isHighlighted) KrasRedBright.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.22f)
                    ),
                    start = Offset.Zero,
                    end = Offset(this.size.width, this.size.height)
                )
                onDrawBehind {
                    drawCircle(brush = fillBrush, radius = r)
                    drawCircle(
                        brush = rimBrush,
                        radius = r - 0.5.dp.toPx(),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }
            .liquidPressClickable(pressedScale = 0.90f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * Center 10-second rewind / forward button with dark translucent glass & 3D rim.
 */
@Composable
private fun CenterSeekButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "seek_btn_scale"
    )

    Box(
        modifier = modifier
            .size(58.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .drawWithCache {
                val r = size.minDimension / 2f
                val bgBrush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xBF1A1E2B),
                        Color(0xD9090B10)
                    )
                )
                val borderBrush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.45f),
                        Color.White.copy(alpha = 0.10f),
                        KrasRed.copy(alpha = 0.35f)
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
                onDrawBehind {
                    drawCircle(brush = bgBrush, radius = r)
                    drawCircle(
                        brush = borderBrush,
                        radius = r - 0.6.dp.toPx(),
                        style = Stroke(width = 1.2.dp.toPx())
                    )
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(28.dp)
        )
    }
}

/**
 * Large circular center Play/Pause control with dark translucent background,
 * subtle red glowing border, and 3D inner specular reflection.
 */
@Composable
private fun CenterPlayPauseButton(
    isPlaying: Boolean,
    glowPulse: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isEnded: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.91f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "play_pause_scale"
    )

    Box(
        modifier = modifier
            .size(84.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawWithCache {
                val r = size.minDimension / 2f
                val innerRadius = r * 0.84f
                val outerGlowBrush = Brush.radialGradient(
                    colors = listOf(
                        KrasRed.copy(alpha = 0.42f * glowPulse),
                        KrasRed.copy(alpha = 0.14f * glowPulse),
                        Color.Transparent
                    ),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = r
                )
                val darkGlassFill = Brush.radialGradient(
                    colors = listOf(
                        Color(0xD9220A0E),
                        Color(0xEC0B0D14)
                    ),
                    center = Offset(size.width * 0.35f, size.height * 0.3f),
                    radius = innerRadius * 1.3f
                )
                val redGlowingBorder = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.72f),
                        KrasRedBright,
                        KrasRed,
                        KrasRedBright.copy(alpha = 0.85f)
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
                onDrawBehind {
                    // Subtle outer red glow halo
                    drawCircle(brush = outerGlowBrush, radius = r)
                    // Dark translucent glass core
                    drawCircle(brush = darkGlassFill, radius = innerRadius)
                    // Glowing red + specular rim border
                    drawCircle(
                        brush = redGlowingBorder,
                        radius = innerRadius - 1.dp.toPx(),
                        style = Stroke(width = 1.8.dp.toPx())
                    )
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = when {
                isEnded -> Icons.Default.Replay
                isPlaying -> Icons.Default.Pause
                else -> Icons.Default.PlayArrow
            },
            contentDescription = when {
                isEnded -> "Replay"
                isPlaying -> "Pause"
                else -> "Play"
            },
            tint = Color.White,
            modifier = Modifier.size(38.dp)
        )
    }
}

/**
 * Equal-sized rounded pill button for the Bottom Settings Bar
 * (Playback Speed, Video Quality, Download, Playlist/Episodes).
 */
@Composable
private fun BottomSettingPillButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false
) {
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(50))
            .drawWithCache {
                val corner = CornerRadius(size.height / 2f, size.height / 2f)
                val fillBrush = Brush.verticalGradient(
                    colors = if (isActive) {
                        listOf(
                            KrasRed.copy(alpha = 0.32f),
                            Color(0xD910131C)
                        )
                    } else {
                        listOf(
                            Color(0xBF1B1F2E),
                            Color(0xD90C0E16)
                        )
                    }
                )
                val borderBrush = Brush.linearGradient(
                    colors = if (isActive) {
                        listOf(
                            Color.White.copy(alpha = 0.55f),
                            KrasRedBright.copy(alpha = 0.7f)
                        )
                    } else {
                        listOf(
                            Color.White.copy(alpha = 0.35f),
                            Color.White.copy(alpha = 0.10f),
                            Color.White.copy(alpha = 0.22f)
                        )
                    },
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
                onDrawBehind {
                    drawRoundRect(brush = fillBrush, cornerRadius = corner)
                    drawRoundRect(
                        brush = borderBrush,
                        cornerRadius = corner,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }
            .liquidPressClickable(pressedScale = 0.95f, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isActive) KrasRedBright else Color.White.copy(alpha = 0.92f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatTime(millis: Long): String {
    val totalSec = (millis / 1000).coerceAtLeast(0L)
    val sec = totalSec % 60
    val min = (totalSec / 60) % 60
    val hrs = totalSec / 3600
    return if (hrs > 0) {
        String.format("%d:%02d:%02d", hrs, min, sec)
    } else {
        String.format("%02d:%02d", min, sec)
    }
}
