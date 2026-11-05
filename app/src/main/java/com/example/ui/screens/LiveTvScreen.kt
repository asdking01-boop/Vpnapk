package com.example.ui.screens

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.data.local.ChannelNameNormalizer
import com.example.data.local.QualityDetector
import com.example.data.model.AspectRatioMode
import com.example.data.model.Category
import com.example.data.model.LiveChannelOrder
import com.example.data.model.MediaItem
import com.example.player.ExoPlayerWrapper
import com.example.player.PlayerImmersiveHelper
import com.example.player.PlayerTrackOption
import com.example.player.PlayerUiState
import com.example.player.VideoQualityVariant
import com.example.ui.components.DynamicAmbientPosterBackdrop
import com.example.ui.components.LiquidGlassChip
import com.example.ui.components.ResolutionBadge
import com.example.ui.components.liquidGlassPanel
import com.example.ui.components.liquidPressClickable
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Computes EPG program metadata (current program title, time slot window, and progress fraction)
 * from channel metadata (`plot`, `categoryName`) or current broadcast clock window.
 */
internal data class LiveEpgProgramInfo(
    val currentTitle: String,
    val nextTitle: String,
    val timeWindowLabel: String,
    val progressFraction: Float
)

internal fun resolveLiveEpgInfo(channel: MediaItem?): LiveEpgProgramInfo {
    if (channel == null) {
        return LiveEpgProgramInfo(
            currentTitle = "Live Broadcast",
            nextTitle = "Up Next • Live Coverage",
            timeWindowLabel = "LIVE",
            progressFraction = 0.45f
        )
    }
    val calendar = Calendar.getInstance()
    val minute = calendar.get(Calendar.MINUTE)
    val second = calendar.get(Calendar.SECOND)
    val startHour = calendar.get(Calendar.HOUR_OF_DAY)
    val isFirstHalfHour = minute < 30
    val slotStartMin = if (isFirstHalfHour) 0 else 30
    val elapsedSecondsInSlot = ((minute - slotStartMin).coerceAtLeast(0) * 60) + second
    val progress = (elapsedSecondsInSlot / 1800f).coerceIn(0.08f, 0.96f)

    val startCal = (calendar.clone() as Calendar).apply {
        set(Calendar.MINUTE, slotStartMin)
        set(Calendar.SECOND, 0)
    }
    val endCal = (startCal.clone() as Calendar).apply {
        add(Calendar.MINUTE, 30)
    }
    val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
    val windowStr = "${timeFormat.format(startCal.time)} – ${timeFormat.format(endCal.time)}"

    val rawPlot = channel.plot.trim()
    val currentProgram = when {
        rawPlot.isNotBlank() -> rawPlot
        channel.categoryName.isNotBlank() -> "${channel.categoryName} • Live Broadcast"
        else -> "${channel.name} • Live Studio Feed"
    }
    val nextProgram = if (channel.categoryName.isNotBlank()) {
        "Next (${timeFormat.format(endCal.time)}): ${channel.categoryName} Spotlight"
    } else {
        "Next (${timeFormat.format(endCal.time)}): Continuous Live Coverage"
    }
    return LiveEpgProgramInfo(
        currentTitle = currentProgram,
        nextTitle = nextProgram,
        timeWindowLabel = windowStr,
        progressFraction = progress
    )
}

