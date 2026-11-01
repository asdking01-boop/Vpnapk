package com.example.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.data.model.*
import com.example.player.ExoPlayerWrapper
import com.example.ui.components.ResolutionBadge
import com.example.ui.theme.*
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    mediaItem: MediaItem,
    episode: Episode?,
    playerWrapper: ExoPlayerWrapper,
    onClose: () -> Unit,
    liveChannels: List<MediaItem>,
    onSwitchChannel: (MediaItem) -> Unit,
    onDownloadClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // Screen Wake Lock while playing
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val isPlaying by playerWrapper.isPlaying.collectAsState()
    val isBuffering by playerWrapper.isBuffering.collectAsState()
    val currentPos by playerWrapper.currentPosition.collectAsState()
    val duration by playerWrapper.duration.collectAsState()
    val errorMessage by playerWrapper.errorMessage.collectAsState()

    var controlsVisible by remember { mutableStateOf(true) }
    var currentAspectRatio by remember { mutableStateOf(AspectRatioMode.FIT) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var isLandscapeLocked by remember { mutableStateOf(false) }

    // Dialog sheets
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var selectedQuality by remember { mutableStateOf("Auto (1080p)") }
    var selectedAudioTrack by remember { mutableStateOf("Stereo 2.0 (Default)") }

    // Auto-hide controls after 4 seconds
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(4000)
            controlsVisible = false
        }
    }

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
        // Video Surface (ExoPlayer PlayerView)
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = playerWrapper.getPlayer()
                    useController = false
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

        // Buffering Spinner / Loading
        if (isBuffering) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = KrasRed,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Smart Buffering...",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Error Banner
        if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 70.dp, start = 20.dp, end = 20.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(KrasRedDark.copy(alpha = 0.9f))
                    .padding(12.dp)
            ) {
                Text(
                    text = errorMessage!!,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Animated Controls Overlay
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Black.copy(alpha = 0.75f),
                            0.3f to Color.Transparent,
                            0.7f to Color.Transparent,
                            1.0f to Color.Black.copy(alpha = 0.85f)
                        )
                    )
            ) {
                // Top Bar: Back, Title, Resolution, Aspect Ratio, Rotation, Settings
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.testTag("player_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Close Player",
                                tint = Color.White
                            )
                        }

                        Column {
                            Text(
                                text = episode?.title ?: mediaItem.name,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            if (episode != null) {
                                Text(
                                    text = "${mediaItem.name} • S${episode.seasonNumber}:E${episode.episodeNum}",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    // Top Action Badges & Aspect Ratio
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ResolutionBadge(
                            resolution = mediaItem.resolution,
                            isLive = mediaItem.type == MediaType.LIVE
                        )

                        // Aspect Ratio Cycle Button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.15f))
                                .clickable {
                                    currentAspectRatio = when (currentAspectRatio) {
                                        AspectRatioMode.FIT -> AspectRatioMode.FILL
                                        AspectRatioMode.FILL -> AspectRatioMode.STRETCH
                                        AspectRatioMode.STRETCH -> AspectRatioMode.FIT
                                        else -> AspectRatioMode.FIT
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("player_aspect_ratio_button")
                        ) {
                            Text(
                                text = currentAspectRatio.name,
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Orientation toggle
                        IconButton(onClick = {
                            isLandscapeLocked = !isLandscapeLocked
                            activity?.requestedOrientation = if (isLandscapeLocked) {
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR
                            }
                        }) {
                            Icon(
                                imageVector = if (isLandscapeLocked) Icons.Default.ScreenRotation else Icons.Default.ScreenLockRotation,
                                contentDescription = "Toggle Rotation",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Audio/Subtitle Track selector
                        IconButton(onClick = { showAudioDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Subtitles,
                                contentDescription = "Audio & Subtitles",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Center Controls: Rewind 10s, Play/Pause, Forward 10s
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (mediaItem.type != MediaType.LIVE) {
                        IconButton(
                            onClick = { playerWrapper.seekBackward(10_000) },
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .testTag("player_rewind_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Replay10,
                                contentDescription = "Rewind 10 seconds",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }

                    // Main Play/Pause Button
                    IconButton(
                        onClick = { playerWrapper.togglePlayPause() },
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(KrasRed)
                            .testTag("player_play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(42.dp)
                        )
                    }

                    if (mediaItem.type != MediaType.LIVE) {
                        IconButton(
                            onClick = { playerWrapper.seekForward(10_000) },
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .testTag("player_forward_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Forward10,
                                contentDescription = "Forward 10 seconds",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                }

                // Bottom Panel: Live Channels Drawer (if Live TV) OR Seekbar & Speed (if VOD)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    if (mediaItem.type == MediaType.LIVE) {
                        // Quick Channel Switching Bar for Live TV
                        Text(
                            text = "Channel Switcher",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(liveChannels, key = { it.id }) { ch ->
                                val isSelected = ch.id == mediaItem.id
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) KrasRed else Color.White.copy(alpha = 0.15f))
                                        .clickable { onSwitchChannel(ch) }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = ch.name,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    } else {
                        // VOD / Series Seekbar
                        val dur = duration.coerceAtLeast(1L)
                        val pos = currentPos.coerceIn(0L, dur)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatTime(pos),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            Slider(
                                value = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f),
                                onValueChange = { frac ->
                                    val target = (frac * dur).toLong()
                                    playerWrapper.seekTo(target)
                                },
                                colors = SliderDefaults.colors(
                                    thumbColor = KrasRed,
                                    activeTrackColor = KrasRed,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                                    .testTag("player_seek_bar")
                            )

                            Text(
                                text = formatTime(dur),
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }

                        // Bottom Actions: Speed, Quality, Offline Download
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Playback speed button
                            TextButton(onClick = { showSpeedDialog = true }) {
                                Text(
                                    text = "Speed: ${playbackSpeed}x",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Quality Selector
                            TextButton(onClick = { showQualityDialog = true }) {
                                Text(
                                    text = selectedQuality,
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                            }

                            // Download for offline playback
                            IconButton(onClick = onDownloadClick) {
                                Icon(
                                    imageVector = Icons.Default.FileDownload,
                                    contentDescription = "Download Offline",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Playback Speed Dialog
    if (showSpeedDialog) {
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text("Playback Speed", color = Color.White) },
            text = {
                Column {
                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    playbackSpeed = speed
                                    playerWrapper.setPlaybackSpeed(speed)
                                    showSpeedDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (playbackSpeed == speed),
                                onClick = {
                                    playbackSpeed = speed
                                    playerWrapper.setPlaybackSpeed(speed)
                                    showSpeedDialog = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = KrasRed)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${speed}x ${if (speed == 1.0f) "(Normal)" else ""}",
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            containerColor = CinemaDark
        )
    }

    // Quality Selector Dialog
    if (showQualityDialog) {
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = { Text("Select Stream Quality", color = Color.White) },
            text = {
                Column {
                    listOf("Auto (Adaptive)", "4K Ultra HD (2160p)", "Full HD (1080p)", "HD (720p)", "Data Saver (480p)").forEach { q ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedQuality = q
                                    showQualityDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (selectedQuality == q),
                                onClick = {
                                    selectedQuality = q
                                    showQualityDialog = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = KrasRed)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = q, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            containerColor = CinemaDark
        )
    }

    // Audio & Subtitle Dialog
    if (showAudioDialog) {
        AlertDialog(
            onDismissRequest = { showAudioDialog = false },
            title = { Text("Audio & Subtitle Tracks", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Audio Track:", color = TextSecondary, fontSize = 12.sp)
                    listOf("Original English (5.1 Dolby)", "Arabic (Dubbed)", "Stereo 2.0 (Default)").forEach { track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedAudioTrack = track }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (selectedAudioTrack == track),
                                onClick = { selectedAudioTrack = track },
                                colors = RadioButtonDefaults.colors(selectedColor = KrasRed)
                            )
                            Text(track, color = Color.White, fontSize = 13.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Subtitles:", color = TextSecondary, fontSize = 12.sp)
                    listOf("Off", "English [CC]", "Arabic").forEach { sub ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = sub == "Off",
                                onClick = {},
                                colors = RadioButtonDefaults.colors(selectedColor = KrasRed)
                            )
                            Text(sub, color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showAudioDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = KrasRed)
                ) {
                    Text("Done", color = Color.White)
                }
            },
            containerColor = CinemaDark
        )
    }
}

private fun formatTime(millis: Long): String {
    val totalSec = millis / 1000
    val sec = totalSec % 60
    val min = (totalSec / 60) % 60
    val hrs = totalSec / 3600
    return if (hrs > 0) {
        String.format("%d:%02d:%02d", hrs, min, sec)
    } else {
        String.format("%02d:%02d", min, sec)
    }
}
