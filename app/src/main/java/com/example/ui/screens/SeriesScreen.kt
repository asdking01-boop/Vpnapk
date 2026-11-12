package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.QualityDetector
import com.example.data.model.CardStyle
import com.example.data.model.Category
import com.example.data.model.MediaItem
import com.example.data.model.MediaSortOrder
import com.example.data.model.WatchProgress
import com.example.ui.components.DynamicAmbientPosterBackdrop
import com.example.ui.components.LiquidGlassChip
import com.example.ui.components.MediaCard
import com.example.ui.components.liquidGlassPanel
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
    availableYears: List<String> = emptyList(),
    watchHistory: List<WatchProgress> = emptyList(),
    onLoadMore: (() -> Unit)? = null,
    onFilterChanged: ((categoryId: String, query: String, minRating: Double, year: String, qualityFilter: String, sortByQuality: Boolean, sortOrder: MediaSortOrder) -> Unit)? = null
) {
    var selectedCategoryId by rememberSaveable { mutableStateOf("all") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var minRating by rememberSaveable { mutableStateOf(0.0) }
    var selectedYear by rememberSaveable { mutableStateOf("All") }
    var selectedQualityFilter by rememberSaveable { mutableStateOf("All") }
    var sortByQuality by rememberSaveable { mutableStateOf(false) }
    var selectedSortOrderName by rememberSaveable { mutableStateOf(MediaSortOrder.RECENTLY_ADDED.name) }
    val selectedSortOrder = remember(selectedSortOrderName) {
        try {
            MediaSortOrder.valueOf(selectedSortOrderName)
        } catch (_: Exception) {
            MediaSortOrder.RECENTLY_ADDED
        }
    }
    var showOnlyLatest20 by rememberSaveable { mutableStateOf(false) }
    var showLatest20ViewAllScreen by rememberSaveable { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    val gridState = rememberLazyGridState()
    val qualityFilterOptions = remember { listOf("All", "4K", "FHD", "HD", "SD") }
    val configuration = LocalConfiguration.current
    val isTvOrLargeScreen = configuration.screenWidthDp >= 720

    val activeCategoryName = remember(selectedCategoryId, categories) {
        if (selectedCategoryId == "all" || selectedCategoryId == "series_all") {
            "All TV Series"
        } else {
            categories.find { it.categoryId == selectedCategoryId }?.categoryName ?: "Selected Category"
        }
    }

    LaunchedEffect(
        selectedCategoryId,
        searchQuery,
        minRating,
        selectedYear,
        selectedQualityFilter,
        sortByQuality,
        selectedSortOrder
    ) {
        onFilterChanged?.invoke(
            selectedCategoryId,
            searchQuery,
            minRating,
            selectedYear,
            selectedQualityFilter,
            sortByQuality,
            selectedSortOrder
        )
    }

    // Resolve the Recently Added (Latest 20) Series for the active category (sorted by server date_added, newest first)
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

    // Dynamic available release years for the active category (latest available years first)
    val effectiveAvailableYears = remember(
        availableYears,
        seriesList,
        resolvedLatest20Series,
        selectedCategoryId
    ) {
        computeAvailableYears(
            providedYears = availableYears,
            items = seriesList,
            latestItems = resolvedLatest20Series,
            selectedCategoryId = selectedCategoryId
        )
    }

    val filteredSeries = remember(
        seriesList,
        resolvedLatest20Series,
        selectedCategoryId,
        searchQuery,
        minRating,
        selectedYear,
        selectedQualityFilter,
        sortByQuality,
        selectedSortOrder,
        showOnlyLatest20,
        onFilterChanged
    ) {
        val baseSource = if (showOnlyLatest20) {
            resolvedLatest20Series
        } else if (onFilterChanged != null) {
            applyMediaSortOrder(seriesList, selectedSortOrder, sortByQuality)
        } else {
            val filtered = seriesList.filter { s ->
                val matchCat = selectedCategoryId == "all" ||
                        selectedCategoryId == "series_all" ||
                        s.categoryId == selectedCategoryId ||
                        s.categoryName.contains(selectedCategoryId, ignoreCase = true)
                val matchSearch = searchQuery.isEmpty() ||
                        s.name.contains(searchQuery, ignoreCase = true) ||
                        s.genre.contains(searchQuery, ignoreCase = true)
                val matchRating = s.rating >= minRating
                val matchYear = selectedYear == "All" || s.year == selectedYear
                val matchQuality = QualityDetector.matchesQualityFilter(
                    s.quality.ifBlank { s.resolution },
                    selectedQualityFilter
                )
                matchCat && matchSearch && matchRating && matchYear && matchQuality
            }
            applyMediaSortOrder(filtered, selectedSortOrder, sortByQuality)
        }

        if (showOnlyLatest20) {
            val filteredLatest = baseSource.filter { s ->
                val matchSearch = searchQuery.isEmpty() ||
                        s.name.contains(searchQuery, ignoreCase = true) ||
                        s.genre.contains(searchQuery, ignoreCase = true)
                val matchRating = s.rating >= minRating
                val matchYear = selectedYear == "All" || s.year == selectedYear
                val matchQuality = QualityDetector.matchesQualityFilter(
                    s.quality.ifBlank { s.resolution },
                    selectedQualityFilter
                )
                matchSearch && matchRating && matchYear && matchQuality
            }
            applyMediaSortOrder(filteredLatest, selectedSortOrder, sortByQuality)
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
            title = "Recently Added Series",
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
            // Search bar + Filter & Sort button
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
                        .weight(1f)
                        .height(50.dp)
                        .testTag("series_search_input")
                )

                Spacer(modifier = Modifier.width(10.dp))

                val hasActiveExtraFilter = minRating > 0.0 ||
                        selectedYear != "All" ||
                        selectedQualityFilter != "All" ||
                        sortByQuality ||
                        selectedSortOrder != MediaSortOrder.RECENTLY_ADDED ||
                        showOnlyLatest20
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .liquidGlassPanel(
                            cornerRadius = 16.dp,
                            tintColor = if (hasActiveExtraFilter) KrasRed else Color(0xFF141824),
                            surfaceAlpha = if (hasActiveExtraFilter) 0.85f else 0.65f,
                            borderAlpha = 0.55f
                        )
                        .clickable { showFilterDialog = true }
                        .testTag("series_filter_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = "Filter & Sort",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
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

            // Sort Selector + Release Year Filter Row (Latest available years first, combinable with sorting)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("series_year_filter_row")
            ) {
                item(key = "series_sort_selector") {
                    Box {
                        LiquidGlassChip(
                            text = "Sort: ${selectedSortOrder.shortLabel} ▾",
                            isSelected = selectedSortOrder != MediaSortOrder.RECENTLY_ADDED,
                            onClick = { showSortMenu = true },
                            activeColor = KrasRed,
                            modifier = Modifier.testTag("series_sort_order_chip")
                        )
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                            modifier = Modifier.background(CinemaDark)
                        ) {
                            MediaSortOrder.entries.forEach { orderOption ->
                                val isCurrent = selectedSortOrder == orderOption
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            if (isCurrent) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = KrasRedBright,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            Text(
                                                text = orderOption.label,
                                                color = if (isCurrent) KrasRedBright else Color.White,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 13.sp
                                            )
                                        }
                                    },
                                    onClick = {
                                        selectedSortOrderName = orderOption.name
                                        showSortMenu = false
                                    },
                                    modifier = Modifier.testTag("series_sort_option_${orderOption.name}")
                                )
                            }
                        }
                    }
                }

                item(key = "year_all_series") {
                    LiquidGlassChip(
                        text = "All Years",
                        isSelected = selectedYear == "All",
                        onClick = { selectedYear = "All" },
                        activeColor = KrasRed,
                        modifier = Modifier.testTag("series_year_chip_All")
                    )
                }

                items(effectiveAvailableYears, key = { "yr_$it" }) { yr ->
                    LiquidGlassChip(
                        text = yr,
                        isSelected = selectedYear == yr,
                        onClick = { selectedYear = if (selectedYear == yr) "All" else yr },
                        activeColor = KrasRedBright,
                        modifier = Modifier.testTag("series_year_chip_$yr")
                    )
                }
            }

            // Recently Added 20 Quick Filter + Quality Filter & Sort by Quality Chip Row
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("series_quality_filter_row")
            ) {
                item(key = "latest_20_series_chip") {
                    LiquidGlassChip(
                        text = "★ Recently Added (20)",
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
                        text = if (sortByQuality) "Quality Priority ↓" else "Quality Priority",
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
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val yearSuffix = if (selectedYear != "All") " • Year: $selectedYear" else ""
                Text(
                    text = if (showOnlyLatest20) {
                        "Recently Added (${filteredSeries.size}) in $activeCategoryName$yearSuffix"
                    } else {
                        "${totalCount.coerceAtLeast(filteredSeries.size)} Shows in $activeCategoryName$yearSuffix • ${selectedSortOrder.label}"
                    },
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (minRating > 0.0 ||
                    selectedYear != "All" ||
                    selectedQualityFilter != "All" ||
                    sortByQuality ||
                    selectedSortOrder != MediaSortOrder.RECENTLY_ADDED ||
                    showOnlyLatest20
                ) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Reset",
                        color = KrasRedBright,
                        fontSize = 11.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                minRating = 0.0
                                selectedYear = "All"
                                selectedQualityFilter = "All"
                                sortByQuality = false
                                selectedSortOrderName = MediaSortOrder.RECENTLY_ADDED.name
                                showOnlyLatest20 = false
                            }
                            .testTag("series_clear_filters_button")
                    )
                }
            }

            val minGridColWidth = when {
                cardStyle == CardStyle.WIDE -> if (isTvOrLargeScreen) 210.dp else 180.dp
                isTvOrLargeScreen -> 142.dp
                else -> 118.dp
            }

            // Grid with Dedicated "Recently Added" (Latest 20 Series) Carousel at the Top + Full Category Library Below
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = minGridColWidth),
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
                            sectionTitle = "Recently Added",
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

                    item(
                        key = "complete_series_library_header_span",
                        span = { GridItemSpan(maxLineSpan) }
                    ) {
                        CompleteCategoryLibraryHeader(
                            categoryLabel = activeCategoryName,
                            sortLabel = selectedSortOrder.label,
                            selectedYear = selectedYear
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

    // Filter & Sort Dialog for Series
    if (showFilterDialog) {
        AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            title = {
                Text(
                    text = "Sort & Filter TV Series",
                    color = Color.White,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Sort Order:", color = TextSecondary, fontSize = 12.sp)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MediaSortOrder.entries.forEach { orderOpt ->
                            val isSel = selectedSortOrder == orderOpt
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSel) KrasRed else CinemaCard)
                                    .border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
                                    .clickable { selectedSortOrderName = orderOpt.name }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = orderOpt.label,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                                )
                                if (isSel) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }

                    Text("Release Year (Latest Available First):", color = TextSecondary, fontSize = 12.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val yearDialogOptions = listOf("All") + effectiveAvailableYears.ifEmpty {
                            listOf("2025", "2024", "2023", "2022")
                        }
                        items(yearDialogOptions, key = { "dlg_ser_yr_$it" }) { yr ->
                            val isSel = selectedYear == yr
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSel) KrasRed else CinemaCard)
                                    .border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
                                    .clickable { selectedYear = yr }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (yr == "All") "All Years" else yr,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Text("Minimum Rating:", color = TextSecondary, fontSize = 12.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(0.0 to "All", 7.0 to "★ 7+", 8.0 to "★ 8+", 9.0 to "★ 9+").forEach { (rating, label) ->
                            val isSel = minRating == rating
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSel) KrasRed else CinemaCard)
                                    .border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
                                    .clickable { minRating = rating }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showFilterDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = KrasRed)
                ) {
                    Text("Apply", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    minRating = 0.0
                    selectedYear = "All"
                    selectedQualityFilter = "All"
                    sortByQuality = false
                    selectedSortOrderName = MediaSortOrder.RECENTLY_ADDED.name
                    showFilterDialog = false
                }) {
                    Text("Reset", color = TextSecondary)
                }
            },
            containerColor = CinemaDark
        )
    }
}
