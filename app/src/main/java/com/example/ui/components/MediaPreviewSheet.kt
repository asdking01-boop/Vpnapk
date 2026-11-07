package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.EnrichedMediaDetails
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPreviewSheet(
    item: MediaItem,
    enrichedDetails: EnrichedMediaDetails?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onPlayClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    isFavorite: Boolean,
    onMoreDetailsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = CinemaBlack,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = GlassBorder)
        },
        modifier = modifier.testTag("media_preview_bottom_sheet")
    ) {
        val backdropUrl = enrichedDetails?.backdropUrl?.takeIf { it.isNotBlank() }
            ?: enrichedDetails?.posterUrl?.takeIf { it.isNotBlank() }
            ?: item.backdrop.takeIf { it.isNotBlank() }
            ?: item.streamIcon

        val title = enrichedDetails?.title?.takeIf { it.isNotBlank() } ?: item.name
        val year = enrichedDetails?.year?.takeIf { it.isNotBlank() }
            ?: enrichedDetails?.releaseDate?.take(4)?.takeIf { it.isNotBlank() }
            ?: item.year

        val rating = when {
            enrichedDetails != null && enrichedDetails.rating > 0.0 -> enrichedDetails.rating
            item.rating > 0.0 -> item.rating
            else -> 0.0
        }

        val duration = enrichedDetails?.duration?.takeIf { it.isNotBlank() } ?: item.duration
        val quality = enrichedDetails?.quality?.takeIf { it.isNotBlank() }
            ?: item.quality.ifBlank { item.resolution }

        val genres = enrichedDetails?.genres?.filter { it.isNotBlank() }?.ifEmpty { null }
            ?: listOfNotNull(item.genre.takeIf { it.isNotBlank() } ?: item.categoryName.takeIf { it.isNotBlank() })

        val plot = enrichedDetails?.plot?.takeIf { it.isNotBlank() } ?: item.plot

        val mainCastNames = enrichedDetails?.cast?.map { it.name }?.filter { it.isNotBlank() }?.take(4)?.joinToString(", ")
            ?: ""

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            // Backdrop Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
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
                            .background(CinemaCardElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title.firstOrNull()?.uppercase() ?: "K",
                            color = KrasRedBright,
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Vignette gradient
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

                // Close Button
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(34.dp)
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

                // Play floating bubble on top of backdrop
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(KrasRed.copy(alpha = 0.9f))
                        .clickable(onClick = onPlayClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            // Content Info Body
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                // Title
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Metadata row (Rating, Year, Duration, Quality)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (rating > 0.0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.1f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Rating",
                                tint = RatingYellow,
                                modifier = Modifier.size(13.dp)
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

                    if (isLoading) {
                        CircularProgressIndicator(
                            color = KrasRedBright,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }

                // Genres Pill Row
                if (genres.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        genres.take(3).forEach { g ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = g,
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // 3-Line Overview
                Spacer(modifier = Modifier.height(10.dp))
                if (isLoading && plot.isBlank()) {
                    ShimmerOverviewPlaceholder()
                } else {
                    Text(
                        text = plot.ifBlank { "Stream instantly in high definition with adaptive bitrates and crystal-clear audio." },
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Main Cast
                if (mainCastNames.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Cast: $mainCastNames",
                        color = TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Action Buttons: Play, My List, Favorite, More Details
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Primary Play Button
                    Button(
                        onClick = onPlayClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1.2f)
                            .height(44.dp)
                            .testTag("preview_play_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (item.type == MediaType.SERIES) "Episodes" else "Play",
                            fontSize = 13.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // My List / Favorite Button
                    OutlinedButton(
                        onClick = onToggleFavorite,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.White.copy(alpha = 0.08f),
                            contentColor = Color.White
                        ),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = Brush.linearGradient(
                                listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.15f))
                            )
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("preview_favorite_button")
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Check else Icons.Default.Add,
                            contentDescription = "Favorite",
                            tint = if (isFavorite) KrasRedBright else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isFavorite) "Saved" else "My List",
                            fontSize = 12.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // More Details Button
                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(KrasRed.copy(alpha = 0.15f))
                            .border(1.dp, KrasRed.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                            .clickable(onClick = onMoreDetailsClick)
                            .padding(horizontal = 14.dp)
                            .testTag("preview_more_details_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Details",
                                color = KrasRedBright,
                                fontSize = 12.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "More",
                                tint = KrasRedBright,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShimmerOverviewPlaceholder() {
    val transition = rememberInfiniteTransition(label = "preview_shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer_alpha"
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .height(12.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = alpha))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(12.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = alpha))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(12.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = alpha))
        )
    }
}