@Composable
fun LiveTvScreen(
    channels: List<MediaItem>,
    categories: List<Category>,
    isFavorite: (String) -> Boolean,
    onToggleFavorite: (MediaItem) -> Unit,
    onChannelClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    totalCount: Int = channels.size,
    liveChannelOrder: LiveChannelOrder = LiveChannelOrder.SMART,
    activeChannel: MediaItem? = null,
    playerWrapper: ExoPlayerWrapper? = null,
    isFullscreen: Boolean = false,
    onToggleFullscreen: (Boolean) -> Unit = {},
    onRetryPlayback: () -> Unit = {},
    onStopPlayback: () -> Unit = {},
    defaultAspectRatio: AspectRatioMode = AspectRatioMode.FIT,
    recentChannels: List<MediaItem> = emptyList(),
    lastWatchedChannel: MediaItem? = null,
    swipeGesturesEnabled: Boolean = true,
    onToggleSwipeGestures: (Boolean) -> Unit = {},
    backgroundPlaybackEnabled: Boolean = true,
    onToggleBackgroundPlayback: (Boolean) -> Unit = {},
    onMinimizeToMiniPlayer: (() -> Unit)? = null,
    onEnterPip: (() -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    onFilterChanged: ((categoryId: String, query: String, qualityFilter: String, sortByQuality: Boolean) -> Unit)? = null
) {
    var selectedCategoryId by rememberSaveable { mutableStateOf("all") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedQualityFilter by rememberSaveable { mutableStateOf("All") }
    var sortByQuality by rememberSaveable { mutableStateOf(false) }
    var localSelectedChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    var isFullscreenDrawerOpen by rememberSaveable { mutableStateOf(false) }
    var drawerSearchQuery by rememberSaveable { mutableStateOf("") }
    var localSwipeGestures by rememberSaveable(swipeGesturesEnabled) { mutableStateOf(swipeGesturesEnabled) }

    // Persistent LazyListState preserved across channel switches and fullscreen transitions
    val listState = rememberLazyListState()
    val fullscreenDrawerListState = rememberLazyListState()

    val context = LocalContext.current
    val activity = context as? Activity
    val haptic = LocalHapticFeedback.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isInPipMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        activity?.isInPictureInPictureMode == true
    } else {
        false
    }

    // Landscape or explicit Fullscreen triggers edge-to-edge immersive mode
    val useImmersiveFullscreenLayout = isFullscreen || isLandscape

    // Manage immersive system bars when Live TV player is in fullscreen or landscape
    DisposableEffect(useImmersiveFullscreenLayout) {
        if (activity != null) {
            if (useImmersiveFullscreenLayout) {
                PlayerImmersiveHelper.enterImmersiveMode(activity)
            } else {
                PlayerImmersiveHelper.exitImmersiveMode(activity)
            }
        }
        onDispose {
            if (useImmersiveFullscreenLayout && activity != null) {
                PlayerImmersiveHelper.exitImmersiveMode(activity)
            }
        }
    }

    // BackHandler in fullscreen or drawer mode
    BackHandler(enabled = isFullscreen || isFullscreenDrawerOpen) {
        when {
            isFullscreenDrawerOpen -> isFullscreenDrawerOpen = false
            isFullscreen -> onToggleFullscreen(false)
        }
    }

    val qualityFilterOptions = remember { listOf("All", "4K", "FHD", "HD", "SD") }

    LaunchedEffect(selectedCategoryId, searchQuery, selectedQualityFilter, sortByQuality) {
        onFilterChanged?.invoke(selectedCategoryId, searchQuery, selectedQualityFilter, sortByQuality)
    }

    val filteredChannels = remember(
        channels,
        recentChannels,
        selectedCategoryId,
        searchQuery,
        selectedQualityFilter,
        sortByQuality,
        onFilterChanged,
        liveChannelOrder
    ) {
        if (onFilterChanged != null && selectedCategoryId != "recent") {
            channels
        } else {
            val baseSource = if (selectedCategoryId == "recent" && recentChannels.isNotEmpty()) {
                recentChannels
            } else {
                channels
            }
            val filtered = baseSource.filter { ch ->
                val matchCat = when (selectedCategoryId) {
                    "all", "live_all", "recent" -> true
                    "favorites" -> isFavorite(ch.id)
                    else -> ch.categoryId == selectedCategoryId ||
                            ch.categoryName.contains(selectedCategoryId, ignoreCase = true)
                }
                val matchSearch = searchQuery.isEmpty() ||
                        ch.name.contains(searchQuery, ignoreCase = true)
                val matchQuality = QualityDetector.matchesQualityFilter(
                    ch.quality.ifBlank { ch.resolution },
                    selectedQualityFilter
                )
                matchCat && matchSearch && matchQuality
            }
            val ordered = if (selectedCategoryId == "recent") {
                filtered
            } else {
                ChannelNameNormalizer.sortMediaItems(filtered, liveChannelOrder)
            }
            if (sortByQuality) {
                QualityDetector.sortMediaByQualityDescending(ordered)
            } else {
                ordered
            }
        }
    }

    val drawerFilteredChannels = remember(filteredChannels, drawerSearchQuery) {
        if (drawerSearchQuery.isBlank()) {
            filteredChannels
        } else {
            filteredChannels.filter {
                it.name.contains(drawerSearchQuery, ignoreCase = true) ||
                        it.categoryName.contains(drawerSearchQuery, ignoreCase = true)
            }
        }
    }

    // Resolve currently active/selected channel (supports both ViewModel state and standalone composable usage)
    val currentPlayingChannel = remember(activeChannel, localSelectedChannelId, filteredChannels, channels) {
        activeChannel
            ?: localSelectedChannelId?.let { id ->
                filteredChannels.find { it.id == id } ?: channels.find { it.id == id }
            }
    }

    val handleChannelSelect: (MediaItem) -> Unit = { ch ->
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        localSelectedChannelId = ch.id
        onChannelClick(ch)
    }

    val handlePrevNextChannel: (Int) -> Unit = { delta ->
        val navPool = filteredChannels.ifEmpty { channels }
        if (navPool.isNotEmpty()) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            val curIdx = navPool.indexOfFirst { it.id == currentPlayingChannel?.id }
            val targetIdx = if (curIdx < 0) 0 else (curIdx + delta + navPool.size) % navPool.size
            handleChannelSelect(navPool[targetIdx])
        }
    }

    LaunchedEffect(listState.canScrollForward, filteredChannels.size) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisible >= filteredChannels.size - 6 && filteredChannels.isNotEmpty()) {
            onLoadMore?.invoke()
        }
    }

    // Pulsing live indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val distinctCategories = remember(categories) {
        categories.filter {
            !it.categoryId.equals("all", ignoreCase = true) &&
                    !it.categoryId.equals("live_all", ignoreCase = true) &&
                    !it.categoryName.equals("All Channels", ignoreCase = true)
        }
    }

    val ambientChannelArt = remember(currentPlayingChannel, filteredChannels) {
        (currentPlayingChannel ?: filteredChannels.firstOrNull())?.let {
            it.streamIcon.ifBlank { it.backdrop }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("live_tv_screen")
    ) {
        // Subtle AMOLED ambient radial backdrop
        DynamicAmbientPosterBackdrop(
            imageUrl = ambientChannelArt,
            ambientAlpha = if (useImmersiveFullscreenLayout) 0.08f else 0.16f
        )

        if (useImmersiveFullscreenLayout || isInPipMode) {
            // IMMERSIVE EDGE-TO-EDGE FULLSCREEN / LANDSCAPE LIVE TV PLAYER
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .testTag("live_tv_fullscreen_container")
            ) {
                EmbeddedLiveVideoPlayer(
                    activeChannel = currentPlayingChannel,
                    playerWrapper = playerWrapper,
                    isFullscreen = true,
                    isInPipMode = isInPipMode,
                    isFavorite = currentPlayingChannel?.let { isFavorite(it.id) } ?: false,
                    onToggleFavorite = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentPlayingChannel?.let(onToggleFavorite)
                    },
                    onToggleFullscreen = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        isFullscreenDrawerOpen = false
                        onToggleFullscreen(!isFullscreen)
                    },
                    onOpenChannelDrawer = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        isFullscreenDrawerOpen = !isFullscreenDrawerOpen
                    },
                    onPrevChannel = { handlePrevNextChannel(-1) },
                    onNextChannel = { handlePrevNextChannel(1) },
                    onRetryPlayback = onRetryPlayback,
                    onStopPlayback = {
                        localSelectedChannelId = null
                        onStopPlayback()
                    },
                    onStartFirstChannel = {
                        (lastWatchedChannel ?: filteredChannels.firstOrNull())?.let(handleChannelSelect)
                    },
                    defaultAspectRatio = defaultAspectRatio,
                    swipeGesturesEnabled = localSwipeGestures,
                    onToggleSwipeGestures = { enabled ->
                        localSwipeGestures = enabled
                        onToggleSwipeGestures(enabled)
                    },
                    backgroundPlaybackEnabled = backgroundPlaybackEnabled,
                    onToggleBackgroundPlayback = onToggleBackgroundPlayback,
                    onMinimizeToMiniPlayer = onMinimizeToMiniPlayer,
                    onEnterPip = {
                        if (onEnterPip != null) {
                            onEnterPip()
                        } else {
                            PlayerImmersiveHelper.enterPictureInPicture(activity)
                        }
                    },
                    pulseAlpha = pulseAlpha,
                    modifier = Modifier.fillMaxSize()
                )

                // Translucent Collapsible Floating Channel Drawer (Landscape & Fullscreen)
                if (!isInPipMode) {
                    FloatingLiveChannelDrawer(
                        visible = isFullscreenDrawerOpen,
                        onDismiss = { isFullscreenDrawerOpen = false },
                        channels = drawerFilteredChannels,
                        activeChannelId = currentPlayingChannel?.id,
                        drawerListState = fullscreenDrawerListState,
                        selectedCategoryId = selectedCategoryId,
                        onSelectCategory = { selectedCategoryId = it },
                        distinctCategories = distinctCategories,
                        hasRecentChannels = recentChannels.isNotEmpty(),
                        drawerSearchQuery = drawerSearchQuery,
                        onDrawerSearchQueryChange = { drawerSearchQuery = it },
                        isFavorite = isFavorite,
                        onToggleFavorite = onToggleFavorite,
                        onChannelClick = { ch ->
                            handleChannelSelect(ch)
                        },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }
        } else {
            // MODERN PORTRAIT LAYOUT ( optimized for Samsung Galaxy S26 Ultra one-handed ergonomics )
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                ) {
                    // 1. Top Cinema Video Player Surface with subtle red ambient glow
                    val hasActiveStream = currentPlayingChannel != null
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .shadow(
                                elevation = if (hasActiveStream) 20.dp else 8.dp,
                                shape = RoundedCornerShape(22.dp),
                                ambientColor = if (hasActiveStream) KrasRed else Color.Black,
                                spotColor = if (hasActiveStream) KrasRedBright else Color.Black
                            )
                    ) {
                        EmbeddedLiveVideoPlayer(
                            activeChannel = currentPlayingChannel,
                            playerWrapper = playerWrapper,
                            isFullscreen = false,
                            isInPipMode = false,
                            isFavorite = currentPlayingChannel?.let { isFavorite(it.id) } ?: false,
                            onToggleFavorite = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentPlayingChannel?.let(onToggleFavorite)
                            },
                            onToggleFullscreen = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onToggleFullscreen(true)
                            },
                            onOpenChannelDrawer = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                isFullscreenDrawerOpen = !isFullscreenDrawerOpen
                            },
                            onPrevChannel = { handlePrevNextChannel(-1) },
                            onNextChannel = { handlePrevNextChannel(1) },
                            onRetryPlayback = onRetryPlayback,
                            onStopPlayback = {
                                localSelectedChannelId = null
                                onStopPlayback()
                            },
                            onStartFirstChannel = {
                                (lastWatchedChannel ?: filteredChannels.firstOrNull())?.let(handleChannelSelect)
                            },
                            defaultAspectRatio = defaultAspectRatio,
                            swipeGesturesEnabled = localSwipeGestures,
                            onToggleSwipeGestures = { enabled ->
                                localSwipeGestures = enabled
                                onToggleSwipeGestures(enabled)
                            },
                            backgroundPlaybackEnabled = backgroundPlaybackEnabled,
                            onToggleBackgroundPlayback = onToggleBackgroundPlayback,
                            onMinimizeToMiniPlayer = onMinimizeToMiniPlayer,
                            onEnterPip = {
                                if (onEnterPip != null) {
                                    onEnterPip()
                                } else {
                                    PlayerImmersiveHelper.enterPictureInPicture(activity)
                                }
                            },
                            pulseAlpha = pulseAlpha,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .heightIn(min = 200.dp, max = 236.dp)
                                .clip(RoundedCornerShape(22.dp))
                        )
                    }

                    // 2. Active EPG Program Strip OR Last Watched Channel Quick-Resume Banner
                    if (currentPlayingChannel != null) {
                        PortraitActiveEpgBar(
                            activeChannel = currentPlayingChannel,
                            pulseAlpha = pulseAlpha,
                            onOpenDrawer = { isFullscreenDrawerOpen = true },
                            onExpandFullscreen = { onToggleFullscreen(true) }
                        )
                    } else if (lastWatchedChannel != null) {
                        LastWatchedChannelBanner(
                            channel = lastWatchedChannel,
                            onResumeClick = { handleChannelSelect(lastWatchedChannel) }
                        )
                    }

                    // 3. Floating Category Chips, Search & Stream Quality Filters
                    LiveTvFilterAndCategoryControls(
                        searchQuery = searchQuery,
                        onSearchQueryChange = { searchQuery = it },
                        isSearchExpanded = isSearchExpanded,
                        onToggleSearchExpanded = { isSearchExpanded = !isSearchExpanded },
                        selectedCategoryId = selectedCategoryId,
                        onSelectCategory = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedCategoryId = it
                        },
                        distinctCategories = distinctCategories,
                        hasRecentChannels = recentChannels.isNotEmpty(),
                        qualityFilterOptions = qualityFilterOptions,
                        selectedQualityFilter = selectedQualityFilter,
                        onSelectQualityFilter = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedQualityFilter = it
                        },
                        sortByQuality = sortByQuality,
                        onToggleSortByQuality = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            sortByQuality = !sortByQuality
                        },
                        totalCount = totalCount.coerceAtLeast(filteredChannels.size),
                        activeChannelName = currentPlayingChannel?.name,
                        pulseAlpha = pulseAlpha
                    )

                    // 4. Vertically Scrolling Rounded Channel Cards with Subtle Red Glow on Active Channel
                    PersistentLiveChannelList(
                        channels = filteredChannels,
                        activeChannelId = currentPlayingChannel?.id,
                        listState = listState,
                        isFavorite = isFavorite,
                        onToggleFavorite = { ch ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleFavorite(ch)
                        },
                        onChannelClick = handleChannelSelect,
                        bottomPadding = 104.dp,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Floating Channel Drawer also accessible in Portrait via edge-swipe or pill Channel List button
                FloatingLiveChannelDrawer(
                    visible = isFullscreenDrawerOpen,
                    onDismiss = { isFullscreenDrawerOpen = false },
                    channels = drawerFilteredChannels,
                    activeChannelId = currentPlayingChannel?.id,
                    drawerListState = fullscreenDrawerListState,
                    selectedCategoryId = selectedCategoryId,
                    onSelectCategory = { selectedCategoryId = it },
                    distinctCategories = distinctCategories,
                    hasRecentChannels = recentChannels.isNotEmpty(),
                    drawerSearchQuery = drawerSearchQuery,
                    onDrawerSearchQueryChange = { drawerSearchQuery = it },
                    isFavorite = isFavorite,
                    onToggleFavorite = onToggleFavorite,
                    onChannelClick = { ch ->
                        handleChannelSelect(ch)
                        isFullscreenDrawerOpen = false
                    },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
    }
}

