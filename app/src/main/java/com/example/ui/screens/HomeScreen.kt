package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CardStyle
import com.example.data.model.FavoriteItem
import com.example.data.model.MediaItem
import com.example.data.model.WatchProgress
import com.example.ui.components.DynamicAmbientPosterBackdrop
import com.example.ui.components.HeroBannerCarousel
import com.example.ui.components.HeroBannerSkeleton
import com.example.ui.components.LiquidGlassChip
import com.example.ui.components.MediaRow
import com.example.ui.components.MediaRowSkeleton
import com.example.ui.components.ResolutionBadge
import com.example.ui.components.liquidGlassPanel
import com.example.ui.theme.*

@Composable
fun HomeScreen(
    movies: List<MediaItem>,
    series: List<MediaItem>,
    liveStreams: List<MediaItem>,
    watchHistory: List<WatchProgress>,
    isFavorite: (String) -> Boolean,
    onToggleFavorite: (MediaItem) -> Unit,
    onMediaClick: (MediaItem) -> Unit,
    onSearchClick: () -> Unit,
    onAccountClick: () -> Unit,
    onRefreshClick: () -> Unit,
    isRefreshing: Boolean,
    isLoading: Boolean,
    cardStyle: CardStyle,
    modifier: Modifier = Modifier,
    favorites: List<FavoriteItem> = emptyList(),
    onItemInfoClick: ((MediaItem) -> Unit)? = null,
    onItemLongClick: ((MediaItem) -> Unit)? = null
) {
    var selectedQuickFilter by remember { mutableStateOf("All") }
    var showHeroInfoDialog by remember { mutableStateOf<MediaItem?>(null) }

    val quickFilters = listOf("All", "Movies", "TV Series", "Live TV", "4K UHD", "Top Rated", "Favorites")
    val listState = rememberLazyListState()

    // Parallax offset derived from first item scroll offset (GPU friendly)
    val heroParallaxOffset by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex == 0) {
                listState.firstVisibleItemScrollOffset.toFloat()
            } else {
                500f
            }
        }
    }

    // Featured hero showcase candidates (top movies + series)
    val heroCandidates = remember(movies, series, liveStreams) {
        (movies.take(5) + series.take(3)).ifEmpty { liveStreams.take(3) }
    }
    var currentHeroIndex by remember { mutableIntStateOf(0) }

    val featuredItem = remember(heroCandidates, currentHeroIndex) {
        if (heroCandidates.isEmpty()) null
        else heroCandidates[
            ((currentHeroIndex % heroCandidates.size) + heroCandidates.size) % heroCandidates.size
        ]
    }

    val continueWatchingItems = remember(watchHistory, movies, series) {
        val allPlayable = movies + series
        watchHistory.mapNotNull { progress ->
            allPlayable.find { it.id == progress.mediaId }
        }
    }

    val favoriteItems = remember(favorites, movies, series, liveStreams) {
        val favIds = favorites.map { it.mediaId }.toSet()
        (movies + series + liveStreams).filter { favIds.contains(it.id) }
    }

    val fourKMovies = remember(movies) {
        movies.filter {
            val q = it.quality.ifBlank { it.resolution }
            q.contains("4K", ignoreCase = true) ||
                q.contains("8K", ignoreCase = true) ||
                it.categoryName.contains("4K", ignoreCase = true)
        }.ifEmpty { movies.take(6) }
    }

    val topRatedMovies = remember(movies) {
        movies.sortedByDescending { it.rating }.take(10)
    }

    val ambientPosterUrl = remember(featuredItem) {
        featuredItem?.backdrop?.ifBlank { featuredItem.streamIcon }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
            .testTag("home_screen")
    ) {
        // Dynamic Blurred Ambient Backdrop extracted from featured poster
        DynamicAmbientPosterBackdrop(
            imageUrl = ambientPosterUrl,
            ambientAlpha = 0.28f
        )

        if (isLoading && movies.isEmpty() && series.isEmpty() && liveStreams.isEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 110.dp)
            ) {
                item {
                    HeroBannerSkeleton(
                        onSearchClick = onSearchClick,
                        onAccountClick = onAccountClick
                    )
                }
                item { MediaRowSkeleton(title = "Trending Blockbusters", cardStyle = cardStyle) }
                item { MediaRowSkeleton(title = "Live TV Channels Now", cardStyle = CardStyle.WIDE) }
                item { MediaRowSkeleton(title = "4K Ultra HD & Dolby Atmos", cardStyle = CardStyle.BILLBOARD) }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 110.dp)
            ) {
                // 1. Full-Screen Cinematic Hero Banner Carousel with Touch Swipe, Infinite Loop, Ken Burns & Parallax
                item(key = "hero_section") {
                    if (heroCandidates.isNotEmpty()) {
                        HeroBannerCarousel(
                            items = heroCandidates,
                            currentIndex = currentHeroIndex,
                            onCurrentIndexChange = { currentHeroIndex = it },
                            onPlayClick = { item -> onMediaClick(item) },
                            onInfoClick = { item ->
                                if (onItemInfoClick != null) {
                                    onItemInfoClick(item)
                                } else {
                                    showHeroInfoDialog = item
                                }
                            },
                            isFavorite = { id -> isFavorite(id) },
                            onToggleFavorite = { item -> onToggleFavorite(item) },
                            onSearchClick = onSearchClick,
                            onAccountClick = onAccountClick,
                            scrollOffset = heroParallaxOffset
                        )
                    }
                }

                // 2. Quick Filter Chips + Refresh Button inside Frosted Liquid Glass Strip
                item(key = "quick_filters") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LazyRow(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(quickFilters, key = { it }) { filter ->
                                val isSelected = filter == selectedQuickFilter
                                LiquidGlassChip(
                                    text = filter,
                                    isSelected = isSelected,
                                    onClick = { selectedQuickFilter = filter },
                                    activeColor = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        // Sync / Refresh Catalog Button
                        Box(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(36.dp)
                                .liquidGlassPanel(
                                    cornerRadius = 18.dp,
                                    surfaceAlpha = 0.65f,
                                    borderAlpha = 0.5f
                                )
                                .clickable(onClick = onRefreshClick)
                                .testTag("home_refresh_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    color = KrasRedBright,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh Catalog",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // 3. Continue Watching Row
                if (continueWatchingItems.isNotEmpty() && selectedQuickFilter == "All") {
                    item(key = "row_continue_watching") {
                        MediaRow(
                            title = "Continue Watching",
                            items = continueWatchingItems,
                            onItemClick = onMediaClick,
                            cardStyle = CardStyle.WIDE,
                            isFavorite = isFavorite,
                            onToggleFavorite = onToggleFavorite,
                            watchHistory = watchHistory,
                            onInfoClick = onItemInfoClick,
                            onLongClick = onItemLongClick
                        )
                    }
                }

                // 4. Favorites Row
                if (favoriteItems.isNotEmpty() && (selectedQuickFilter == "All" || selectedQuickFilter == "Favorites")) {
                    item(key = "row_favorites") {
                        MediaRow(
                            title = "My List & Favorites",
                            items = favoriteItems,
                            onItemClick = onMediaClick,
                            cardStyle = cardStyle,
                            isFavorite = isFavorite,
                            onToggleFavorite = onToggleFavorite,
                            watchHistory = watchHistory,
                            onInfoClick = onItemInfoClick,
                            onLongClick = onItemLongClick
                        )
                    }
                }

                // 5. Live TV Highlights
                if (selectedQuickFilter == "All" || selectedQuickFilter == "Live TV") {
                    item(key = "row_live_tv") {
                        MediaRow(
                            title = "Live TV Channels Now",
                            items = liveStreams.take(15),
                            onItemClick = onMediaClick,
                            cardStyle = CardStyle.WIDE,
                            isFavorite = isFavorite,
                            onToggleFavorite = onToggleFavorite
                        )
                    }
                }

                // 6. 4K UHD Cinema Showcase
                if (selectedQuickFilter == "All" || selectedQuickFilter == "4K UHD" || selectedQuickFilter == "Movies") {
                    item(key = "row_4k_uhd") {
                        MediaRow(
                            title = "4K Ultra HD & Dolby Atmos",
                            items = fourKMovies,
                            onItemClick = onMediaClick,
                            cardStyle = CardStyle.BILLBOARD,
                            isFavorite = isFavorite,
                            onToggleFavorite = onToggleFavorite,
                            watchHistory = watchHistory,
                            onInfoClick = onItemInfoClick,
                            onLongClick = onItemLongClick
                        )
                    }
                }

                // 7. Trending Movies
                if (selectedQuickFilter == "All" || selectedQuickFilter == "Movies") {
                    item(key = "row_trending_movies") {
                        MediaRow(
                            title = "Trending Blockbusters",
                            items = movies,
                            onItemClick = onMediaClick,
                            cardStyle = cardStyle,
                            isFavorite = isFavorite,
                            onToggleFavorite = onToggleFavorite,
                            watchHistory = watchHistory,
                            onInfoClick = onItemInfoClick,
                            onLongClick = onItemLongClick
                        )
                    }
                }

                // 8. Top Rated IMDb
                if (selectedQuickFilter == "All" || selectedQuickFilter == "Top Rated" || selectedQuickFilter == "Movies") {
                    item(key = "row_top_rated") {
                        MediaRow(
                            title = "Critically Acclaimed (IMDb 8.0+)",
                            items = topRatedMovies,
                            onItemClick = onMediaClick,
                            cardStyle = cardStyle,
                            isFavorite = isFavorite,
                            onToggleFavorite = onToggleFavorite,
                            watchHistory = watchHistory,
                            onInfoClick = onItemInfoClick,
                            onLongClick = onItemLongClick
                        )
                    }
                }

                // 9. Binge-Worthy TV Series
                if (selectedQuickFilter == "All" || selectedQuickFilter == "TV Series") {
                    item(key = "row_series") {
                        MediaRow(
                            title = "Binge-Worthy Series",
                            items = series,
                            onItemClick = onMediaClick,
                            cardStyle = cardStyle,
                            isFavorite = isFavorite,
                            onToggleFavorite = onToggleFavorite,
                            onInfoClick = onItemInfoClick,
                            onLongClick = onItemLongClick
                        )
                    }
                }
            }
        }
    }

    // Hero Info Details Liquid Glass Modal
    AnimatedVisibility(
        visible = showHeroInfoDialog != null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        val item = showHeroInfoDialog
        if (item != null) {
            AlertDialog(
                onDismissRequest = { showHeroInfoDialog = null },
                title = {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ResolutionBadge(
                                resolution = item.quality.ifBlank { item.resolution },
                                isLive = false
                            )
                            if (item.rating > 0.0) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = RatingYellow,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = String.format("%.1f", item.rating),
                                        color = RatingYellow,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = item.name,
                            color = Color.White,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp
                        )
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "${item.year.ifEmpty { "2025" }} • ${item.duration.ifEmpty { "Full HD / 4K" }} • ${item.genre.ifEmpty { item.categoryName }}",
                            color = KrasRedBright,
                            fontSize = 12.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = item.plot.ifEmpty { "Stream in crystal clear quality with adaptive multi-bitrate playback." },
                            color = TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showHeroInfoDialog = null
                            onMediaClick(item)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = KrasRed),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Watch Now",
                            color = Color.White,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showHeroInfoDialog = null }) {
                        Text("Close", color = TextSecondary)
                    }
                },
                containerColor = CinemaDark,
                shape = RoundedCornerShape(22.dp)
            )
        }
    }
}
