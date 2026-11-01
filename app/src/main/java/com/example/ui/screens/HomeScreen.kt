package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import com.example.data.model.*
import com.example.ui.components.HeroBanner
import com.example.ui.components.HeroBannerSkeleton
import com.example.ui.components.MediaCard
import com.example.ui.components.MediaRow
import com.example.ui.components.MediaRowSkeleton
import com.example.ui.theme.CinemaBlack
import com.example.ui.theme.KrasRed

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
    modifier: Modifier = Modifier
) {
    // Pick hero item (first top-rated movie or series)
    val heroItem = remember(movies, series) {
        movies.maxByOrNull { it.rating } ?: series.firstOrNull() ?: movies.firstOrNull()
    }

    // Recently Added (combine movies and series, take 20 latest)
    val recentlyAdded = remember(movies, series) {
        (movies + series).sortedByDescending { it.year }.take(20)
    }

    // Continue Watching items resolved against known media
    val continueWatchingItems = remember(watchHistory, movies, series, liveStreams) {
        val allMedia = (movies + series + liveStreams).associateBy { it.id }
        watchHistory.mapNotNull { hist ->
            allMedia[hist.mediaId]?.let { media ->
                Pair(media, hist)
            }
        }
    }

    var selectedQuickFilter by remember { mutableStateOf("All") }
    val quickFilters = listOf("All", "Movies", "Series", "Live TV")

    val isContentEmpty = movies.isEmpty() && series.isEmpty() && liveStreams.isEmpty()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
            .testTag("home_screen")
    ) {
        // 1. Hero Banner: ALWAYS rendered at top (identical 440.dp layout with statusBarsPadding)
        // If data is loaded, render actual HeroBanner; if loading/empty, render HeroBannerSkeleton.
        item {
            if (heroItem != null) {
                HeroBanner(
                    item = heroItem,
                    onPlayClick = { onMediaClick(heroItem) },
                    onInfoClick = { onMediaClick(heroItem) },
                    isFavorite = isFavorite(heroItem.id),
                    onToggleFavorite = { onToggleFavorite(heroItem) },
                    onSearchClick = onSearchClick,
                    onAccountClick = onAccountClick
                )
            } else {
                HeroBannerSkeleton(
                    onSearchClick = onSearchClick,
                    onAccountClick = onAccountClick
                )
            }
        }

        // 2. Quick Filter Chips & Refresh Button (ALWAYS in normal position below the hero banner)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(quickFilters) { filter ->
                        val isSelected = selectedQuickFilter == filter
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else Color.White.copy(alpha = 0.1f)
                                )
                                .clickable { selectedQuickFilter = filter }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = filter,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onRefreshClick,
                    modifier = Modifier.testTag("home_refresh_button")
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh from Server",
                            tint = Color.White
                        )
                    }
                }
            }
        }

        // 3. Continue Watching (if history available)
        if (continueWatchingItems.isNotEmpty() && (selectedQuickFilter == "All" || selectedQuickFilter == "Movies" || selectedQuickFilter == "Series")) {
            item {
                MediaRow(
                    title = "Continue Watching",
                    items = continueWatchingItems.map { it.first },
                    onItemClick = onMediaClick,
                    cardStyle = CardStyle.WIDE,
                    onToggleFavorite = onToggleFavorite,
                    isFavorite = isFavorite,
                    watchHistory = watchHistory
                )
            }
        }

        // 4. Rows or Loading Skeletons
        if (isContentEmpty && isLoading) {
            // Render shimmering skeleton rows while loading
            item {
                MediaRowSkeleton(title = "Recently Added", cardStyle = cardStyle)
            }
            item {
                MediaRowSkeleton(title = "Trending Movies", cardStyle = cardStyle)
            }
            item {
                MediaRowSkeleton(title = "Popular TV Series", cardStyle = cardStyle)
            }
            item {
                MediaRowSkeleton(title = "Top Live Channels", cardStyle = CardStyle.WIDE)
            }
        } else {
            // Recently Added (20 Latest Items)
            if (recentlyAdded.isNotEmpty() && selectedQuickFilter == "All") {
                item {
                    MediaRow(
                        title = "Recently Added",
                        items = recentlyAdded,
                        onItemClick = onMediaClick,
                        cardStyle = cardStyle,
                        onToggleFavorite = onToggleFavorite,
                        isFavorite = isFavorite,
                        watchHistory = watchHistory
                    )
                }
            }

            // Trending Movies
            if (movies.isNotEmpty() && (selectedQuickFilter == "All" || selectedQuickFilter == "Movies")) {
                item {
                    MediaRow(
                        title = "Trending Movies",
                        items = movies.sortedByDescending { it.rating },
                        onItemClick = onMediaClick,
                        cardStyle = cardStyle,
                        onToggleFavorite = onToggleFavorite,
                        isFavorite = isFavorite,
                        watchHistory = watchHistory
                    )
                }
            }

            // Popular TV Series
            if (series.isNotEmpty() && (selectedQuickFilter == "All" || selectedQuickFilter == "Series")) {
                item {
                    MediaRow(
                        title = "Popular TV Series",
                        items = series,
                        onItemClick = onMediaClick,
                        cardStyle = cardStyle,
                        onToggleFavorite = onToggleFavorite,
                        isFavorite = isFavorite,
                        watchHistory = watchHistory
                    )
                }
            }

            // Top Live Channels
            if (liveStreams.isNotEmpty() && (selectedQuickFilter == "All" || selectedQuickFilter == "Live TV")) {
                item {
                    MediaRow(
                        title = "Top Live Channels",
                        items = liveStreams,
                        onItemClick = onMediaClick,
                        cardStyle = CardStyle.WIDE,
                        onToggleFavorite = onToggleFavorite,
                        isFavorite = isFavorite
                    )
                }
            }
        }

        // Spacer for bottom navigation bar padding
        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}
