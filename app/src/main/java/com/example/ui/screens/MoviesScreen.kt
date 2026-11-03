package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
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
import com.example.data.local.QualityDetector
import com.example.data.model.CardStyle
import com.example.data.model.Category
import com.example.data.model.MediaItem
import com.example.data.model.WatchProgress
import com.example.ui.components.DynamicAmbientPosterBackdrop
import com.example.ui.components.LiquidGlassChip
import com.example.ui.components.MediaCard
import com.example.ui.components.liquidGlassPanel
import com.example.ui.theme.*

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
    onLoadMore: (() -> Unit)? = null,
    onFilterChanged: ((categoryId: String, query: String, minRating: Double, year: String, qualityFilter: String, sortByQuality: Boolean) -> Unit)? = null
) {
    var selectedCategoryId by remember { mutableStateOf("all") }
    var searchQuery by remember { mutableStateOf("") }
    var minRating by remember { mutableStateOf(0.0) }
    var selectedYear by remember { mutableStateOf("All") }
    var selectedQualityFilter by remember { mutableStateOf("All") }
    var sortByQuality by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }

    val qualityFilterOptions = remember { listOf("All", "4K", "FHD", "HD", "SD") }

    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()

    // Trigger filter update when controls change
    LaunchedEffect(selectedCategoryId, searchQuery, minRating, selectedYear, selectedQualityFilter, sortByQuality) {
        if (onFilterChanged != null) {
            onFilterChanged(selectedCategoryId, searchQuery, minRating, selectedYear, selectedQualityFilter, sortByQuality)
        }
    }

    val filteredMovies = remember(
        movies,
        selectedCategoryId,
        searchQuery,
        minRating,
        selectedYear,
        selectedQualityFilter,
        sortByQuality,
        onFilterChanged
    ) {
        if (onFilterChanged != null) {
            movies
        } else {
            val filtered = movies.filter { movie ->
                val matchCategory = selectedCategoryId == "all" ||
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
            if (sortByQuality) {
                QualityDetector.sortMediaByQualityDescending(filtered)
            } else {
                filtered
            }
        }
    }

    // Scroll pagination triggers
    LaunchedEffect(gridState.canScrollForward, filteredMovies.size) {
        val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisible >= filteredMovies.size - 6 && filteredMovies.isNotEmpty()) {
            onLoadMore?.invoke()
        }
    }

    LaunchedEffect(listState.canScrollForward, filteredMovies.size) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisible >= filteredMovies.size - 6 && filteredMovies.isNotEmpty()) {
            onLoadMore?.invoke()
        }
    }

    val ambientPosterUrl = remember(filteredMovies) {
        filteredMovies.firstOrNull()?.let { it.backdrop.ifBlank { it.streamIcon } }
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

                val hasActiveExtraFilter = minRating > 0.0 || selectedYear != "All" || selectedQualityFilter != "All" || sortByQuality
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
                        contentDescription = "Filter",
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

                items(categories, key = { it.categoryId }) { cat ->
                    LiquidGlassChip(
                        text = cat.categoryName,
                        isSelected = selectedCategoryId == cat.categoryId,
                        onClick = { selectedCategoryId = cat.categoryId },
                        activeColor = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Quality Filter & Sort by Quality Chip Row (combinable with Category filter)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag("movies_quality_filter_row")
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
                        onClick = { selectedQualityFilter = qOpt },
                        activeColor = activeColor,
                        modifier = Modifier.testTag("movies_quality_chip_$qOpt")
                    )
                }

                item(key = "sort_by_quality") {
                    LiquidGlassChip(
                        text = if (sortByQuality) "Sort by quality ↓" else "Sort by quality",
                        isSelected = sortByQuality,
                        onClick = { sortByQuality = !sortByQuality },
                        activeColor = KrasRed,
                        modifier = Modifier.testTag("movies_sort_by_quality_chip")
                    )
                }
            }

            // Results Count
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${totalCount.coerceAtLeast(filteredMovies.size)} Movies available",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Medium
                )
                if (minRating > 0.0 || selectedYear != "All" || selectedQualityFilter != "All" || sortByQuality) {
                    Text(
                        text = "Clear filters",
                        color = KrasRedBright,
                        fontSize = 11.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            minRating = 0.0
                            selectedYear = "All"
                            selectedQualityFilter = "All"
                            sortByQuality = false
                        }
                    )
                }
            }

            // Movies Grid / List
            if (filteredMovies.isEmpty()) {
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
                            text = "Try clearing your search or category filter",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                if (cardStyle == CardStyle.COMPACT) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
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
                        columns = GridCells.Adaptive(minSize = if (cardStyle == CardStyle.WIDE) 180.dp else 118.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
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

    // Filter Dialog
    if (showFilterDialog) {
        AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            title = {
                Text(
                    text = "Filter Movies",
                    color = Color.White,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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

                    Text("Release Year:", color = TextSecondary, fontSize = 12.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("All", "2025", "2024", "2023").forEach { yr ->
                            val isSel = selectedYear == yr
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSel) KrasRed else CinemaCard)
                                    .border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
                                    .clickable { selectedYear = yr }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(yr, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                    Text("Apply Filters", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    minRating = 0.0
                    selectedYear = "All"
                    showFilterDialog = false
                }) {
                    Text("Reset", color = TextSecondary)
                }
            },
            containerColor = CinemaDark
        )
    }
}