@Composable
private fun PortraitActiveEpgBar(
    activeChannel: MediaItem,
    pulseAlpha: Float,
    onOpenDrawer: () -> Unit,
    onExpandFullscreen: () -> Unit
) {
    val epgInfo = remember(activeChannel.id, activeChannel.plot, activeChannel.categoryName) {
        resolveLiveEpgInfo(activeChannel)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xE6140A0E),
                        Color(0xD90D1018),
                        Color(0xE60B0D14)
                    )
                )
            )
            .border(1.dp, KrasRed.copy(alpha = 0.32f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .alpha(pulseAlpha)
                        .background(KrasRedBright)
                )
                Text(
                    text = epgInfo.currentTitle,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = epgInfo.timeWindowLabel,
                    color = KrasRedBright,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.07f))
                        .clickable(onClick = onOpenDrawer)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Guide",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(KrasRed.copy(alpha = 0.22f))
                        .border(0.8.dp, KrasRedBright.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .clickable(onClick = onExpandFullscreen)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Fullscreen",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Subtle EPG time-slot progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.5.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(epgInfo.progressFraction)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(KrasRed, KrasRedBright)
                        )
                    )
            )
        }
    }
}

@Composable
private fun LastWatchedChannelBanner(
    channel: MediaItem,
    onResumeClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        KrasRed.copy(alpha = 0.22f),
                        Color(0xE60E111A)
                    )
                )
            )
            .border(1.dp, KrasRedBright.copy(alpha = 0.42f), RoundedCornerShape(16.dp))
            .clickable(onClick = onResumeClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
            .testTag("live_resume_last_channel_banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = KrasRedBright,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Resume Last Watched Channel",
                    color = KrasRedBright,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = channel.name,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(KrasRed)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Watch",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun LiveTvFilterAndCategoryControls(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSearchExpanded: Boolean,
    onToggleSearchExpanded: () -> Unit,
    selectedCategoryId: String,
    onSelectCategory: (String) -> Unit,
    distinctCategories: List<Category>,
    hasRecentChannels: Boolean,
    qualityFilterOptions: List<String>,
    selectedQualityFilter: String,
    onSelectQualityFilter: (String) -> Unit,
    sortByQuality: Boolean,
    onToggleSortByQuality: () -> Unit,
    totalCount: Int,
    activeChannelName: String?,
    pulseAlpha: Float
) {
    // Sleek Rounded Glass Search Input
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = {
                Text(
                    text = "Search live channels, sports, news...",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = KrasRedBright,
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear Search",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = KrasRedBright.copy(alpha = 0.75f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                focusedContainerColor = Color(0xCC0D1018),
                unfocusedContainerColor = Color(0x990B0E15),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("live_search_input")
        )
    }

    // Floating Category Chips (All Channels, Favorites, Recent, and IPTV Categories)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "cat_all") {
            LiquidGlassChip(
                text = "All Channels",
                isSelected = selectedCategoryId == "all",
                onClick = { onSelectCategory("all") },
                activeColor = KrasRed
            )
        }

        item(key = "cat_favorites") {
            LiquidGlassChip(
                text = "Favorites",
                isSelected = selectedCategoryId == "favorites",
                onClick = { onSelectCategory("favorites") },
                activeColor = KrasRed,
                modifier = Modifier.testTag("live_category_chip_favorites")
            )
        }

        item(key = "cat_recent") {
            LiquidGlassChip(
                text = if (hasRecentChannels) "Recent" else "Recent",
                isSelected = selectedCategoryId == "recent",
                onClick = { onSelectCategory("recent") },
                activeColor = KrasRed,
                modifier = Modifier.testTag("live_category_chip_recent")
            )
        }

        items(distinctCategories, key = { it.categoryId }) { cat ->
            LiquidGlassChip(
                text = cat.categoryName,
                isSelected = selectedCategoryId == cat.categoryId,
                onClick = { onSelectCategory(cat.categoryId) },
                activeColor = KrasRed,
                modifier = Modifier.testTag("live_category_chip_${cat.categoryId}")
            )
        }
    }

    // Compact Quality Filter & Sort by Quality Chip Row
    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag("live_quality_filter_row")
    ) {
        items(qualityFilterOptions, key = { "q_$it" }) { qOpt ->
            val isSelected = selectedQualityFilter == qOpt
            val activeColor = when (qOpt) {
                "4K" -> Badge4K
                "FHD" -> BadgeFHD
                "HD" -> BadgeHD
                "SD" -> BadgeSD
                else -> KrasRed
            }
            LiquidGlassChip(
                text = if (qOpt == "All") "All Quality" else qOpt,
                isSelected = isSelected,
                onClick = { onSelectQualityFilter(qOpt) },
                activeColor = activeColor,
                modifier = Modifier.testTag("live_quality_chip_$qOpt")
            )
        }

        item(key = "sort_by_quality") {
            LiquidGlassChip(
                text = if (sortByQuality) "Sort by quality ↓" else "Sort by quality",
                isSelected = sortByQuality,
                onClick = onToggleSortByQuality,
                activeColor = KrasRed,
                modifier = Modifier.testTag("live_sort_by_quality_chip")
            )
        }
    }

    // Channel Count + Active Channel Status Line
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .alpha(pulseAlpha)
                    .background(KrasRedBright)
            )
            Text(
                text = "$totalCount Live Channels",
                color = TextSecondary,
                fontSize = 11.5.sp,
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (!activeChannelName.isNullOrBlank()) {
            Text(
                text = "Playing: $activeChannelName",
                color = KrasRedBright,
                fontSize = 11.sp,
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .testTag("live_now_playing_label")
            )
        }
    }
}

