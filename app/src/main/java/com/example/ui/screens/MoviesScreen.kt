package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Formats the server addition timestamp badge without inventing dates when metadata is missing.
 */
internal fun formatAddedDateBadge(timestampMs: Long): String {
    if (timestampMs <= 0L) {
        return "Recently Added"
    }
    val dateStr = try {
        SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(timestampMs))
    } catch (_: Exception) {
        ""
    }
    return if (dateStr.isNotEmpty()) "Added • $dateStr" else "Recently Added"
}

/**
 * Computes the Latest 20 items for a category sorted strictly by actual server addition timestamp
 * (newest first) and deduplicated by id, streamId, and normalized category+title.
 */
internal fun computeFallbackLatest20(
    items: List<MediaItem>,
    categoryId: String,
    limit: Int = 20
): List<MediaItem> {
    val categoryFiltered = items.filter { item ->
        categoryId == "all" ||
                categoryId == "vod_all" ||
                categoryId == "series_all" ||
                item.categoryId == categoryId ||
                item.categoryName.contains(categoryId, ignoreCase = true)
    }
    val sorted = categoryFiltered.sortedWith(
        compareByDescending<MediaItem> { it.addedTimestamp }
            .thenByDescending { it.streamId }
            .thenBy { it.name.lowercase() }
    )
    val seenIds = HashSet<String>()
    val seenStreamIds = HashSet<Int>()
    val seenTitles = HashSet<String>()
    val result = ArrayList<MediaItem>(limit)
    for (item in sorted) {
        if (!seenIds.add(item.id)) continue
        if (item.streamId > 0 && !seenStreamIds.add(item.streamId)) continue
        val titleKey = "${item.categoryId}_${item.name.trim().lowercase()}"
        if (!seenTitles.add(titleKey)) continue
        result.add(item)
        if (result.size >= limit) break
    }
    return result
}

/**
 * Extracts distinct valid 4-digit release years from items, sorted with the latest available years first.
 */
internal fun computeAvailableYears(
    providedYears: List<String>,
    items: List<MediaItem>,
    latestItems: List<MediaItem>,
    selectedCategoryId: String
): List<String> {
    val fromItems = (items + latestItems)
        .filter { item ->
            selectedCategoryId == "all" ||
                    selectedCategoryId == "vod_all" ||
                    selectedCategoryId == "series_all" ||
                    item.categoryId == selectedCategoryId ||
                    item.categoryName.contains(selectedCategoryId, ignoreCase = true)
        }
        .map { it.year.trim() }
    return (providedYears + fromItems)
        .mapNotNull { it.toIntOrNull() }
        .filter { it in 1900..2100 }
        .distinct()
        .sortedDescending()
        .map { it.toString() }
}

/**
 * Applies the selected [MediaSortOrder] (and optional quality priority) to a list of [MediaItem]s.
 * Never confuses release year (`year`) with date added (`addedTimestamp`).
 */
internal fun applyMediaSortOrder(
    items: List<MediaItem>,
    sortOrder: MediaSortOrder,
    sortByQuality: Boolean = false
): List<MediaItem> {
    val comparator = when (sortOrder) {
        MediaSortOrder.RECENTLY_ADDED -> compareByDescending<MediaItem> { it.addedTimestamp }
            .thenByDescending { it.streamId }
            .thenBy { it.name.trim().lowercase() }

        MediaSortOrder.YEAR_NEWEST -> compareBy<MediaItem> {
            val yr = it.year.trim().toIntOrNull() ?: 0
            if (yr in 1900..2100) 0 else 1
        }
            .thenByDescending { it.year.trim().toIntOrNull() ?: 0 }
            .thenByDescending { it.addedTimestamp }
            .thenBy { it.name.trim().lowercase() }

        MediaSortOrder.YEAR_OLDEST -> compareBy<MediaItem> {
            val yr = it.year.trim().toIntOrNull() ?: 0
            if (yr in 1900..2100) 0 else 1
        }
            .thenBy { it.year.trim().toIntOrNull() ?: Int.MAX_VALUE }
            .thenByDescending { it.addedTimestamp }
            .thenBy { it.name.trim().lowercase() }

        MediaSortOrder.TITLE_AZ -> compareBy<MediaItem> { it.name.trim().lowercase() }
            .thenByDescending { it.addedTimestamp }

        MediaSortOrder.TITLE_ZA -> compareByDescending<MediaItem> { it.name.trim().lowercase() }
            .thenByDescending { it.addedTimestamp }
    }

    val sorted = items.sortedWith(comparator)
    return if (sortByQuality) {
        QualityDetector.sortMediaByQualityDescending(sorted)
    } else {
        sorted
    }
}

