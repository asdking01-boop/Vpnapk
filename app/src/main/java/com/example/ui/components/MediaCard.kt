package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.data.model.CardStyle
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.WatchProgress
import com.example.ui.theme.*

@Composable
fun MediaCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardStyle: CardStyle = CardStyle.POSTER,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    watchProgress: WatchProgress? = null
) {
    when (cardStyle) {
        CardStyle.POSTER -> PosterCard(item, onClick, isFavorite, onToggleFavorite, watchProgress, modifier)
        CardStyle.WIDE -> WideCard(item, onClick, isFavorite, onToggleFavorite, watchProgress, modifier)
        CardStyle.COMPACT -> CompactCard(item, onClick, isFavorite, onToggleFavorite, watchProgress, modifier)
        CardStyle.BILLBOARD -> BillboardCard(item, onClick, isFavorite, onToggleFavorite, watchProgress, modifier)
    }
}

@Composable
private fun PosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
    watchProgress: WatchProgress?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(135.dp)
            .height(205.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CinemaCard)
            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("media_card_${item.id}")
    ) {
        // Poster Image
        val imageUrl = item.streamIcon.ifEmpty { item.backdrop }
        AsyncImage(
            model = imageUrl,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Gradient scrim at bottom for text readability
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.85f),
                            Color.Black
                        )
                    )
                )
        )

        // Badges overlay (Top)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Resolution badge
            ResolutionBadge(resolution = item.resolution, isLive = item.type == MediaType.LIVE)

            // Favorite toggle
            if (onToggleFavorite != null) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(onClick = onToggleFavorite),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) KrasRed else Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Details overlay (Bottom)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(8.dp)
        ) {
            Text(
                text = item.name,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (item.rating > 0.0) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Rating",
                        tint = RatingYellow,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = String.format("%.1f", item.rating),
                        color = RatingYellow,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (item.year.isNotEmpty()) {
                    Text(
                        text = "• ${item.year}",
                        color = TextSecondary,
                        fontSize = 10.sp
                    )
                }
            }

            // Progress bar if watching
            if (watchProgress != null && watchProgress.progressFraction > 0f) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { watchProgress.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = KrasRed,
                    trackColor = Color.White.copy(alpha = 0.2f)
                )
            }
        }
    }
}

@Composable
private fun WideCard(
    item: MediaItem,
    onClick: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
    watchProgress: WatchProgress?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(230.dp)
            .height(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CinemaCard)
            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("media_card_wide_${item.id}")
    ) {
        val imageUrl = item.backdrop.ifEmpty { item.streamIcon }
        AsyncImage(
            model = imageUrl,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.7f),
                            Color.Black.copy(alpha = 0.95f)
                        )
                    )
                )
        )

        // Top Badges
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ResolutionBadge(resolution = item.resolution, isLive = item.type == MediaType.LIVE)

            if (onToggleFavorite != null) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(onClick = onToggleFavorite),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) KrasRed else Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Bottom info
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(10.dp)
        ) {
            Text(
                text = item.name,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (item.categoryName.isNotEmpty()) {
                    Text(
                        text = item.categoryName,
                        color = TextSecondary,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
                if (item.rating > 0.0) {
                    Text(
                        text = "★ ${String.format("%.1f", item.rating)}",
                        color = RatingYellow,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (watchProgress != null && watchProgress.progressFraction > 0f) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { watchProgress.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = KrasRed,
                    trackColor = Color.White.copy(alpha = 0.2f)
                )
            }
        }
    }
}

@Composable
private fun CompactCard(
    item: MediaItem,
    onClick: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
    watchProgress: WatchProgress?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(90.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CinemaCard)
            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
            .testTag("media_card_compact_${item.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .width(110.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(CinemaDark)
        ) {
            AsyncImage(
                model = item.backdrop.ifEmpty { item.streamIcon },
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
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

        Spacer(modifier = Modifier.width(12.dp))

        // Info
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = item.name,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.genre.ifEmpty { item.categoryName },
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
                ResolutionBadge(resolution = item.resolution, isLive = item.type == MediaType.LIVE)
                if (item.year.isNotEmpty()) {
                    Text(text = item.year, color = TextMuted, fontSize = 10.sp)
                }
                if (item.rating > 0.0) {
                    Text(
                        text = "★ ${String.format("%.1f", item.rating)}",
                        color = RatingYellow,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (onToggleFavorite != null) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (isFavorite) KrasRed else Color.White
                )
            }
        }
    }
}

@Composable
private fun BillboardCard(
    item: MediaItem,
    onClick: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: (() -> Unit)?,
    watchProgress: WatchProgress?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(280.dp)
            .height(170.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CinemaCard)
            .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("media_card_billboard_${item.id}")
    ) {
        val imageUrl = item.backdrop.ifEmpty { item.streamIcon }
        AsyncImage(
            model = imageUrl,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.5f),
                            Color.Black.copy(alpha = 0.95f)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(12.dp)
        ) {
            ResolutionBadge(resolution = item.resolution, isLive = item.type == MediaType.LIVE)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.plot.ifEmpty { item.genre },
                color = TextSecondary,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ResolutionBadge(resolution: String, isLive: Boolean = false) {
    val (bgColor, textColor, label) = when {
        isLive -> Triple(KrasRed, Color.White, "LIVE")
        resolution.contains("4K", ignoreCase = true) -> Triple(Badge4K, Color.White, "4K UHD")
        resolution.contains("FHD", ignoreCase = true) || resolution.contains("1080", ignoreCase = true) -> Triple(BadgeFHD, Color.White, "FHD")
        else -> Triple(Color(0xFF333333), Color.LightGray, resolution.ifEmpty { "HD" })
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLive) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
            Spacer(modifier = Modifier.width(3.dp))
        }
        Text(
            text = label,
            color = textColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp
        )
    }
}