@Composable
private fun PersistentLiveChannelList(
    channels: List<MediaItem>,
    activeChannelId: String?,
    listState: LazyListState,
    isFavorite: (String) -> Boolean,
    onToggleFavorite: (MediaItem) -> Unit,
    onChannelClick: (MediaItem) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 2.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.testTag("live_channel_list")
    ) {
        items(
            items = channels,
            key = { it.id },
            contentType = { "live_channel_card" }
        ) { ch ->
            val fav = isFavorite(ch.id)
            val isPlayingThisChannel = ch.id == activeChannelId
            val effectiveQuality = ch.quality.ifBlank { ch.resolution }
            val epgInfo = remember(ch.id, ch.plot, ch.categoryName) {
                resolveLiveEpgInfo(ch)
            }

            val cardShape = RoundedCornerShape(20.dp)
            val rowModifier = if (isPlayingThisChannel) {
                Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .shadow(
                        elevation = 14.dp,
                        shape = cardShape,
                        ambientColor = KrasRed,
                        spotColor = KrasRedBright
                    )
                    .clip(cardShape)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                KrasRed.copy(alpha = 0.30f),
                                Color(0xEB19090D),
                                Color(0xEB0E111A)
                            )
                        )
                    )
                    .border(
                        width = 1.6.dp,
                        brush = Brush.horizontalGradient(
                            colors = listOf(KrasRedBright, KrasRed.copy(alpha = 0.55f))
                        ),
                        shape = cardShape
                    )
            } else {
                Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .clip(cardShape)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xCC111520),
                                Color(0xB80B0D14)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.09f),
                        shape = cardShape
                    )
            }

            Row(
                modifier = rowModifier
                    .liquidPressClickable { onChannelClick(ch) }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("channel_item_${ch.streamId}"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Rounded Logo Card with subtle play indicator
                Box(
                    modifier = Modifier
                        .width(76.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF07090E))
                        .border(
                            width = if (isPlayingThisChannel) 1.2.dp else 0.8.dp,
                            color = if (isPlayingThisChannel) KrasRedBright else Color.White.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(14.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val chIcon = ch.streamIcon.ifBlank { ch.backdrop }.takeIf { it.isNotBlank() }
                    if (chIcon != null) {
                        AsyncImage(
                            model = chIcon,
                            contentDescription = ch.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = ch.name.take(2).uppercase(),
                            color = if (isPlayingThisChannel) KrasRedBright else TextSecondary,
                            fontSize = 16.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    // Minimal floating play/live badge on logo corner
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (isPlayingThisChannel) KrasRed else Color.Black.copy(alpha = 0.68f))
                            .border(0.7.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play Live",
                            tint = Color.White,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(13.dp))

                // Channel Name, Active Red Badge, EPG Program Info & Quality Badge
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isPlayingThisChannel) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(KrasRedBright)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .testTag("active_channel_badge_${ch.streamId}")
                            ) {
                                Text(
                                    text = "LIVE",
                                    color = Color.White,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.6.sp
                                )
                            }
                        }
                        Text(
                            text = ch.name,
                            color = if (isPlayingThisChannel) Color.White else Color.White.copy(alpha = 0.95f),
                            fontSize = 14.5.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        ResolutionBadge(resolution = effectiveQuality, isLive = true)
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = epgInfo.currentTitle,
                        color = if (isPlayingThisChannel) Color.White.copy(alpha = 0.88f) else TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (isPlayingThisChannel) {
                                "CH ${ch.streamId} • Watching Now"
                            } else {
                                "CH ${ch.streamId} • ${epgInfo.timeWindowLabel}"
                            },
                            color = if (isPlayingThisChannel) KrasRedBright else TextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isPlayingThisChannel) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }

                // 48dp Accessible Favorite Button
                IconButton(
                    onClick = { onToggleFavorite(ch) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (fav) "Remove Favorite" else "Add Favorite",
                        tint = if (fav) KrasRedBright else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingLiveChannelDrawer(
    visible: Boolean,
    onDismiss: () -> Unit,
    channels: List<MediaItem>,
    activeChannelId: String?,
    drawerListState: LazyListState,
    selectedCategoryId: String,
    onSelectCategory: (String) -> Unit,
    distinctCategories: List<Category>,
    hasRecentChannels: Boolean,
    drawerSearchQuery: String,
    onDrawerSearchQueryChange: (String) -> Unit,
    isFavorite: (String) -> Boolean,
    onToggleFavorite: (MediaItem) -> Unit,
    onChannelClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = tween(240, easing = FastOutSlowInEasing)
        ) + fadeIn(tween(200)),
        exit = slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = tween(200, easing = FastOutSlowInEasing)
        ) + fadeOut(tween(180)),
        modifier = modifier
            .fillMaxHeight()
            .widthIn(min = 310.dp, max = 385.dp)
            .padding(vertical = 10.dp, horizontal = 8.dp)
            .testTag("live_fullscreen_channel_drawer")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .shadow(
                    elevation = 24.dp,
                    shape = RoundedCornerShape(26.dp),
                    ambientColor = Color.Black,
                    spotColor = KrasRed
                )
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xEB0B0E17),
                            Color(0xF507090F)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(KrasRedBright.copy(alpha = 0.45f), Color.White.copy(alpha = 0.14f))
                    ),
                    shape = RoundedCornerShape(26.dp)
                )
                .systemBarsPadding()
                .padding(vertical = 12.dp)
        ) {
            // Drawer Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(KrasRed.copy(alpha = 0.22f))
                            .border(1.dp, KrasRedBright.copy(alpha = 0.55f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.LiveTv,
                            contentDescription = null,
                            tint = KrasRedBright,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Live Channel Guide",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "${channels.size} channels available",
                            color = TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Channel List",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Compact Search inside Drawer
            OutlinedTextField(
                value = drawerSearchQuery,
                onValueChange = onDrawerSearchQueryChange,
                placeholder = {
                    Text("Filter channels...", color = TextSecondary, fontSize = 12.sp)
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = KrasRedBright,
                        modifier = Modifier.size(16.dp)
                    )
                },
                trailingIcon = {
                    if (drawerSearchQuery.isNotEmpty()) {
                        IconButton(onClick = { onDrawerSearchQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = KrasRedBright,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
                    focusedContainerColor = Color(0x99121622),
                    unfocusedContainerColor = Color(0x66121622),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .height(46.dp)
            )

            // Category Chips inside Floating Drawer
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "fs_cat_all") {
                    LiquidGlassChip(
                        text = "All",
                        isSelected = selectedCategoryId == "all",
                        onClick = { onSelectCategory("all") },
                        activeColor = KrasRed
                    )
                }
                item(key = "fs_cat_fav") {
                    LiquidGlassChip(
                        text = "Favorites",
                        isSelected = selectedCategoryId == "favorites",
                        onClick = { onSelectCategory("favorites") },
                        activeColor = KrasRed
                    )
                }
                if (hasRecentChannels) {
                    item(key = "fs_cat_recent") {
                        LiquidGlassChip(
                            text = "Recent",
                            isSelected = selectedCategoryId == "recent",
                            onClick = { onSelectCategory("recent") },
                            activeColor = KrasRed
                        )
                    }
                }
                items(distinctCategories, key = { "fs_${it.categoryId}" }) { cat ->
                    LiquidGlassChip(
                        text = cat.categoryName,
                        isSelected = selectedCategoryId == cat.categoryId,
                        onClick = { onSelectCategory(cat.categoryId) },
                        activeColor = KrasRed
                    )
                }
            }

            // Channel Cards inside Floating Drawer
            PersistentLiveChannelList(
                channels = channels,
                activeChannelId = activeChannelId,
                listState = drawerListState,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
                onChannelClick = onChannelClick,
                bottomPadding = 24.dp,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun EmbeddedLiveVideoPlayer(
    activeChannel: MediaItem?,
    playerWrapper: ExoPlayerWrapper?,
    isFullscreen: Boolean,
    isInPipMode: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onOpenChannelDrawer: () -> Unit,
    onPrevChannel: () -> Unit,
    onNextChannel: () -> Unit,
    onRetryPlayback: () -> Unit,
    onStopPlayback: () -> Unit,
    onStartFirstChannel: () -> Unit,
    defaultAspectRatio: AspectRatioMode,
    swipeGesturesEnabled: Boolean,
    onToggleSwipeGestures: (Boolean) -> Unit,
    backgroundPlaybackEnabled: Boolean,
    onToggleBackgroundPlayback: (Boolean) -> Unit,
    onMinimizeToMiniPlayer: (() -> Unit)?,
    onEnterPip: () -> Unit,
    pulseAlpha: Float,
    modifier: Modifier = Modifier
) {
    var showOverlayControls by remember { mutableStateOf(false) }
    var controlInteractionTick by remember { mutableIntStateOf(0) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var aspectMode by remember(defaultAspectRatio) { mutableStateOf(defaultAspectRatio) }
    var isMuted by remember { mutableStateOf(false) }
    var swipeIndicatorText by remember { mutableStateOf<String?>(null) }
    var surfaceWidthPx by remember { mutableFloatStateOf(1080f) }

    val haptic = LocalHapticFeedback.current

    val uiState = if (playerWrapper != null) {
        playerWrapper.playerUiState.collectAsState().value
    } else {
        PlayerUiState.PLAYING
    }
    val isPlaying = if (playerWrapper != null) {
        playerWrapper.isPlaying.collectAsState().value
    } else {
        activeChannel != null
    }
    val hasRenderedFirstFrame = if (playerWrapper != null) {
        playerWrapper.hasRenderedFirstFrame.collectAsState().value
    } else {
        activeChannel != null
    }
    val errorMessage = if (playerWrapper != null) {
        playerWrapper.errorMessage.collectAsState().value
    } else {
        null
    }
    val detectedQualityBadge = if (playerWrapper != null) {
        playerWrapper.detectedQualityBadge.collectAsState().value
    } else {
        ""
    }
    val realResolutionOverlay = if (playerWrapper != null) {
        playerWrapper.realResolutionOverlay.collectAsState().value
    } else {
        ""
    }
    val mediaController = if (playerWrapper != null) {
        playerWrapper.mediaController.collectAsState().value
    } else {
        null
    }
    val availableVideoVariants = if (playerWrapper != null) {
        playerWrapper.availableVideoVariants.collectAsState().value
    } else {
        listOf(VideoQualityVariant(label = "Auto", height = 0))
    }
    val selectedVideoVariant = if (playerWrapper != null) {
        playerWrapper.selectedVideoVariant.collectAsState().value
    } else {
        VideoQualityVariant(label = "Auto", height = 0)
    }
    val availableAudioTracks = if (playerWrapper != null) {
        playerWrapper.availableAudioTracks.collectAsState().value
    } else {
        listOf(PlayerTrackOption(id = "default_audio", label = "Original Audio (Default)"))
    }
    val selectedAudioTrack = if (playerWrapper != null) {
        playerWrapper.selectedAudioTrack.collectAsState().value
    } else {
        PlayerTrackOption(id = "default_audio", label = "Original Audio (Default)")
    }
    val availableSubtitleTracks = if (playerWrapper != null) {
        playerWrapper.availableSubtitleTracks.collectAsState().value
    } else {
        listOf(PlayerTrackOption(id = "sub_off", label = "Off", isOff = true))
    }
    val selectedSubtitleTrack = if (playerWrapper != null) {
        playerWrapper.selectedSubtitleTrack.collectAsState().value
    } else {
        PlayerTrackOption(id = "sub_off", label = "Off", isOff = true)
    }

    // Delayed subtle red spinner (only shown if preparation/buffering exceeds 500ms)
    val isLoadingState = activeChannel != null &&
            (uiState == PlayerUiState.PREPARING || uiState == PlayerUiState.BUFFERING) &&
            uiState != PlayerUiState.ERROR
    var showDelayedSpinner by remember { mutableStateOf(false) }
    LaunchedEffect(isLoadingState, activeChannel?.id) {
        if (isLoadingState) {
            showDelayedSpinner = false
            delay(500)
            showDelayedSpinner = true
        } else {
            showDelayedSpinner = false
        }
    }

    // Clear swipe feedback badge after 900ms
    LaunchedEffect(swipeIndicatorText) {
        if (swipeIndicatorText != null) {
            delay(900)
            swipeIndicatorText = null
        }
    }

    // Automatically hide floating controls after ~3 seconds (3000ms) of inactivity
    LaunchedEffect(showOverlayControls, controlInteractionTick, isPlaying, activeChannel?.id, showSettingsSheet) {
        if (showOverlayControls && !showSettingsSheet && activeChannel != null && uiState != PlayerUiState.ERROR) {
            delay(3000)
            showOverlayControls = false
        }
    }

    val bumpInteraction = {
        controlInteractionTick++
    }

    val epgInfo = remember(activeChannel?.id, activeChannel?.plot, activeChannel?.categoryName) {
        resolveLiveEpgInfo(activeChannel)
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .border(
                width = if (isFullscreen) 0.dp else 1.dp,
                color = if (isFullscreen) Color.Transparent else Color.White.copy(alpha = 0.14f),
                shape = if (isFullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(22.dp)
            )
            .onSizeChanged { size ->
                if (size.width > 0) {
                    surfaceWidthPx = size.width.toFloat()
                }
            }
            .pointerInput(activeChannel?.id, swipeGesturesEnabled, surfaceWidthPx) {
                var totalDragX = 0f
                var dragStartX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        totalDragX = 0f
                        dragStartX = offset.x
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        totalDragX += dragAmount
                    },
                    onDragEnd = {
                        val edgeZoneStart = surfaceWidthPx * 0.82f
                        val swipeThreshold = 90f
                        if (dragStartX >= edgeZoneStart && totalDragX < -swipeThreshold) {
                            // Swipe from right screen edge reveals floating channel drawer
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onOpenChannelDrawer()
                        } else if (swipeGesturesEnabled && activeChannel != null) {
                            if (totalDragX < -swipeThreshold) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                swipeIndicatorText = "Next Channel ⏭"
                                onNextChannel()
                            } else if (totalDragX > swipeThreshold) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                swipeIndicatorText = "⏮ Previous Channel"
                                onPrevChannel()
                            }
                        }
                    }
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                showOverlayControls = !showOverlayControls
                bumpInteraction()
            }
            .testTag("live_embedded_player")
    ) {
        if (activeChannel == null) {
            // Minimalist Futuristic Standby State before a channel is selected
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFF21070C), Color(0xFF06080E), Color.Black)
                        )
                    )
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .shadow(12.dp, RoundedCornerShape(8.dp), spotColor = KrasRedBright)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(KrasRedBright, KrasRedDark)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "K",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp
                            )
                        }
                        Text(
                            text = "KRAS TV LIVE",
                            color = Color.White,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            letterSpacing = 1.4.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Ultra-low latency cinema broadcast • Tap any channel or start instant stream",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = onStartFirstChannel,
                        colors = ButtonDefaults.buttonColors(containerColor = KrasRed),
                        shape = RoundedCornerShape(24.dp),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 9.dp),
                        modifier = Modifier
                            .shadow(14.dp, RoundedCornerShape(24.dp), spotColor = KrasRedBright)
                            .testTag("live_play_first_channel_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Start Live Broadcast",
                            color = Color.White,
                            fontSize = 12.5.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            // Media3 Player Surface connected to KrasMediaPlaybackService via MediaController (or single ExoPlayer)
            val boundPlayer = mediaController ?: playerWrapper?.getPlayer()
            if (boundPlayer != null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = boundPlayer
                            useController = false
                            keepScreenOn = true
                            // Keep current video frame visible during channel switches until next stream renders
                            setKeepContentOnPlayerReset(true)
                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                            resizeMode = when (aspectMode) {
                                AspectRatioMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                AspectRatioMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                AspectRatioMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                AspectRatioMode.RATIO_16_9 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                                AspectRatioMode.RATIO_4_3 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
                            }
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    update = { view ->
                        if (view.player !== boundPlayer) {
                            view.player = boundPlayer
                        }
                        view.setKeepContentOnPlayerReset(true)
                        view.resizeMode = when (aspectMode) {
                            AspectRatioMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            AspectRatioMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            AspectRatioMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            AspectRatioMode.RATIO_16_9 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                            AspectRatioMode.RATIO_4_3 -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Pure black cover ONLY before the very first frame of the session has rendered
            if (!hasRenderedFirstFrame && uiState != PlayerUiState.ERROR) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                )
            }

            // Subtle floating glass buffering spinner when preparation/buffering takes > 500ms
            if (showDelayedSpinner && uiState != PlayerUiState.ERROR) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("live_embedded_buffering_spinner"),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color(0xB8080A10))
                            .border(1.dp, KrasRedBright.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = KrasRedBright,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }

            // Horizontal Swipe Channel Switch Floating Feedback Pill
            AnimatedVisibility(
                visible = swipeIndicatorText != null,
                enter = fadeIn(tween(140)) + scaleIn(initialScale = 0.9f),
                exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.92f),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xD90B0E17))
                        .border(1.dp, KrasRedBright.copy(alpha = 0.65f), RoundedCornerShape(22.dp))
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = swipeIndicatorText.orEmpty(),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Elegant Frosted-Glass Error & Retry State
            if (uiState == PlayerUiState.ERROR) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.82f))
                        .padding(18.dp)
                        .testTag("live_embedded_error_overlay"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 340.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xE60E111B))
                            .border(1.dp, KrasRedBright.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Stream Error",
                            tint = KrasRedBright,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = errorMessage ?: "Live stream temporarily unavailable",
                            color = Color.White,
                            fontSize = 12.5.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onRetryPlayback()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = KrasRed),
                                shape = RoundedCornerShape(18.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("live_player_retry_button")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Retry Stream", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = onNextChannel,
                                shape = RoundedCornerShape(18.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.SkipNext, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Next CH", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // Minimal Floating Control Overlay (Soft Fade + Slide Animation, Auto-Hides after 3s)
            AnimatedVisibility(
                visible = showOverlayControls && !isInPipMode && uiState != PlayerUiState.ERROR,
                enter = fadeIn(tween(220)) + slideInVertically(
                    initialOffsetY = { it / 10 },
                    animationSpec = tween(240, easing = FastOutSlowInEasing)
                ),
                exit = fadeOut(tween(200)) + slideOutVertically(
                    targetOffsetY = { it / 12 },
                    animationSpec = tween(200, easing = FastOutSlowInEasing)
                ),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.62f),
                                    Color.Transparent,
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.72f)
                                )
                            )
                        )
                ) {
                    // TOP FLOATING GLASS BAR: Live Pulse + Channel Info + EPG + Quick Utility Icons
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .then(if (isFullscreen) Modifier.statusBarsPadding() else Modifier)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("live_top_status_bar"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left Floating Channel Identity Pill
                        Row(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xBF0B0E17))
                                .border(0.8.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 11.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(KrasRed)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.5.dp)
                                            .clip(CircleShape)
                                            .alpha(pulseAlpha)
                                            .background(Color.White)
                                    )
                                    Text(
                                        text = "LIVE",
                                        color = Color.White,
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text(
                                    text = activeChannel.name,
                                    color = Color.White,
                                    fontSize = if (isFullscreen) 14.sp else 12.5.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isFullscreen || realResolutionOverlay.isNotBlank()) {
                                    Text(
                                        text = if (realResolutionOverlay.isNotBlank()) {
                                            "${epgInfo.currentTitle} • $realResolutionOverlay"
                                        } else {
                                            epgInfo.currentTitle
                                        },
                                        color = TextSecondary,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            val effectiveBadge = detectedQualityBadge.ifBlank {
                                activeChannel.quality.ifBlank { activeChannel.resolution }
                            }
                            ResolutionBadge(
                                resolution = effectiveBadge,
                                isLive = true,
                                maxExtraTags = 1
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Right Floating Quick Utility Actions (Favorite, Mute, PiP, Mini-Player, Stop)
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xBF0B0E17))
                                .border(0.8.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    bumpInteraction()
                                    onToggleFavorite()
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "Favorite Channel",
                                    tint = if (isFavorite) KrasRedBright else Color.White,
                                    modifier = Modifier.size(17.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    bumpInteraction()
                                    isMuted = !isMuted
                                    playerWrapper?.getPlayer()?.volume = if (isMuted) 0f else 1f
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = "Mute",
                                    tint = if (isMuted) KrasRedBright else Color.White,
                                    modifier = Modifier.size(17.dp)
                                )
                            }

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                IconButton(
                                    onClick = {
                                        bumpInteraction()
                                        onEnterPip()
                                    },
                                    modifier = Modifier
                                        .size(34.dp)
                                        .testTag("live_pip_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PictureInPictureAlt,
                                        contentDescription = "Picture in Picture",
                                        tint = Color.White,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }

                            if (onMinimizeToMiniPlayer != null) {
                                IconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onMinimizeToMiniPlayer()
                                    },
                                    modifier = Modifier
                                        .size(34.dp)
                                        .testTag("live_minimize_mini_player_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AspectRatio,
                                        contentDescription = "Mini Player",
                                        tint = Color.White,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onStopPlayback()
                                },
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("live_embedded_stop_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop Playback",
                                    tint = Color.White,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }

                    // BOTTOM TRANSLUCENT FLOATING CONTROL PILL
                    // Contains essential controls: Previous Channel, Play/Pause, Next Channel, Channel List, Settings, Fullscreen
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .then(if (isFullscreen) Modifier.navigationBarsPadding() else Modifier)
                            .padding(horizontal = 14.dp, vertical = if (isFullscreen) 16.dp else 10.dp)
                            .shadow(
                                elevation = 20.dp,
                                shape = RoundedCornerShape(30.dp),
                                ambientColor = Color.Black,
                                spotColor = KrasRed
                            )
                            .clip(RoundedCornerShape(30.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xD80C0F18),
                                        Color(0xE6121624),
                                        Color(0xD80C0F18)
                                    )
                                )
                            )
                            .border(
                                width = 1.dp,
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.20f),
                                        KrasRedBright.copy(alpha = 0.45f),
                                        Color.White.copy(alpha = 0.20f)
                                    )
                                ),
                                shape = RoundedCornerShape(30.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("live_floating_control_pill"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. Previous Channel
                        IconButton(
                            onClick = {
                                bumpInteraction()
                                onPrevChannel()
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.07f))
                                .testTag("live_prev_channel_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "Previous Channel",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // 2. Play / Pause (with subtle red glowing ring)
                        IconButton(
                            onClick = {
                                bumpInteraction()
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                playerWrapper?.togglePlayPause()
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .shadow(10.dp, CircleShape, spotColor = KrasRedBright)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(KrasRed, Color(0xFF1A070B))
                                    )
                                )
                                .border(1.4.dp, KrasRedBright, CircleShape)
                                .testTag("live_embedded_play_pause_button")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // 3. Next Channel
                        IconButton(
                            onClick = {
                                bumpInteraction()
                                onNextChannel()
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.07f))
                                .testTag("live_next_channel_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Next Channel",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Subtle vertical divider inside pill
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(22.dp)
                                .background(Color.White.copy(alpha = 0.14f))
                        )

                        // 4. Channel List Drawer Button
                        IconButton(
                            onClick = {
                                bumpInteraction()
                                onOpenChannelDrawer()
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.07f))
                                .testTag("live_fullscreen_channels_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                                contentDescription = "Channel List",
                                tint = Color.White,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        // 5. Player Settings Button (Aspect Ratio, Audio, Subtitles, Stream Quality, Gestures)
                        IconButton(
                            onClick = {
                                bumpInteraction()
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                showSettingsSheet = true
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (showSettingsSheet) KrasRed.copy(alpha = 0.35f)
                                    else Color.White.copy(alpha = 0.07f)
                                )
                                .testTag("live_player_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Player Settings",
                                tint = Color.White,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        // 6. Fullscreen Toggle Button
                        IconButton(
                            onClick = {
                                bumpInteraction()
                                onToggleFullscreen()
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.07f))
                                .testTag(if (isFullscreen) "live_exit_fullscreen_button" else "live_embedded_fullscreen_button")
                        ) {
                            Icon(
                                imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = if (isFullscreen) "Exit Fullscreen" else "Fullscreen",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Floating Frosted-Glass Live Player Settings Sheet (Aspect Ratio, Quality, Audio, Subtitles, Gestures)
            AnimatedVisibility(
                visible = showSettingsSheet,
                enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.94f),
                exit = fadeOut(tween(160)) + scaleOut(targetScale = 0.94f),
                modifier = Modifier.fillMaxSize()
            ) {
                LivePlayerSettingsOverlay(
                    aspectMode = aspectMode,
                    onSelectAspectMode = { mode ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        aspectMode = mode
                    },
                    availableVideoVariants = availableVideoVariants,
                    selectedVideoVariant = selectedVideoVariant,
                    onSelectVideoVariant = { variant ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        playerWrapper?.selectVideoQuality(variant)
                    },
                    availableAudioTracks = availableAudioTracks,
                    selectedAudioTrack = selectedAudioTrack,
                    onSelectAudioTrack = { track ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        playerWrapper?.selectAudioTrack(track)
                    },
                    availableSubtitleTracks = availableSubtitleTracks,
                    selectedSubtitleTrack = selectedSubtitleTrack,
                    onSelectSubtitleTrack = { sub ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        playerWrapper?.selectSubtitleTrack(sub)
                    },
                    swipeGesturesEnabled = swipeGesturesEnabled,
                    onToggleSwipeGestures = { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleSwipeGestures(enabled)
                    },
                    backgroundPlaybackEnabled = backgroundPlaybackEnabled,
                    onToggleBackgroundPlayback = { enabled ->
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleBackgroundPlayback(enabled)
                    },
                    onDismiss = { showSettingsSheet = false }
                )
            }
        }
    }
}

@Composable
private fun LivePlayerSettingsOverlay(
    aspectMode: AspectRatioMode,
    onSelectAspectMode: (AspectRatioMode) -> Unit,
    availableVideoVariants: List<VideoQualityVariant>,
    selectedVideoVariant: VideoQualityVariant,
    onSelectVideoVariant: (VideoQualityVariant) -> Unit,
    availableAudioTracks: List<PlayerTrackOption>,
    selectedAudioTrack: PlayerTrackOption,
    onSelectAudioTrack: (PlayerTrackOption) -> Unit,
    availableSubtitleTracks: List<PlayerTrackOption>,
    selectedSubtitleTrack: PlayerTrackOption,
    onSelectSubtitleTrack: (PlayerTrackOption) -> Unit,
    swipeGesturesEnabled: Boolean,
    onToggleSwipeGestures: (Boolean) -> Unit,
    backgroundPlaybackEnabled: Boolean,
    onToggleBackgroundPlayback: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.76f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .padding(14.dp)
            .testTag("live_settings_sheet"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xF5101420), Color(0xFA090B12))
                    )
                )
                .border(1.dp, KrasRedBright.copy(alpha = 0.42f), RoundedCornerShape(24.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = KrasRedBright,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Live Player Settings",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Settings",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // 1. Aspect Ratio Controls
            Text(
                text = "Aspect Ratio",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AspectRatioMode.entries) { mode ->
                    val selected = aspectMode == mode
                    val modeLabel = when (mode) {
                        AspectRatioMode.FIT -> "Fit"
                        AspectRatioMode.FILL -> "Fill (Crop)"
                        AspectRatioMode.STRETCH -> "Stretch"
                        AspectRatioMode.RATIO_16_9 -> "16:9"
                        AspectRatioMode.RATIO_4_3 -> "4:3"
                    }
                    LiquidGlassChip(
                        text = modeLabel,
                        isSelected = selected,
                        onClick = { onSelectAspectMode(mode) },
                        activeColor = KrasRed
                    )
                }
            }

            // 2. Stream Quality Variants
            Text(
                text = "Stream Quality",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(availableVideoVariants, key = { "${it.label}_${it.height}" }) { variant ->
                    val selected = selectedVideoVariant.label == variant.label &&
                            selectedVideoVariant.height == variant.height
                    LiquidGlassChip(
                        text = variant.label,
                        isSelected = selected,
                        onClick = { onSelectVideoVariant(variant) },
                        activeColor = KrasRed
                    )
                }
            }

            // 3. Audio Tracks
            Text(
                text = "Audio Track",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(availableAudioTracks, key = { it.id }) { track ->
                    val selected = selectedAudioTrack.id == track.id
                    LiquidGlassChip(
                        text = track.label,
                        isSelected = selected,
                        onClick = { onSelectAudioTrack(track) },
                        activeColor = KrasRed
                    )
                }
            }

            // 4. Subtitles / CC
            Text(
                text = "Subtitles / CC",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(availableSubtitleTracks, key = { it.id }) { sub ->
                    val selected = selectedSubtitleTrack.id == sub.id
                    LiquidGlassChip(
                        text = sub.label,
                        isSelected = selected,
                        onClick = { onSelectSubtitleTrack(sub) },
                        activeColor = KrasRed
                    )
                }
            }

            // 5. Horizontal Swipe Channel Gestures Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Swipe,
                        contentDescription = null,
                        tint = KrasRedBright,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "Horizontal Swipe Channel Switch",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Swipe left or right on video to switch channels",
                            color = TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                }
                Switch(
                    checked = swipeGesturesEnabled,
                    onCheckedChange = onToggleSwipeGestures,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = KrasRedBright,
                        checkedTrackColor = KrasRedDark
                    ),
                    modifier = Modifier.testTag("live_gesture_toggle_switch")
                )
            }

            // 6. Background Audio Playback Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Audiotrack,
                        contentDescription = null,
                        tint = KrasRedBright,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "Background Audio & Notification",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Keep live audio playing with One UI media controls",
                            color = TextSecondary,
                            fontSize = 10.sp
                        )
                    }
                }
                Switch(
                    checked = backgroundPlaybackEnabled,
                    onCheckedChange = onToggleBackgroundPlayback,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = KrasRedBright,
                        checkedTrackColor = KrasRedDark
                    )
                )
            }
        }
    }
}

