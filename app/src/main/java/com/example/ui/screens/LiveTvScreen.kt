package com.example.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.example.data.model.Category
import com.example.data.model.MediaItem
import com.example.ui.components.ResolutionBadge
import com.example.ui.theme.*

@Composable
fun LiveTvScreen(
    channels: List<MediaItem>,
    categories: List<Category>,
    isFavorite: (String) -> Boolean,
    onToggleFavorite: (MediaItem) -> Unit,
    onChannelClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedCategoryId by remember { mutableStateOf("all") }
    var searchQuery by remember { mutableStateOf("") }

    val filteredChannels = remember(channels, selectedCategoryId, searchQuery) {
        channels.filter { ch ->
            val matchCat = selectedCategoryId == "all" ||
                    ch.categoryId == selectedCategoryId ||
                    ch.categoryName.contains(selectedCategoryId, ignoreCase = true)
            val matchSearch = searchQuery.isEmpty() ||
                    ch.name.contains(searchQuery, ignoreCase = true)
            matchCat && matchSearch
        }
    }

    // Pulsing live indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
            .statusBarsPadding()
            .testTag("live_tv_screen")
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
                placeholder = { Text("Search Live Channels, Sports, News...", color = TextSecondary, fontSize = 13.sp) },
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
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("live_search_input")
            )
        }

        // Category Chips
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
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
                        text = "All Channels",
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

        // Count + Live Beacon
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .alpha(pulseAlpha)
                        .background(KrasRed)
                )
                Text(
                    text = "${filteredChannels.size} Live Streams Online",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Channels List
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 90.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filteredChannels, key = { it.id }) { ch ->
                val fav = isFavorite(ch.id)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(82.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(CinemaCard)
                        .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
                        .clickable { onChannelClick(ch) }
                        .padding(10.dp)
                        .testTag("channel_item_${ch.streamId}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Logo / Preview
                    Box(
                        modifier = Modifier
                            .width(80.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(CinemaDark),
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
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f)),
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

                    // Channel Name & Info
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = ch.name,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = ch.plot.ifEmpty { ch.categoryName },
                            color = TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ResolutionBadge(resolution = ch.resolution, isLive = true)
                            Text(
                                text = "Channel #${ch.streamId}",
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }

                    // Favorite Button
                    IconButton(onClick = { onToggleFavorite(ch) }) {
                        Icon(
                            imageVector = if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (fav) KrasRed else TextSecondary
                        )
                    }
                }
            }
        }
    }
}
