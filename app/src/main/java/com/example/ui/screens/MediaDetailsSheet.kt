package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.*
import com.example.ui.components.ResolutionBadge
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailsSheet(
    item: MediaItem,
    enrichedDetails: EnrichedMediaDetails?,
    seriesDetails: SeriesDetails?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onPlayMovie: (MediaItem) -> Unit,
    onPlayEpisode: (MediaItem, Episode) -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    isFavorite: Boolean,
    onSimilarClick: ((SimilarMedia) -> Unit)? = null,
    onRateTmdb: ((Double) -> Unit)? = null,
    tmdbConnected: Boolean = false,
    watchHistory: List<WatchProgress> = emptyList(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedSeasonIndex by remember { mutableIntStateOf(0) }
    var showRatingDialog by remember { mutableStateOf(false) }

    // Merge metadata
    val title = enrichedDetails?.title?.takeIf { it.isNotBlank() } ?: item.name
    val backdropUrl = enrichedDetails?.backdropUrl?.takeIf { it.isNotBlank() }
        ?: seriesDetails?.backdrop?.takeIf { it.isNotBlank() }
        ?: item.backdrop.takeIf { it.isNotBlank() }
        ?: enrichedDetails?.posterUrl?.takeIf { it.isNotBlank() }
        ?: item.streamIcon

    val posterUrl = enrichedDetails?.posterUrl?.takeIf { it.isNotBlank() }
        ?: seriesDetails?.cover?.takeIf { it.isNotBlank() }
        ?: item.streamIcon

    val year = enrichedDetails?.year?.takeIf { it.isNotBlank() }
        ?: enrichedDetails?.releaseDate?.take(4)?.takeIf { it.isNotBlank() }
        ?: seriesDetails?.releaseDate?.take(4)?.takeIf { it.isNotBlank() }
        ?: item.year

    val rating = when {
        enrichedDetails != null && enrichedDetails.rating > 0.0 -> enrichedDetails.rating
        seriesDetails != null && seriesDetails.rating > 0.0 -> seriesDetails.rating
        item.rating > 0.0 -> item.rating
        else -> 0.0
    }

    val duration = enrichedDetails?.duration?.takeIf { it.isNotBlank() } ?: item.duration
    val quality = enrichedDetails?.quality?.takeIf { it.isNotBlank() }
        ?: seriesDetails?.quality?.takeIf { it.isNotBlank() }
        ?: item.quality.ifBlank { item.resolution }

    val genres = enrichedDetails?.genres?.filter { it.isNotBlank() }?.ifEmpty { null }
        ?: listOfNotNull(item.genre.takeIf { it.isNotBlank() } ?: item.categoryName.takeIf { it.isNotBlank() })

    val plot = enrichedDetails?.plot?.takeIf { it.isNotBlank() }
        ?: seriesDetails?.plot?.takeIf { it.isNotBlank() }
        ?: item.plot

    val director = enrichedDetails?.director?.takeIf { it.isNotBlank() } ?: ""
    val castMembers = enrichedDetails?.cast ?: emptyList()
    val trailerKey = enrichedDetails?.youtubeTrailer?.takeIf { it.isNotBlank() }
        ?: seriesDetails?.youtubeTrailer?.takeIf { it.isNotBlank() }
        ?: ""

    val seasons = when {
        seriesDetails != null && seriesDetails.seasons.isNotEmpty() -> seriesDetails.seasons
        enrichedDetails != null && enrichedDetails.seasons.isNotEmpty() -> enrichedDetails.seasons
        else -> emptyList()
    }

    val activeSeason = seasons.getOrNull(selectedSeasonIndex) ?: seasons.firstOrNull()

    val similarMedia = enrichedDetails?.similar ?: emptyList()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CinemaBlack,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = GlassBorder)
        },
        modifier = modifier.testTag("media_details_sheet")
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
        ) {
            // 1. Large Backdrop Header with Poster and Title
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                ) {
                    if (backdropUrl.isNotBlank()) {
                        AsyncImage(
                            model = backdropUrl,
                            contentDescription = title,
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

                    // Scrims
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.35f),
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.85f),
                                        CinemaBlack
                                    )
                                )
                            )
                    )

                    // Close Button
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(14.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Poster floating thumbnail on bottom-left
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(90.dp)
                                .height(132.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(CinemaCard)
                                .border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
                        ) {
                            if (posterUrl.isNotBlank()) {
                                AsyncImage(
                                    model = posterUrl,
                                    contentDescription = title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        // Title & Metadata column
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = title,
                                color = Color.White,
                                fontSize = 20.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Black,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (rating > 0.0) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color.White.copy(alpha = 0.12f))
                                            .padding(horizontal = 5.dp, vertical = 2.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = "Rating",
                                            tint = RatingYellow,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            text = String.format("%.1f", rating),
                                            color = RatingYellow,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                if (year.isNotBlank() && year != "LIVE") {
                                    Text(
                                        text = year,
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                if (duration.isNotBlank()) {
                                    Text(
                                        text = duration,
                                        color = TextMuted,
                                        fontSize = 12.sp
                                    )
                                }

                                if (quality.isNotBlank()) {
                                    ResolutionBadge(resolution = quality)
                                }
                            }

                            if (genres.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = genres.joinToString(" • "),
                                    color = KrasRedBright,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // 2. Action Buttons (Play / Episodes, Trailer, Favorite, Rate)
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Play Movie or Play First Episode
                        Button(
                            onClick = {
                                if (item.type == MediaType.SERIES) {
                                    val firstEp = activeSeason?.episodes?.firstOrNull()
                                    if (firstEp != null) {
                                        onPlayEpisode(item, firstEp)
                                    } else {
                                        onPlayMovie(item)
                                    }
                                } else {
                                    onPlayMovie(item)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1.3f)
                                .height(46.dp)
                                .testTag("details_play_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.Black
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (item.type == MediaType.SERIES) "Watch Series" else "Play",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        // Trailer Button (if available)
                        if (trailerKey.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    launchYouTubeTrailer(context, trailerKey)
                                },
                                border = ButtonDefaults.outlinedButtonBorder.copy(
                                    brush = Brush.horizontalGradient(listOf(KrasRed, KrasRedDark))
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .height(46.dp)
                                    .testTag("details_trailer_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayCircleOutline,
                                    contentDescription = "Trailer",
                                    tint = KrasRedBright
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Trailer",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        // Favorite / My List Toggle
                        OutlinedButton(
                            onClick = { onToggleFavorite(item) },
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = Brush.horizontalGradient(listOf(GlassBorder, GlassBorder))
                            ),
                            shape = RoundedCornerShape(10.dp),
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

                        // TMDb Rating Button (if TMDb connected)
                        if (tmdbConnected && onRateTmdb != null) {
                            IconButton(
                                onClick = { showRatingDialog = true },
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.StarBorder,
                                    contentDescription = "Rate on TMDb",
                                    tint = RatingYellow
                                )
                            }
                        }
                    }
                }
            }

            // 3. Full Overview & Director
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        text = "Synopsis",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = plot.ifBlank { "No synopsis available for this title." },
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )

                    if (director.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Director: $director",
                            color = TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // 4. Cast with Photos (Horizontal LazyRow)
            if (castMembers.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            text = "Cast & Characters",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(castMembers, key = { it.id }) { actor ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(76.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(64.dp)
                                            .clip(CircleShape)
                                            .background(CinemaCard)
                                            .border(1.dp, GlassBorder, CircleShape)
                                    ) {
                                        if (actor.profileUrl.isNotBlank()) {
                                            AsyncImage(
                                                model = actor.profileUrl,
                                                contentDescription = actor.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Person,
                                                    contentDescription = null,
                                                    tint = TextMuted,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = actor.name,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                    if (actor.character.isNotBlank()) {
                                        Text(
                                            text = actor.character,
                                            color = TextMuted,
                                            fontSize = 9.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. For Series: Seasons Selector & Episodes List
            if (item.type == MediaType.SERIES && seasons.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(
                            text = "Seasons & Episodes",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(seasons.indices.toList()) { index ->
                                val season = seasons[index]
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
                    }
                }

                if (activeSeason != null && activeSeason.episodes.isNotEmpty()) {
                    items(activeSeason.episodes, key = { it.id }) { ep ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 5.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(CinemaCard)
                                .border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
                                .clickable { onPlayEpisode(item, ep) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Thumbnail
                            Box(
                                modifier = Modifier
                                    .width(80.dp)
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(CinemaDark)
                            ) {
                                if (ep.coverUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = ep.coverUrl,
                                        contentDescription = ep.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .align(Alignment.Center)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.6f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${ep.episodeNum}. ${ep.title}",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (ep.plot.isNotBlank()) {
                                    Text(
                                        text = ep.plot,
                                        color = TextSecondary,
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (ep.durationSec > 0) {
                                    Text(
                                        text = "${ep.durationSec / 60} min",
                                        color = TextMuted,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 6. Similar Titles Row (from TMDb)
            if (similarMedia.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(vertical = 12.dp)) {
                        Text(
                            text = "More Like This",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(similarMedia, key = { "${it.tmdbId}_${it.title}" }) { sim ->
                                Column(
                                    modifier = Modifier
                                        .width(110.dp)
                                        .clickable { onSimilarClick?.invoke(sim) }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(110.dp)
                                            .height(160.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(CinemaCard)
                                    ) {
                                        val poster = sim.posterUrl.ifBlank { sim.backdropUrl }
                                        if (poster.isNotBlank()) {
                                            AsyncImage(
                                                model = poster,
                                                contentDescription = sim.title,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                        if (sim.rating > 0.0) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(4.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(Color.Black.copy(alpha = 0.7f))
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "★ ${String.format("%.1f", sim.rating)}",
                                                    color = RatingYellow,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = sim.title,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }

    // TMDb 1 to 10 Star Rating Dialog
    if (showRatingDialog && onRateTmdb != null) {
        var userRatingValue by remember { mutableDoubleStateOf(enrichedDetails?.userRating?.takeIf { it > 0.0 } ?: 8.0) }
        AlertDialog(
            onDismissRequest = { showRatingDialog = false },
            title = {
                Text(
                    text = "Rate on TMDb",
                    color = Color.White,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "${userRatingValue.toInt()} / 10",
                        color = RatingYellow,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = userRatingValue.toFloat(),
                        onValueChange = { userRatingValue = it.toDouble() },
                        valueRange = 1f..10f,
                        steps = 8,
                        colors = SliderDefaults.colors(
                            thumbColor = RatingYellow,
                            activeTrackColor = RatingYellow,
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRateTmdb(userRatingValue)
                        showRatingDialog = false
                        Toast.makeText(context, "Rating submitted to TMDb!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = KrasRed)
                ) {
                    Text("Submit", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRatingDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = CinemaDark,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

private fun launchYouTubeTrailer(context: Context, youtubeKeyOrUrl: String) {
    if (youtubeKeyOrUrl.isBlank()) return
    val videoId = when {
        youtubeKeyOrUrl.contains("v=") -> youtubeKeyOrUrl.substringAfter("v=").substringBefore("&")
        youtubeKeyOrUrl.contains("youtu.be/") -> youtubeKeyOrUrl.substringAfter("youtu.be/").substringBefore("?")
        else -> youtubeKeyOrUrl
    }
    val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId"))
    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
    try {
        context.startActivity(appIntent)
    } catch (_: Exception) {
        try {
            context.startActivity(webIntent)
        } catch (_: Exception) {
            Toast.makeText(context, "Unable to open YouTube trailer", Toast.LENGTH_SHORT).show()
        }
    }
}
