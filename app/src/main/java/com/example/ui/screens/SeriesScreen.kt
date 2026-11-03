package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.QualityDetector
import com.example.data.model.CardStyle
import com.example.data.model.Category
import com.example.data.model.MediaItem
import com.example.data.model.WatchProgress
import com.example.ui.components.DynamicAmbientPosterBackdrop
import com.example.ui.components.LiquidGlassChip
import com.example.ui.components.MediaCard
import com.example.ui.theme.*

@Composable
fun SeriesScreen(
    seriesList: List<MediaItem>,
    categories: List<Category>,
    isFavorite: (String) -> Boolean,
    onToggleFavorite: (MediaItem) -> Unit,
    onSeriesClick: (MediaItem) -> Unit,
    cardStyle: CardStyle,
    modifier: Modifier = Modifier,
    totalCount: Int = seriesList.size,
    latestSeriesForCategory: List<MediaItem> = emptyList(),
    latestSeriesByCategoryMap: Map<String, List<MediaItem>> = emptyMap(),
    watchHistory: List<WatchProgress> = emptyList(),
    onLoadMore: (() -> Unit)? = null,
    onFilterChanged: ((categoryId: String, query: String, minRating: Double, year: String, qualityFilter: String, sortByQuality: Boolean) -> Unit)? = null
) {
    var selectedCategoryId by rememberSaveable { mutableStateOf("all") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedQualityFilter by rememberSaveable { mutableStateOf("All") }
    var sortByQuality by rememberSaveable { mutableStateOf(false) }
    var showOnlyLatest20 by rememberSaveable { mutableStateOf(false) }
    var showLatest20ViewAllScreen by rememberSaveable { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    val qualityFilterOptions = remember { listOf("All", "4K", "FHD", "HD", "SD") }

    val activeCategoryName = remember(selectedCategoryId, categories) {
        if (selectedCategoryId == "all" || selectedCategoryId == "series_all") {
            "All TV Series"
        } else {
            categories.find { it.categoryId == selectedCategoryId }?.categoryName ?: "Selected Category"
        }
    }

    LaunchedEffect(selectedCategoryId, searchQuery, selectedQualityFilter, sortByQuality) {
        onFilterChanged?.invoke(selectedCategoryId, searchQuery, 0.0, "All", selectedQualityFilter, sortByQuality)
    }

    // Resolve the Latest 20 Series for the active category (sorted by server date_added / last_modified or first-seen timestamp)
    val resolvedLatest20Series = remember(
        latestSeriesForCategory,
        latestSeriesByCategoryMap,
        seriesList,
        selectedCategoryId
    ) {
        val fromMap = latestSeriesByCategoryMap[selectedCategoryId]
        when {
            !fromMap.isNullOrEmpty() -> fromMap.take(20)
            latestSeriesForCategory.isNotEmpty() -> computeFallbackLatest20(latestSeriesForCategory, selectedCategoryId, 20)
            else -> computeFallbackLatest20(seriesList, selectedCategoryId, 20)
        }
    }

    val filteredSeries = remember(
        seriesList,
        resolvedLatest20Series,
        selectedCategoryId,
        searchQuery,
        selectedQualityFilter,
        sortByQuality,
        showOnlyLatest20,
        onFilterChanged
    ) {
        val baseSource = if (showOnlyLatest20) {
            resolvedLatest20Series
        } else if (onFilterChanged != null) {
            seriesList
        } else {
            val filtered = seriesList.filter { s ->
                val matchCat = selectedCategoryId == "all" ||
                        s.categoryId == selectedCategoryId ||
                        s.categoryName.contains(selectedCategoryId, ignoreCase = true)
                val matchSearch = searchQuery.isEmpty() ||
                        s.name.contains(searchQuery, ignoreCase = true) ||
                        s.genre.contains(searchQuery, ignoreCase = true)
                val matchQuality = QualityDetector.matchesQualityFilter(
                    s.quality.ifBlank { s.resolution },
                    selectedQualityFilter
                )
                matchCat && matchSearch && matchQuality
            }
            if (sortByQuality) {
                QualityDetector.sortMediaByQualityDescending(filtered)
            } else {
                filtered
            }
        }

        if (showOnlyLatest20) {
            baseSource.filter { s ->
                val matchSearch = searchQuery.isEmpty() ||
                        s.name.contains(searchQuery, ignoreCase = true) ||
                        s.genre.contains(searchQuery, ignoreCase = true)
                val matchQuality = QualityDetector.matchesQualityFilter(
                    s.quality.ifBlank { s.resolution },
                    selectedQualityFilter
                )
                matchSearch && matchQuality
            }
        } else {
            baseSource
        }
    }

    LaunchedEffect(gridState.canScrollForward, filteredSeries.size, showOnlyLatest20) {
        if (!showOnlyLatest20) {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= filteredSeries.size - 6 && filteredSeries.isNotEmpty()) {
                onLoadMore?.invoke()
            }
        }
    }

    val ambientPosterUrl = remember(resolvedLatest20Series, filteredSeries) {
        (resolvedLatest20Series.firstOrNull() ?: filteredSeries.firstOrNull())?.let {
            it.backdrop.ifBlank { it.streamIcon }
        }
    }

    if (showLatest20ViewAllScreen) {
        BackHandler {
            showLatest20ViewAllScreen = false
        }
        Latest20DedicatedGridScreen(
            title = "Latest 20 Series",
            categoryLabel = activeCategoryName,
            items = resolvedLatest20Series,
            cardStyle = cardStyle,
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onItemClick = onSeriesClick,
            watchHistory = watchHistory,
            onBack = { showLatest20ViewAllScreen = false },
            screenTestTag = "latest_series_grid_screen"
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
            .testTag("series_screen")
    ) {
        DynamicAmbientPosterBackdrop(
            imageUrl = ambientPosterUrl,
            ambientAlpha = 0.22f
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Search bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = "Search TV Series, seasons...",
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
                        .height(50.dp)
                        .testTag("series_search_input")
                )
            }

            // Category Chips
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "cat_all_series") {
                    LiquidGlassChip(
                        text = "All TV Series",
                        isSelected = selectedCategoryId == "all",
                        onClick = { selectedCategoryId = "all" },
                        activeColor = MaterialTheme.colorScheme.primary
                    )
                }

                items(categories.filter { !it.categoryId.equals("series_all", ignoreCase = true) }, key = { it.categoryId }) { cat ->
                    LiquidGlassChip(
                        text = cat.categoryName,
                        isSelected = selectedCategoryId == cat.categoryId,
                        onClick = { selectedCategoryId = cat.categoryId },
                        activeColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("series_category_chip_${cat.categoryId}")
                    )
                }
            }

            // Latest 20 Series Option + Quality Filter & Sort by Quality Chip Row
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("series_quality_filter_row")
            ) {
                item(key = "latest_20_series_chip") {
                    LiquidGlassChip(
                        text = "★ Latest 20 Series",
                        isSelected = showOnlyLatest20,
                        onClick = { showOnlyLatest20 = !showOnlyLatest20 },
                        activeColor = KrasRedBright,
                        modifier = Modifier.testTag("series_latest_20_chip")
                    )
                }

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
                        onClick = { selectedQualityFilter = qOpt },
                        activeColor = activeColor,
                        modifier = Modifier.testTag("series_quality_chip_$qOpt")
                    )
                }

                item(key = "sort_by_quality") {
                    LiquidGlassChip(
                        text = if (sortByQuality) "Sort by quality ↓" else "Sort by quality",
                        isSelected = sortByQuality,
                        onClick = { sortByQuality = !sortByQuality },
                        activeColor = KrasRed,
                        modifier = Modifier.testTag("series_sort_by_quality_chip")
                    )
                }
            }

            // Count + Clear Filters
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (showOnlyLatest20) {
                        "Latest ${filteredSeries.size} Series in $activeCategoryName"
                    } else {
                        "${totalCount.coerceAtLeast(filteredSeries.size)} Shows available"
                    },
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Medium
                )
                if (selectedQualityFilter != "All" || sortByQuality || showOnlyLatest20) {
                    Text(
                        text = "Clear filters",
                        color = KrasRedBright,
                        fontSize = 11.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            selectedQualityFilter = "All"
                            sortByQuality = false
                            showOnlyLatest20 = false
                        }
                    )
                }
            }

            // Grid with Dedicated "Latest 20 Series" Carousel inside every category
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 118.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (resolvedLatest20Series.isNotEmpty() && searchQuery.isEmpty() && !showOnlyLatest20) {
                    item(
                        key = "latest_20_series_carousel_span",
                        span = { GridItemSpan(maxLineSpan) }
                    ) {
                        Latest20CarouselSection(
                            sectionTitle = "Latest 20 Series",
                            categoryLabel = activeCategoryName,
                            items = resolvedLatest20Series,
                            isFavorite = isFavorite,
                            onToggleFavorite = onToggleFavorite,
                            onItemClick = onSeriesClick,
                            watchHistory = watchHistory,
                            onViewAllClick = { showLatest20ViewAllScreen = true },
                            carouselTestTag = "latest_series_carousel",
                            viewAllTestTag = "latest_series_view_all"
                        )
                    }
                }

                items(filteredSeries, key = { it.id }) { s ->
                    MediaCard(
                        item = s,
                        onClick = { onSeriesClick(s) },
                        cardStyle = cardStyle,
                        isFavorite = isFavorite(s.id),
                        onToggleFavorite = { onToggleFavorite(s) }
                    )
                }
            }
        }
    }
}
