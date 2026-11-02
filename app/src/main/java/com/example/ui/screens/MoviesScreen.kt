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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
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
import com.example.data.local.QualityDetector
import com.example.data.model.CardStyle
import com.example.data.model.Category
import com.example.data.model.MediaItem
import com.example.data.model.WatchProgress
import com.example.ui.components.MediaCard
import com.example.ui.theme.*

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState

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
            movies // Already filtered and sorted by Room SQL query
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
            .statusBarsPadding()
            .testTag("movies_screen")
    ) {
        // Top Header with Search and Filter
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search movies, genres...", color = TextSecondary, fontSize = 13.sp) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = KrasRed)
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = KrasRed,
                    unfocusedBorderColor = GlassBorder,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("movies_search_input")
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Filter button
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (minRating > 0.0 || selectedYear != "All" || selectedQualityFilter != "All" || sortByQuality) KrasRed else CinemaCard)
                    .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
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
            item {
                val isSelected = selectedCategoryId == "all"
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else CinemaCard)
                        .clickable { selectedCategoryId = "all" }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "All Movies",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }

            items(categories) { cat ->
                val isSelected = selectedCategoryId == cat.categoryId
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else CinemaCard)
                        .clickable { selectedCategoryId = cat.categoryId }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = cat.categoryName,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
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
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isSelected) activeColor else CinemaCard)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) Color.White.copy(alpha = 0.3f) else GlassBorder,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { selectedQualityFilter = qOpt }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                        .testTag("movies_quality_chip_$qOpt")
                ) {
                    Text(
                        text = if (qOpt == "All") "All Quality" else qOpt,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }

            item(key = "sort_by_quality") {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (sortByQuality) KrasRed else CinemaCard)
                        .border(
                            width = 1.dp,
                            color = if (sortByQuality) Color.White.copy(alpha = 0.35f) else GlassBorder,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { sortByQuality = !sortByQuality }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                        .testTag("movies_sort_by_quality_chip")
                ) {
                    Text(
                        text = if (sortByQuality) "Sort by quality ↓" else "Sort by quality",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = if (sortByQuality) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        // Results Count
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${totalCount.coerceAtLeast(filteredMovies.size)} Movies available",
                color = TextSecondary,
                fontSize = 12.sp
            )
            if (minRating > 0.0 || selectedYear != "All" || selectedQualityFilter != "All" || sortByQuality) {
                Text(
                    text = "Clear filters",
                    color = KrasRedBright,
                    fontSize = 11.sp,
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No movies match your filter",
                        color = Color.White,
                        fontSize = 16.sp,
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
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 90.dp),
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
                    columns = GridCells.Adaptive(minSize = if (cardStyle == CardStyle.WIDE) 180.dp else 125.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 90.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
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

    // Filter Dialog
    if (showFilterDialog) {
        AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            title = {
                Text("Filter Movies", color = Color.White, fontWeight = FontWeight.Bold)
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
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) KrasRed else CinemaCard)
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
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) KrasRed else CinemaCard)
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