/**
 * Floating In-App Live TV Mini Player docked cleanly above the bottom navigation bar
 * when the user browses Home, Movies, Series, or Settings while a Live TV channel is active.
 */
@OptIn(UnstableApi::class)
@Composable
fun LiveMiniPlayerOverlay(
    channel: MediaItem,
    playerWrapper: ExoPlayerWrapper,
    onExpandClick: () -> Unit,
    onNextChannelClick: () -> Unit,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPlaying by playerWrapper.isPlaying.collectAsState()
    val mediaController by playerWrapper.mediaController.collectAsState()
    val boundPlayer = mediaController ?: playerWrapper.getPlayer()
    val haptic = LocalHapticFeedback.current

    val epgInfo = remember(channel.id, channel.plot, channel.categoryName) {
        resolveLiveEpgInfo(channel)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(74.dp)
            .shadow(
                elevation = 22.dp,
                shape = RoundedCornerShape(22.dp),
                ambientColor = Color.Black,
                spotColor = KrasRedBright
            )
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xF212090D),
                        Color(0xF50B0E17),
                        Color(0xF20D101B)
                    )
                )
            )
            .border(
                width = 1.2.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(KrasRedBright.copy(alpha = 0.65f), Color.White.copy(alpha = 0.16f))
                ),
                shape = RoundedCornerShape(22.dp)
            )
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onExpandClick()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("live_mini_player_overlay"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Live Mini Video Surface
        Box(
            modifier = Modifier
                .width(98.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
                .border(1.dp, KrasRedBright.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = boundPlayer
                        useController = false
                        setKeepContentOnPlayerReset(true)
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = { view ->
                    if (view.player !== boundPlayer) {
                        view.player = boundPlayer
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(KrasRed)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "LIVE",
                    color = Color.White,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Channel Name + EPG Program Info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = channel.name,
                color = Color.White,
                fontSize = 13.5.sp,
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = epgInfo.currentTitle,
                color = TextSecondary,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Mini Player Controls: Play/Pause, Next Channel, Expand, Close
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    playerWrapper.togglePlayPause()
                },
                modifier = Modifier
                    .size(38.dp)
                    .testTag("mini_player_play_pause")
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onNextChannelClick()
                },
                modifier = Modifier
                    .size(38.dp)
                    .testTag("mini_player_next")
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next Channel",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onExpandClick()
                },
                modifier = Modifier
                    .size(38.dp)
                    .testTag("mini_player_expand")
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInFull,
                    contentDescription = "Expand Live Player",
                    tint = KrasRedBright,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onCloseClick()
                },
                modifier = Modifier
                    .size(38.dp)
                    .testTag("mini_player_close")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Stop Live Player",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