@Composable
fun MoviesScreen(
    movies: List<MediaItem>,
    categories: List<Category>,
    watchHistory: List<WatchProgress>,
    isFavorite: (String) -> Boolean,
    onToggleFavorite: (MediaItem) -> Unit,
    onMovieClick: (MediaItem) -> Unit,
    cardStyle: CardStyle,
    modifier: Modifier = Modifier,
    totalCount: Int = movies.size,
    latestMoviesForCategory: List<MediaItem> = emptyList(),
    latestMoviesByCategoryMap: Map<String, List<MediaItem>> = emptyMap(),
    availableYears: List<String> = emptyList(),
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

    val qualityFilterOptions = remember { listOf("All", "4K", "FHD", "HD", "SD") }
    val configuration = LocalConfiguration.current
    val isTvOrLargeScreen = configuration.screenWidthDp >= 720

    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()

    val activeCategoryName = remember(selectedCategoryId, categories) {
        if (selectedCategoryId == "all" || selectedCategoryId == "vod_all") {
            "All Movies"
        } else {
            categories.find { it.categoryId == selectedCategoryId }?.categoryName ?: "Selected Category"
        }
    }

    // Trigger filter update when controls change
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

    // Resolve the Recently Added (Latest 20) Movies for the active category (sorted by server date_added, newest first)
    val resolvedLatest20Movies = remember(
        latestMoviesForCategory,
        latestMoviesByCategoryMap,
        movies,
        selectedCategoryId
    ) {
        val fromMap = latestMoviesByCategoryMap[selectedCategoryId]
        when {
            !fromMap.isNullOrEmpty() -> fromMap.take(20)
            latestMoviesForCategory.isNotEmpty() -> computeFallbackLatest20(latestMoviesForCategory, selectedCategoryId, 20)
            else -> computeFallbackLatest20(movies, selectedCategoryId, 20)
        }
    }

    // Dynamic available release years for the active category (latest available years first)
    val effectiveAvailableYears = remember(
        availableYears,
        movies,
        resolvedLatest20Movies,
        selectedCategoryId
    ) {
        computeAvailableYears(
            providedYears = availableYears,
            items = movies,
            latestItems = resolvedLatest20Movies,
            selectedCategoryId = selectedCategoryId
        )
    }

    val filteredMovies = remember(
        movies,
        resolvedLatest20Movies,
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
            resolvedLatest20Movies
        } else if (onFilterChanged != null) {
            applyMediaSortOrder(movies, selectedSortOrder, sortByQuality)
        } else {
            val filtered = movies.filter { movie ->
                val matchCategory = selectedCategoryId == "all" ||
                        selectedCategoryId == "vod_all" ||
                        movie.categoryId == selectedCategoryId ||
                        movie.categoryName.contains(selectedCategoryId, ignoreCase = true)
                val matchSearch = searchQuery.isEmpty() ||
                        movie.name.contains(searchQuery, ignoreCase = true) ||
                        movie.genre.contains(searchQuery, ignoreCase = true)
                val matchRating = movie.rating >= minRating
                val matchYear = selectedYear == "All" || movie.year == selectedYear
                val matchQuality = QualityDetector.matchesQualityFilter(
                    movie.quality.ifBlank { movie.resolution },
                    selectedQualityFilter
                )
                matchCategory && matchSearch && matchRating && matchYear && matchQuality
            }
            applyMediaSortOrder(filtered, selectedSortOrder, sortByQuality)
        }

        if (showOnlyLatest20) {
            val filteredLatest = baseSource.filter { movie ->
                val matchSearch = searchQuery.isEmpty() ||
                        movie.name.contains(searchQuery, ignoreCase = true) ||
                        movie.genre.contains(searchQuery, ignoreCase = true)
                val matchRating = movie.rating >= minRating
                val matchYear = selectedYear == "All" || movie.year == selectedYear
                val matchQuality = QualityDetector.matchesQualityFilter(
                    movie.quality.ifBlank { movie.resolution },
                    selectedQualityFilter
                )
                matchSearch && matchRating && matchYear && matchQuality
            }
            applyMediaSortOrder(filteredLatest, selectedSortOrder, sortByQuality)
        } else {
            baseSource
        }
    }

    // Scroll pagination triggers
    LaunchedEffect(gridState.canScrollForward, filteredMovies.size, showOnlyLatest20) {
        if (!showOnlyLatest20) {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= filteredMovies.size - 6 && filteredMovies.isNotEmpty()) {
                onLoadMore?.invoke()
            }
        }
    }

    LaunchedEffect(listState.canScrollForward, filteredMovies.size, showOnlyLatest20) {
        if (!showOnlyLatest20) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= filteredMovies.size - 6 && filteredMovies.isNotEmpty()) {
                onLoadMore?.invoke()
            }
        }
    }

    val ambientPosterUrl = remember(resolvedLatest20Movies, filteredMovies) {
        (resolvedLatest20Movies.firstOrNull() ?: filteredMovies.firstOrNull())?.let {
            it.backdrop.ifBlank { it.streamIcon }
        }
    }

    if (showLatest20ViewAllScreen) {
        BackHandler {
            showLatest20ViewAllScreen = false
        }
        Latest20DedicatedGridScreen(
            title = "Recently Added Movies",
            categoryLabel = activeCategoryName,
            items = resolvedLatest20Movies,
            cardStyle = cardStyle,
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onItemClick = onMovieClick,
            watchHistory = watchHistory,
            onBack = { showLatest20ViewAllScreen = false },
            screenTestTag = "latest_movies_grid_screen"
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
            .testTag("movies_screen")
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
            // Top Header with Liquid Glass Search and Filter
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
                            text = "Search movies, genres...",
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
                        .testTag("movies_search_input")
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
                        .testTag("movies_filter_button"),
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

            // Category Chips Row
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "cat_all_movies") {
                    LiquidGlassChip(
                        text = "All Movies",
                        isSelected = selectedCategoryId == "all",
                        onClick = { selectedCategoryId = "all" },
                        activeColor = MaterialTheme.colorScheme.primary
                    )
                }

                items(categories.filter { !it.categoryId.equals("vod_all", ignoreCase = true) }, key = { it.categoryId }) { cat ->
                    LiquidGlassChip(
                        text = cat.categoryName,
                        isSelected = selectedCategoryId == cat.categoryId,
                        onClick = { selectedCategoryId = cat.categoryId },
                        activeColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("movie_category_chip_${cat.categoryId}")
                    )
                }
            }

            // Sort Selector + Release Year Filter Row (Latest available years first, combinable with sorting)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("movies_year_filter_row")
            ) {
                item(key = "movies_sort_selector") {
                    Box {
                        LiquidGlassChip(
                            text = "Sort: ${selectedSortOrder.shortLabel} ▾",
                            isSelected = selectedSortOrder != MediaSortOrder.RECENTLY_ADDED,
                            onClick = { showSortMenu = true },
                            activeColor = KrasRed,
                            modifier = Modifier.testTag("movies_sort_order_chip")
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
                                    modifier = Modifier.testTag("movies_sort_option_${orderOption.name}")
                                )
                            }
                        }
                    }
                }

                item(key = "year_all_movies") {
                    LiquidGlassChip(
                        text = "All Years",
                        isSelected = selectedYear == "All",
                        onClick = { selectedYear = "All" },
                        activeColor = KrasRed,
                        modifier = Modifier.testTag("movies_year_chip_All")
                    )
                }

                items(effectiveAvailableYears, key = { "yr_$it" }) { yr ->
                    LiquidGlassChip(
                        text = yr,
                        isSelected = selectedYear == yr,
                        onClick = { selectedYear = if (selectedYear == yr) "All" else yr },
                        activeColor = KrasRedBright,
                        modifier = Modifier.testTag("movies_year_chip_$yr")
                    )
                }
            }

            // Recently Added 20 Quick Filter + Quality Filter & Sort by Quality Chip Row
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("movies_quality_filter_row")
            ) {
                item(key = "latest_20_movies_chip") {
                    LiquidGlassChip(
                        text = "★ Recently Added (20)",
                        isSelected = showOnlyLatest20,
                        onClick = { showOnlyLatest20 = !showOnlyLatest20 },
                        activeColor = KrasRedBright,
                        modifier = Modifier.testTag("movies_latest_20_chip")
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
                        modifier = Modifier.testTag("movies_quality_chip_$qOpt")
                    )
                }

                item(key = "sort_by_quality") {
                    LiquidGlassChip(
                        text = if (sortByQuality) "Quality Priority ↓" else "Quality Priority",
                        isSelected = sortByQuality,
                        onClick = { sortByQuality = !sortByQuality },
                        activeColor = KrasRed,
                        modifier = Modifier.testTag("movies_sort_by_quality_chip")
                    )
                }
            }

            // Results Count & Active Sort/Filter Summary
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
                        "Recently Added (${filteredMovies.size}) in $activeCategoryName$yearSuffix"
                    } else {
                        "${totalCount.coerceAtLeast(filteredMovies.size)} Movies in $activeCategoryName$yearSuffix • ${selectedSortOrder.label}"
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
                            .testTag("movies_clear_filters_button")
                    )
                }
            }

            // Movies Content: "Recently Added" (Latest 20) Section at the Top of EVERY Category + Full Category Library Below
            if (filteredMovies.isEmpty() && resolvedLatest20Movies.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .liquidGlassPanel(cornerRadius = 20.dp)
                            .padding(horizontal = 24.dp, vertical = 20.dp)
                    ) {
                        Text(
                            text = "No movies match your filter",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Try clearing your search, release year, or category filter",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                val minGridColWidth = when {
                    cardStyle == CardStyle.WIDE -> if (isTvOrLargeScreen) 210.dp else 180.dp
                    isTvOrLargeScreen -> 142.dp
                    else -> 118.dp
                }

                if (cardStyle == CardStyle.COMPACT) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (resolvedLatest20Movies.isNotEmpty() && searchQuery.isEmpty() && !showOnlyLatest20) {
                            item(key = "latest_20_movies_carousel_header") {
                                Latest20CarouselSection(
                                    sectionTitle = "Recently Added",
                                    categoryLabel = activeCategoryName,
                                    items = resolvedLatest20Movies,
                                    isFavorite = isFavorite,
                                    onToggleFavorite = onToggleFavorite,
                                    onItemClick = onMovieClick,
                                    watchHistory = watchHistory,
                                    onViewAllClick = { showLatest20ViewAllScreen = true },
                                    carouselTestTag = "latest_movies_carousel",
                                    viewAllTestTag = "latest_movies_view_all"
                                )
                            }

                            item(key = "complete_library_header_compact") {
                                CompleteCategoryLibraryHeader(
                                    categoryLabel = activeCategoryName,
                                    sortLabel = selectedSortOrder.label,
                                    selectedYear = selectedYear
                                )
                            }
                        }

                        items(filteredMovies, key = { it.id }) { movie ->
                            MediaCard(
                                item = movie,
                                onClick = { onMovieClick(movie) },
                                cardStyle = CardStyle.COMPACT,
                                isFavorite = isFavorite(movie.id),
                                onToggleFavorite = { onToggleFavorite(movie) },
                                watchProgress = watchHistory.find { it.mediaId == movie.id }
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = minGridColWidth),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (resolvedLatest20Movies.isNotEmpty() && searchQuery.isEmpty() && !showOnlyLatest20) {
                            item(
                                key = "latest_20_movies_carousel_span",
                                span = { GridItemSpan(maxLineSpan) }
                            ) {
                                Latest20CarouselSection(
                                    sectionTitle = "Recently Added",
                                    categoryLabel = activeCategoryName,
                                    items = resolvedLatest20Movies,
                                    isFavorite = isFavorite,
                                    onToggleFavorite = onToggleFavorite,
                                    onItemClick = onMovieClick,
                                    watchHistory = watchHistory,
                                    onViewAllClick = { showLatest20ViewAllScreen = true },
                                    carouselTestTag = "latest_movies_carousel",
                                    viewAllTestTag = "latest_movies_view_all"
                                )
                            }

                            item(
                                key = "complete_library_header_span",
                                span = { GridItemSpan(maxLineSpan) }
                            ) {
                                CompleteCategoryLibraryHeader(
                                    categoryLabel = activeCategoryName,
                                    sortLabel = selectedSortOrder.label,
                                    selectedYear = selectedYear
                                )
                            }
                        }

                        items(filteredMovies, key = { it.id }) { movie ->
                            MediaCard(
                                item = movie,
                                onClick = { onMovieClick(movie) },
                                cardStyle = cardStyle,
                                isFavorite = isFavorite(movie.id),
                                onToggleFavorite = { onToggleFavorite(movie) },
                                watchProgress = watchHistory.find { it.mediaId == movie.id }
                            )
                        }
                    }
                }
            }
        }
    }

    // Filter & Sort Dialog
    if (showFilterDialog) {
        AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            title = {
                Text(
                    text = "Sort & Filter Movies",
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
                        items(yearDialogOptions, key = { "dlg_yr_$it" }) { yr ->
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

@Composable
internal fun CompleteCategoryLibraryHeader(
    categoryLabel: String,
    sortLabel: String,
    selectedYear: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (selectedYear == "All") {
                "Complete Library • $categoryLabel"
            } else {
                "Complete Library • $categoryLabel ($selectedYear)"
            },
            color = Color.White,
            fontSize = 14.sp,
            fontFamily = OutfitFontFamily,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = sortLabel,
            color = KrasRedBright,
            fontSize = 11.sp,
            fontFamily = OutfitFontFamily,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
internal fun Latest20CarouselSection(
    sectionTitle: String,
    categoryLabel: String,
    items: List<MediaItem>,
    isFavorite: (String) -> Boolean,
    onToggleFavorite: (MediaItem) -> Unit,
    onItemClick: (MediaItem) -> Unit,
    watchHistory: List<WatchProgress>,
    onViewAllClick: () -> Unit,
    carouselTestTag: String,
    viewAllTestTag: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .liquidGlassPanel(
                cornerRadius = 18.dp,
                surfaceAlpha = 0.55f,
                borderAlpha = 0.38f,
                accentGlow = KrasRed
            )
            .padding(vertical = 12.dp)
            .testTag(carouselTestTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = KrasRedBright,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "$sectionTitle in $categoryLabel",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(KrasRed.copy(alpha = 0.25f))
                            .border(0.8.dp, KrasRedBright.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "LATEST ${items.size}",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Latest ${items.size} added to $categoryLabel • Newest additions first",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(KrasRed.copy(alpha = 0.2f))
                    .border(1.dp, KrasRedBright.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .clickable(onClick = onViewAllClick)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag(viewAllTestTag),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "View All",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "View All",
                    tint = KrasRedBright,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(items, key = { _, item -> "latest_${item.id}" }) { index, item ->
                Latest20PosterItem(
                    rank = index + 1,
                    item = item,
                    isFavorite = isFavorite(item.id),
                    onToggleFavorite = { onToggleFavorite(item) },
                    watchProgress = watchHistory.find { it.mediaId == item.id },
                    onClick = { onItemClick(item) }
                )
            }
        }
    }
}

@Composable
internal fun Latest20PosterItem(
    rank: Int,
    item: MediaItem,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    watchProgress: WatchProgress?,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(135.dp)
            .testTag("latest_20_item_${item.id}")
    ) {
        Box {
            MediaCard(
                item = item,
                onClick = onClick,
                cardStyle = CardStyle.POSTER,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
                watchProgress = watchProgress
            )

            // Rank Badge
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 34.dp, start = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(KrasRed, Color(0xCC12141E))
                        )
                    )
                    .border(0.6.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "#$rank",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                tint = KrasRedBright,
                modifier = Modifier.size(11.dp)
            )
            Text(
                text = formatAddedDateBadge(item.addedTimestamp),
                color = TextSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun Latest20DedicatedGridScreen(
    title: String,
    categoryLabel: String,
    items: List<MediaItem>,
    cardStyle: CardStyle,
    isFavorite: (String) -> Boolean,
    onToggleFavorite: (MediaItem) -> Unit,
    onItemClick: (MediaItem) -> Unit,
    watchHistory: List<WatchProgress>,
    onBack: () -> Unit,
    screenTestTag: String
) {
    val ambientArt = remember(items) {
        items.firstOrNull()?.let { it.backdrop.ifBlank { it.streamIcon } }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CinemaBlack)
            .testTag(screenTestTag)
    ) {
        DynamicAmbientPosterBackdrop(
            imageUrl = ambientArt,
            ambientAlpha = 0.24f
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0x99141824))
                        .border(1.dp, GlassBorder, CircleShape)
                        .testTag("latest_20_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$title — $categoryLabel",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Latest ${items.size} added • Sorted by date added (newest first)",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = if (cardStyle == CardStyle.WIDE) 180.dp else 118.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(items, key = { _, item -> "grid_latest_${item.id}" }) { index, item ->
                    Column {
                        Box {
                            MediaCard(
                                item = item,
                                onClick = { onItemClick(item) },
                                cardStyle = cardStyle,
                                isFavorite = isFavorite(item.id),
                                onToggleFavorite = { onToggleFavorite(item) },
                                watchProgress = watchHistory.find { it.mediaId == item.id }
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(top = 34.dp, start = 6.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(KrasRed)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "#${index + 1}",
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatAddedDateBadge(item.addedTimestamp),
                            color = TextSecondary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
