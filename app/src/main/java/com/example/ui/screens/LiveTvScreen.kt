package com.example.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.local.ChannelNameNormalizer
import com.example.data.local.QualityDetector
import com.example.data.model.Category
import com.example.data.model.LiveChannelOrder
import com.example.data.model.MediaItem
import com.example.ui.components.DynamicAmbientPosterBackdrop
import com.example.ui.components.LiquidGlassChip
import com.example.ui.components.ResolutionBadge
import com.example.ui.components.liquidGlassPanel
import com.example.ui.components.liquidPressClickable
import com.example.ui.theme.*

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
    onLoadMore: (() -> Unit)? = null,
    onFilterChanged: ((categoryId: String, query: String, qualityFilter: String, sortByQuality: Boolean) -> Unit)? = null
) {
    var selectedCategoryId by rememberSaveable { mutableStateOf("all") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedQualityFilter by rememberSaveable { mutableStateOf("All") }
    var sortByQuality by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

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

    val ambientChannelArt = remember(filteredChannels) {
        filteredChannels.firstOrNull()?.let { it.streamIcon.ifBlank { it.backdrop } }
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
                        .height(50.dp)
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
                        onClick = { selectedCategoryId = "all" },
                        activeColor = MaterialTheme.colorScheme.primary
                    )
                }

                item(key = "cat_favorites") {
                    LiquidGlassChip(
                        text = "Favorites",
                        isSelected = selectedCategoryId == "favorites",
                        onClick = { selectedCategoryId = "favorites" },
                        activeColor = MaterialTheme.colorScheme.primary
                    )
                }

                items(distinctCategories, key = { it.categoryId }) { cat ->
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
                        onClick = { selectedQualityFilter = qOpt },
                        activeColor = activeColor,
                        modifier = Modifier.testTag("live_quality_chip_$qOpt")
                    )
                }

                item(key = "sort_by_quality") {
                    LiquidGlassChip(
                        text = if (sortByQuality) "Sort by quality ↓" else "Sort by quality",
                        isSelected = sortByQuality,
                        onClick = { sortByQuality = !sortByQuality },
                        activeColor = KrasRed,
                        modifier = Modifier.testTag("live_sort_by_quality_chip")
                    )
                }
            }

            // Count + Live Beacon
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
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
                        text = "${totalCount.coerceAtLeast(filteredChannels.size)} Live Streams Online",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Channels List with Liquid Glass cards
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredChannels, key = { it.id }) { ch ->
                    val fav = isFavorite(ch.id)
                    val effectiveQuality = ch.quality.ifBlank { ch.resolution }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(84.dp)
                            .liquidGlassPanel(
                                cornerRadius = 18.dp,
                                surfaceAlpha = 0.72f,
                                borderAlpha = 0.42f
                            )
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
                                .border(0.75.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
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
                                    .background(Color.Black.copy(alpha = 0.58f))
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

                        // Channel Name & Info (with ResolutionBadge right next to the channel name)
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = ch.name,
                                    color = Color.White,
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
                                text = ch.plot.ifEmpty { ch.categoryName },
                                color = TextSecondary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "Channel #${ch.streamId}",
                                color = TextMuted,
                                fontSize = 10.sp
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
    }
}
