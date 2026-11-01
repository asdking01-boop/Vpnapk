package com.example.ui.screens

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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.*
import com.example.ui.components.ResolutionBadge
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailsSheet(
    seriesItem: MediaItem,
    seriesDetails: SeriesDetails?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onPlayEpisode: (MediaItem, Episode) -> Unit,
    onDownloadEpisode: (MediaItem, Episode) -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    watchHistory: List<WatchProgress>,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CinemaBlack,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = GlassBorder)
        },
        modifier = modifier.testTag("series_details_sheet")
    ) {
        var selectedSeasonIndex by remember { mutableStateOf(0) }

        val details = seriesDetails ?: SeriesDetails(
            seriesId = seriesItem.streamId,
            name = seriesItem.name,
            cover = seriesItem.streamIcon,
            backdrop = seriesItem.backdrop,
            plot = seriesItem.plot,
            rating = seriesItem.rating,
            releaseDate = seriesItem.year,
            seasons = listOf(
                Season(
                    seasonNumber = 1,
                    name = "Season 1",
                    episodeCount = 1,
                    episodes = listOf(
                        Episode(
                            id = "ep_${seriesItem.streamId}_1",
                            episodeNum = 1,
                            title = "Episode 1: Pilot",
                            plot = seriesItem.plot,
                            streamUrl = seriesItem.streamUrl,
                            seriesId = seriesItem.streamId
                        )
                    )
                )
            )
        )

        val activeSeason = details.seasons.getOrNull(selectedSeasonIndex) ?: details.seasons.firstOrNull()

        // Check if there was a last-watched episode
        val lastWatched = remember(watchHistory, seriesItem.id) {
            watchHistory.firstOrNull { it.mediaId.startsWith("ep_${seriesItem.streamId}") || it.mediaId == seriesItem.id }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
        ) {
            // 1. Series Header Backdrop
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp)
                ) {
                    val bg = (details.backdrop.ifBlank { details.cover.ifBlank { seriesItem.streamIcon } }).takeIf { it.isNotBlank() }
                    if (bg != null) {
                        AsyncImage(
                            model = bg,
                            contentDescription = details.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(CinemaCardElevated)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.5f),
                                        CinemaBlack
                                    )
                                )
                            )
                    )

                    // Close button
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Series Title & Badges Overlay
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = details.name,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ResolutionBadge(resolution = "4K UHD")
                            Text(
                                text = "★ ${String.format("%.1f", details.rating)}",
                                color = RatingYellow,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (details.releaseDate.isNotEmpty()) {
                                Text(
                                    text = details.releaseDate,
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            Text(
                                text = "${details.seasons.size} Seasons",
                                color = KrasRedBright,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // 2. Play / Resume Action Buttons
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    val firstEpisode = activeSeason?.episodes?.firstOrNull()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                if (firstEpisode != null) {
                                    onPlayEpisode(seriesItem, firstEpisode)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("series_play_primary_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.Black
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (lastWatched != null && lastWatched.seasonNum != null)
                                    "Resume S${lastWatched.seasonNum}:E${lastWatched.episodeNum}"
                                else "Play Episode 1",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        OutlinedButton(
                            onClick = onToggleFavorite,
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = Brush.horizontalGradient(listOf(GlassBorder, GlassBorder))
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(46.dp)
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Check else Icons.Default.Add,
                                contentDescription = "Favorite",
                                tint = if (isFavorite) KrasRed else Color.White
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isFavorite) "Saved" else "My List",
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Synopsis & Cast
                    Text(
                        text = details.plot.ifEmpty { "An extraordinary story unfolding across space and time with high-stakes conflicts." },
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )

                    if (details.cast.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Starring: ${details.cast}",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                }
            }

            // 3. Seasons Selector Tabs
            if (details.seasons.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            text = "Episodes",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(details.seasons.indices.toList()) { index ->
                                val season = details.seasons[index]
                                val isSelected = index == selectedSeasonIndex
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) KrasRed else CinemaCard)
                                        .clickable { selectedSeasonIndex = index }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = season.name,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }

            // 4. Episodes List
            if (activeSeason != null && activeSeason.episodes.isNotEmpty()) {
                items(activeSeason.episodes, key = { it.id }) { ep ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(CinemaCard)
                            .border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
                            .clickable { onPlayEpisode(seriesItem, ep) }
                            .padding(10.dp)
                            .testTag("episode_item_${ep.episodeNum}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Play icon & episode number
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(KrasRed.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = KrasRedBright,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Episode details
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${ep.episodeNum}. ${ep.title}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (ep.plot.isNotEmpty()) {
                                Text(
                                    text = ep.plot,
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = "${ep.durationSec / 60}m",
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }

                        // Download Episode button
                        IconButton(onClick = { onDownloadEpisode(seriesItem, ep) }) {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = "Download Episode",
                                tint = TextSecondary
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}
