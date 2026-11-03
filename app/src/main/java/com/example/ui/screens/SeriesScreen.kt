package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    onLoadMore: (() -> Unit)? = null,
    onFilterChanged: ((categoryId: String, query: String, minRating: Double, year: String, qualityFilter: String, sortByQuality: Boolean) -> Unit)? = null
) {
    var selectedCategoryId by remember { mutableStateOf("all") }
    var searchQuery by remember { mutableStateOf("") }
    var selectedQualityFilter by remember { mutableStateOf("All") }
    var sortByQuality by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    val qualityFilterOptions = remember { listOf("All", "4K", "FHD", "HD", "SD") }

    LaunchedEffect(selectedCategoryId, searchQuery, selectedQualityFilter, sortByQuality) {
        onFilterChanged?.invoke(selectedCategoryId, searchQuery, 0.0, "All", selectedQualityFilter, sortByQuality)
    }

    val filteredSeries = remember(
        seriesList,
        selectedCategoryId,
        searchQuery,
        selectedQualityFilter,
        sortByQuality,
        onFilterChanged
    ) {
        if (onFilterChanged != null) {
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
    }

    LaunchedEffect(gridState.canScrollForward, filteredSeries.size) {
        val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisible >= filteredSeries.size - 6 && filteredSeries.isNotEmpty()) {
            onLoadMore?.invoke()
        }
    }

    val ambientPosterUrl = remember(filteredSeries) {
        filteredSeries.firstOrNull()?.let { it.backdrop.ifBlank { it.streamIcon } }
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
                modifier = Modifier.testTag("series_quality_filter_row")
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

            // Count
            Text(
                text = "${totalCount.coerceAtLeast(filteredSeries.size)} Shows available",
                color = TextSecondary,
                fontSize = 12.sp,
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )

            // Grid
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 118.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
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
