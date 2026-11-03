package com.example.ui.screens

import android.app.Activity
import android.content.res.Configuration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import com.example.player.PlayerUiState
import com.example.ui.components.DynamicAmbientPosterBackdrop
import com.example.ui.components.LiquidGlassChip
import com.example.ui.components.ResolutionBadge
import com.example.ui.components.liquidGlassPanel
import com.example.ui.components.liquidPressClickable
import com.example.ui.theme.*
import kotlinx.coroutines.delay

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
    defaultAspectRatio: AspectRatioMode = AspectRatioMode.FIT,
    onLoadMore: (() -> Unit)? = null,
    onFilterChanged: ((categoryId: String, query: String, qualityFilter: String, sortByQuality: Boolean) -> Unit)? = null
) {
    var selectedCategoryId by rememberSaveable { mutableStateOf("all") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedQualityFilter by rememberSaveable { mutableStateOf("All") }
    var sortByQuality by rememberSaveable { mutableStateOf(false) }
    var localSelectedChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    var isFullscreenDrawerOpen by rememberSaveable { mutableStateOf(false) }

    // Persistent LazyListState preserved across channel switches and fullscreen transitions
    val listState = rememberLazyListState()
    val fullscreenDrawerListState = rememberLazyListState()

    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Manage immersive system bars when Live TV player enters/exits fullscreen
    DisposableEffect(isFullscreen) {
        if (activity != null) {
            if (isFullscreen) {
                PlayerImmersiveHelper.enterImmersiveMode(activity)
            } else {
                PlayerImmersiveHelper.exitImmersiveMode(activity)
            }
        }
        onDispose {
            if (isFullscreen && activity != null) {
                PlayerImmersiveHelper.exitImmersiveMode(activity)
            }
        }
    }

    // BackHandler in fullscreen mode returns to embedded player + persistent channel list
    BackHandler(enabled = isFullscreen) {
        if (isFullscreenDrawerOpen) {
            isFullscreenDrawerOpen = false
        } else {
            onToggleFullscreen(false)
        }
    }

    val qualityFilterOptions = remember { listOf("All", "4K", "FHD", "HD", "SD") }

    LaunchedEffect(selectedCategoryId, searchQuery, selectedQualityFilter, sortByQuality) {
        onFilterChanged?.invoke(selectedCategoryId, searchQuery, selectedQualityFilter, sortByQuality)
    }

    val filteredChannels = remember(
        channels,
        selectedCategoryId,
        searchQuery,
        selectedQualityFilter,
        sortByQuality,
        onFilterChanged,
        liveChannelOrder
    ) {
        if (onFilterChanged != null) {
            channels
        } else {
            val filtered = channels.filter { ch ->
                val matchCat = when (selectedCategoryId) {
                    "all", "live_all" -> true
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
            val ordered = ChannelNameNormalizer.sortMediaItems(filtered, liveChannelOrder)
            if (sortByQuality) {
                QualityDetector.sortMediaByQualityDescending(ordered)
            } else {
                ordered
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
        localSelectedChannelId = ch.id
        onChannelClick(ch)
    }

    val handlePrevNextChannel: (Int) -> Unit = { delta ->
        if (filteredChannels.isNotEmpty()) {
            val curIdx = filteredChannels.indexOfFirst { it.id == currentPlayingChannel?.id }
            val targetIdx = if (curIdx < 0) 0 else (curIdx + delta + filteredChannels.size) % filteredChannels.size
            handleChannelSelect(filteredChannels[targetIdx])
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
        initialValue = 0.3f,
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
            .background(CinemaBlack)
            .testTag("live_tv_screen")
    ) {
        DynamicAmbientPosterBackdrop(
            imageUrl = ambientChannelArt,
            ambientAlpha = 0.18f
        )

        if (isFullscreen) {
            // FULLSCREEN LIVE TV MODE (with sliding Channel List Overlay Drawer)
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
                    isFavorite = currentPlayingChannel?.let { isFavorite(it.id) } ?: false,
                    onToggleFavorite = { currentPlayingChannel?.let(onToggleFavorite) },
                    onToggleFullscreen = {
                        isFullscreenDrawerOpen = false
                        onToggleFullscreen(false)
                    },
                    onOpenChannelDrawer = { isFullscreenDrawerOpen = !isFullscreenDrawerOpen },
                    onPrevChannel = { handlePrevNextChannel(-1) },
                    onNextChannel = { handlePrevNextChannel(1) },
                    onRetryPlayback = onRetryPlayback,
                    onStartFirstChannel = {
                        filteredChannels.firstOrNull()?.let(handleChannelSelect)
                    },
                    defaultAspectRatio = defaultAspectRatio,
                    pulseAlpha = pulseAlpha,
                    modifier = Modifier.fillMaxSize()
                )

                // Sliding Channel List Overlay inside Fullscreen
                AnimatedVisibility(
                    visible = isFullscreenDrawerOpen,
                    enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(tween(200)),
                    exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(tween(180)),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .widthIn(min = 300.dp, max = 380.dp)
                        .testTag("live_fullscreen_channel_drawer")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color(0xE6080A10), Color(0xF805060A))
                                )
                            )
                            .border(1.dp, GlassBorder, RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                            .systemBarsPadding()
                            .padding(vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LiveTv,
                                    contentDescription = null,
                                    tint = KrasRedBright,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Live Channels",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            IconButton(
                                onClick = { isFullscreenDrawerOpen = false },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Channel List",
                                    tint = Color.White
                                )
                            }
                        }

                        // Category Chips inside Fullscreen Overlay
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item(key = "fs_cat_all") {
                                LiquidGlassChip(
                                    text = "All Channels",
                                    isSelected = selectedCategoryId == "all",
                                    onClick = { selectedCategoryId = "all" },
                                    activeColor = KrasRed
                                )
                            }
                            item(key = "fs_cat_fav") {
                                LiquidGlassChip(
                                    text = "Favorites",
                                    isSelected = selectedCategoryId == "favorites",
                                    onClick = { selectedCategoryId = "favorites" },
                                    activeColor = KrasRed
                                )
                            }
                            items(distinctCategories, key = { "fs_${it.categoryId}" }) { cat ->
                                LiquidGlassChip(
                                    text = cat.categoryName,
                                    isSelected = selectedCategoryId == cat.categoryId,
                                    onClick = { selectedCategoryId = cat.categoryId },
                                    activeColor = KrasRed
                                )
                            }
                        }

                        // Persistent Channel List inside Fullscreen Overlay
                        PersistentLiveChannelList(
                            channels = filteredChannels,
                            activeChannelId = currentPlayingChannel?.id,
                            listState = fullscreenDrawerListState,
                            isFavorite = isFavorite,
                            onToggleFavorite = onToggleFavorite,
                            onChannelClick = { ch ->
                                handleChannelSelect(ch)
                            },
                            bottomPadding = 24.dp,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        } else if (isLandscape) {
            // LANDSCAPE SIDE-BY-SIDE PERSISTENT LAYOUT
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                // Left Pane: Embedded Video Player + Active Channel EPG Details
                Column(
                    modifier = Modifier
                        .weight(0.52f)
                        .fillMaxHeight()
                        .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 90.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    EmbeddedLiveVideoPlayer(
                        activeChannel = currentPlayingChannel,
                        playerWrapper = playerWrapper,
                        isFullscreen = false,
                        isFavorite = currentPlayingChannel?.let { isFavorite(it.id) } ?: false,
                        onToggleFavorite = { currentPlayingChannel?.let(onToggleFavorite) },
                        onToggleFullscreen = { onToggleFullscreen(true) },
                        onOpenChannelDrawer = {},
                        onPrevChannel = { handlePrevNextChannel(-1) },
                        onNextChannel = { handlePrevNextChannel(1) },
                        onRetryPlayback = onRetryPlayback,
                        onStartFirstChannel = {
                            filteredChannels.firstOrNull()?.let(handleChannelSelect)
                        },
                        defaultAspectRatio = defaultAspectRatio,
                        pulseAlpha = pulseAlpha,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                    )

                    ActiveChannelEpgStrip(
                        activeChannel = currentPlayingChannel,
                        pulseAlpha = pulseAlpha,
                        onExpandFullscreen = { onToggleFullscreen(true) }
                    )
                }

                // Right Pane: Persistent Category Selector & Scrollable Channel List
                Column(
                    modifier = Modifier
                        .weight(0.48f)
                        .fillMaxHeight()
                ) {
                    LiveTvFilterAndCategoryControls(
                        searchQuery = searchQuery,
                        onSearchQueryChange = { searchQuery = it },
                        selectedCategoryId = selectedCategoryId,
                        onSelectCategory = { selectedCategoryId = it },
                        distinctCategories = distinctCategories,
                        qualityFilterOptions = qualityFilterOptions,
                        selectedQualityFilter = selectedQualityFilter,
                        onSelectQualityFilter = { selectedQualityFilter = it },
                        sortByQuality = sortByQuality,
                        onToggleSortByQuality = { sortByQuality = !sortByQuality },
                        totalCount = totalCount.coerceAtLeast(filteredChannels.size),
                        activeChannelName = currentPlayingChannel?.name,
                        pulseAlpha = pulseAlpha
                    )

                    PersistentLiveChannelList(
                        channels = filteredChannels,
                        activeChannelId = currentPlayingChannel?.id,
                        listState = listState,
                        isFavorite = isFavorite,
                        onToggleFavorite = onToggleFavorite,
                        onChannelClick = handleChannelSelect,
                        bottomPadding = 100.dp,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        } else {
            // PORTRAIT PERSISTENT LAYOUT (Embedded Video Player at Top + Channel List Underneath)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                // Embedded Live TV Video Player at the Top
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    EmbeddedLiveVideoPlayer(
                        activeChannel = currentPlayingChannel,
                        playerWrapper = playerWrapper,
                        isFullscreen = false,
                        isFavorite = currentPlayingChannel?.let { isFavorite(it.id) } ?: false,
                        onToggleFavorite = { currentPlayingChannel?.let(onToggleFavorite) },
                        onToggleFullscreen = { onToggleFullscreen(true) },
                        onOpenChannelDrawer = {},
                        onPrevChannel = { handlePrevNextChannel(-1) },
                        onNextChannel = { handlePrevNextChannel(1) },
                        onRetryPlayback = onRetryPlayback,
                        onStartFirstChannel = {
                            filteredChannels.firstOrNull()?.let(handleChannelSelect)
                        },
                        defaultAspectRatio = defaultAspectRatio,
                        pulseAlpha = pulseAlpha,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(212.dp)
                            .clip(RoundedCornerShape(18.dp))
                    )
                }

                // Search, Category Selector, and Quality Filters
                LiveTvFilterAndCategoryControls(
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    selectedCategoryId = selectedCategoryId,
                    onSelectCategory = { selectedCategoryId = it },
                    distinctCategories = distinctCategories,
                    qualityFilterOptions = qualityFilterOptions,
                    selectedQualityFilter = selectedQualityFilter,
                    onSelectQualityFilter = { selectedQualityFilter = it },
                    sortByQuality = sortByQuality,
                    onToggleSortByQuality = { sortByQuality = !sortByQuality },
                    totalCount = totalCount.coerceAtLeast(filteredChannels.size),
                    activeChannelName = currentPlayingChannel?.name,
                    pulseAlpha = pulseAlpha
                )

                // Persistent Scrollable Channel List Underneath
                PersistentLiveChannelList(
                    channels = filteredChannels,
                    activeChannelId = currentPlayingChannel?.id,
                    listState = listState,
                    isFavorite = isFavorite,
                    onToggleFavorite = onToggleFavorite,
                    onChannelClick = handleChannelSelect,
                    bottomPadding = 100.dp,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun LiveTvFilterAndCategoryControls(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedCategoryId: String,
    onSelectCategory: (String) -> Unit,
    distinctCategories: List<Category>,
    qualityFilterOptions: List<String>,
    selectedQualityFilter: String,
    onSelectQualityFilter: (String) -> Unit,
    sortByQuality: Boolean,
    onToggleSortByQuality: () -> Unit,
    totalCount: Int,
    activeChannelName: String?,
    pulseAlpha: Float
) {
    // Search bar
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = {
                Text(
                    text = "Search Live Channels, Sports, News...",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = KrasRedBright)
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = KrasRedBright,
                unfocusedBorderColor = GlassBorder,
                focusedContainerColor = Color(0x8810131C),
                unfocusedContainerColor = Color(0x6610131C),
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

    // Category Chips
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "cat_all") {
            LiquidGlassChip(
                text = "All Channels",
                isSelected = selectedCategoryId == "all",
                onClick = { onSelectCategory("all") },
                activeColor = MaterialTheme.colorScheme.primary
            )
        }

        item(key = "cat_favorites") {
            LiquidGlassChip(
                text = "Favorites",
                isSelected = selectedCategoryId == "favorites",
                onClick = { onSelectCategory("favorites") },
                activeColor = MaterialTheme.colorScheme.primary
            )
        }

        items(distinctCategories, key = { it.categoryId }) { cat ->
            LiquidGlassChip(
                text = cat.categoryName,
                isSelected = selectedCategoryId == cat.categoryId,
                onClick = { onSelectCategory(cat.categoryId) },
                activeColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("live_category_chip_${cat.categoryId}")
            )
        }
    }

    // Quality Filter & Sort by Quality Chip Row
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 3.dp),
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

    // Count + Active Channel Indicator
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
                    .size(8.dp)
                    .clip(CircleShape)
                    .alpha(pulseAlpha)
                    .background(KrasRedBright)
            )
            Text(
                text = "$totalCount Live Streams Online",
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.Bold
            )
        }

        if (!activeChannelName.isNullOrBlank()) {
            Text(
                text = "Now Playing: $activeChannelName",
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
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.testTag("live_channel_list")
    ) {
        items(channels, key = { it.id }) { ch ->
            val fav = isFavorite(ch.id)
            val isPlayingThisChannel = ch.id == activeChannelId
            val effectiveQuality = ch.quality.ifBlank { ch.resolution }

            val rowModifier = if (isPlayingThisChannel) {
                Modifier
                    .fillMaxWidth()
                    .height(86.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                KrasRed.copy(alpha = 0.28f),
                                Color(0xCC180A0E),
                                Color(0xCC10131C)
                            )
                        )
                    )
                    .border(1.8.dp, KrasRedBright, RoundedCornerShape(18.dp))
            } else {
                Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .liquidGlassPanel(
                        cornerRadius = 18.dp,
                        surfaceAlpha = 0.72f,
                        borderAlpha = 0.42f
                    )
            }

            Row(
                modifier = rowModifier
                    .liquidPressClickable { onChannelClick(ch) }
                    .padding(10.dp)
                    .testTag("channel_item_${ch.streamId}"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Logo / Preview
                Box(
                    modifier = Modifier
                        .width(82.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CinemaDark)
                        .border(
                            width = if (isPlayingThisChannel) 1.2.dp else 0.75.dp,
                            color = if (isPlayingThisChannel) KrasRedBright else Color.White.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(12.dp)
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
                            text = ch.name.firstOrNull()?.uppercase() ?: "TV",
                            color = KrasRedBright,
                            fontSize = 18.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(if (isPlayingThisChannel) KrasRed else Color.Black.copy(alpha = 0.58f))
                            .border(0.8.dp, Color.White.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play Live",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Channel Name, Active Red Badge, EPG Program Info & Quality Badge
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isPlayingThisChannel) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(KrasRedBright)
                                    .padding(horizontal = 5.dp, vertical = 1.5.dp)
                                    .testTag("active_channel_badge_${ch.streamId}")
                            ) {
                                Text(
                                    text = "LIVE",
                                    color = Color.White,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                        Text(
                            text = ch.name,
                            color = if (isPlayingThisChannel) KrasRedBright else Color.White,
                            fontSize = 14.sp,
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
                        text = ch.plot.ifEmpty { "Live Program • ${ch.categoryName.ifEmpty { "Broadcast" }}" },
                        color = if (isPlayingThisChannel) Color.White.copy(alpha = 0.9f) else TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = if (isPlayingThisChannel) "Channel #${ch.streamId} • Playing Now" else "Channel #${ch.streamId}",
                        color = if (isPlayingThisChannel) KrasRedBright else TextMuted,
                        fontSize = 10.sp,
                        fontWeight = if (isPlayingThisChannel) FontWeight.SemiBold else FontWeight.Normal
                    )
                }

                // Favorite Button
                IconButton(onClick = { onToggleFavorite(ch) }) {
                    Icon(
                        imageVector = if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (fav) KrasRedBright else TextSecondary
                    )
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun EmbeddedLiveVideoPlayer(
    activeChannel: MediaItem?,
    playerWrapper: ExoPlayerWrapper?,
    isFullscreen: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onOpenChannelDrawer: () -> Unit,
    onPrevChannel: () -> Unit,
    onNextChannel: () -> Unit,
    onRetryPlayback: () -> Unit,
    onStartFirstChannel: () -> Unit,
    defaultAspectRatio: AspectRatioMode,
    pulseAlpha: Float,
    modifier: Modifier = Modifier
) {
    var showOverlayControls by remember { mutableStateOf(true) }
    var aspectMode by remember { mutableStateOf(defaultAspectRatio) }
    var isMuted by remember { mutableStateOf(false) }

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

    // Delayed small red spinner (only shown if preparation/buffering exceeds 550ms)
    val isLoadingState = activeChannel != null &&
            (uiState == PlayerUiState.PREPARING || uiState == PlayerUiState.BUFFERING) &&
            uiState != PlayerUiState.ERROR
    var showDelayedSpinner by remember { mutableStateOf(false) }
    LaunchedEffect(isLoadingState, activeChannel?.id) {
        if (isLoadingState) {
            showDelayedSpinner = false
            delay(550)
            showDelayedSpinner = true
        } else {
            showDelayedSpinner = false
        }
    }

    // Auto-hide controls after 4 seconds when playing
    LaunchedEffect(showOverlayControls, isPlaying, activeChannel?.id) {
        if (showOverlayControls && isPlaying && activeChannel != null && uiState != PlayerUiState.ERROR) {
            delay(4000)
            showOverlayControls = false
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .border(
                width = if (isFullscreen) 0.dp else 1.dp,
                color = if (isFullscreen) Color.Transparent else GlassBorder,
                shape = if (isFullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(18.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showOverlayControls = !showOverlayControls
            }
            .testTag("live_embedded_player")
    ) {
        if (activeChannel == null) {
            // Standby State before a channel is selected
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFF1F080C), Color(0xFF07080D))
                        )
                    )
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(KrasRed),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("K", color = Color.White, fontWeight = FontWeight.Black, fontSize = 14.sp)
                    }
                    Text(
                        text = "KRAS TV LIVE",
                        color = Color.White,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        letterSpacing = 1.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Select any channel below to start instant playback while browsing",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onStartFirstChannel,
                    colors = ButtonDefaults.buttonColors(containerColor = KrasRed),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("live_play_first_channel_button")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Watch Live Now", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            // Media3 ExoPlayer Surface
            val exoPlayer = playerWrapper?.getPlayer()
            if (exoPlayer != null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                            keepScreenOn = true
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
                        if (view.player !== exoPlayer) {
                            view.player = exoPlayer
                        }
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

            // Pure black cover until first video frame renders
            if (!hasRenderedFirstFrame && uiState != PlayerUiState.ERROR) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                )
            }

            // Small centered red spinner when preparation/buffering takes > 550ms
            if (showDelayedSpinner && uiState != PlayerUiState.ERROR) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("live_embedded_buffering_spinner"),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = KrasRed,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            // In-player Error Overlay (keeps channel list interactive so user can retry or pick another channel)
            if (uiState == PlayerUiState.ERROR) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.85f))
                        .padding(16.dp)
                        .testTag("live_embedded_error_overlay"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Stream Error",
                            tint = KrasRedBright,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = errorMessage ?: "Channel stream unavailable.",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            maxLines = 2
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onRetryPlayback,
                            colors = ButtonDefaults.buttonColors(containerColor = KrasRed),
                            shape = RoundedCornerShape(18.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("live_player_retry_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retry Stream", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Player Controls Overlay
            AnimatedVisibility(
                visible = showOverlayControls && uiState != PlayerUiState.ERROR,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(180)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.72f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.78f)
                                )
                            )
                        )
                ) {
                    // Top Bar: Brand + Active Channel Title + Quality + Mute + Aspect + Channels Drawer (in Fullscreen) + Fullscreen Toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
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
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(KrasRed)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .alpha(pulseAlpha)
                                            .background(Color.White)
                                    )
                                    Text(
                                        text = "LIVE",
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }

                            Text(
                                text = activeChannel.name,
                                color = Color.White,
                                fontSize = if (isFullscreen) 15.sp else 13.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )

                            val effectiveBadge = detectedQualityBadge.ifBlank {
                                activeChannel.quality.ifBlank { activeChannel.resolution }
                            }
                            ResolutionBadge(
                                resolution = effectiveBadge,
                                isLive = true,
                                maxExtraTags = 1
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (isFullscreen) {
                                // Open Persistent Channel List Drawer inside Fullscreen
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xAA141824))
                                        .border(1.dp, KrasRedBright.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                                        .clickable(onClick = onOpenChannelDrawer)
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                        .testTag("live_fullscreen_channels_button")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                                            contentDescription = "Channels",
                                            tint = Color.White,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            text = "Channels",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            IconButton(
                                onClick = {
                                    isMuted = !isMuted
                                    playerWrapper?.getPlayer()?.volume = if (isMuted) 0f else 1f
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = "Mute",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    val modes = AspectRatioMode.entries
                                    aspectMode = modes[(modes.indexOf(aspectMode) + 1) % modes.size]
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AspectRatio,
                                    contentDescription = "Aspect Ratio",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = onToggleFullscreen,
                                modifier = Modifier
                                    .size(34.dp)
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

                    // Center Controls: Previous Channel, Play/Pause, Next Channel (hidden while spinner is visible)
                    if (!showDelayedSpinner) {
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            IconButton(
                                onClick = onPrevChannel,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                                    .testTag("live_prev_channel_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipPrevious,
                                    contentDescription = "Previous Channel",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            IconButton(
                                onClick = { playerWrapper?.togglePlayPause() },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.68f))
                                    .border(1.5.dp, KrasRedBright, CircleShape)
                                    .testTag("live_embedded_play_pause_button")
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            IconButton(
                                onClick = onNextChannel,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                                    .testTag("live_next_channel_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = "Next Channel",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Bottom Bar: EPG Program Info + Real Video Specs + Favorite Button
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeChannel.plot.ifEmpty {
                                    "Live Broadcast • ${activeChannel.categoryName.ifEmpty { "Channel #${activeChannel.streamId}" }}"
                                },
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (realResolutionOverlay.isNotBlank()) {
                                Text(
                                    text = "Stream: $realResolutionOverlay",
                                    color = KrasRedBright,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        IconButton(
                            onClick = onToggleFavorite,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Favorite",
                                tint = if (isFavorite) KrasRedBright else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveChannelEpgStrip(
    activeChannel: MediaItem?,
    pulseAlpha: Float,
    onExpandFullscreen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlassPanel(cornerRadius = 14.dp, surfaceAlpha = 0.68f, borderAlpha = 0.4f)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (activeChannel != null) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .alpha(pulseAlpha)
                            .background(KrasRedBright)
                    )
                    Text(
                        text = activeChannel.name,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = activeChannel.plot.ifEmpty { "Live Broadcast • ${activeChannel.categoryName}" },
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onExpandFullscreen) {
                Icon(Icons.Default.Fullscreen, contentDescription = null, tint = KrasRedBright, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Fullscreen", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Text(
                text = "Tap any channel on the right to start watching live",
                color = TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}
