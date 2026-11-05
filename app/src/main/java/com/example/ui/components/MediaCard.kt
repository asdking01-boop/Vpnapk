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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.local.QualityDetector
import com.example.data.model.CardStyle
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.WatchProgress
import com.example.ui.theme.*

private fun Modifier.cardGlassRim(cornerRadius: Dp = 14.dp): Modifier = this.drawWithCache {
    val r = cornerRadius.toPx()
    val corner = CornerRadius(r, r)
    val rimBrush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.38f),
            Color.White.copy(alpha = 0.09f),
            Color.White.copy(alpha = 0.03f),
            Color.White.copy(alpha = 0.20f)
        ),
        start = Offset.Zero,
        end = Offset(size.width, size.height)
    )
    onDrawWithContent {
        drawContent()
        drawRoundRect(
            brush = rimBrush,
            cornerRadius = corner,
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

private fun resolveItemQuality(item: MediaItem): String {
    val explicit = item.quality.ifBlank { item.resolution }
    return if (explicit.isNotBlank()) {
        val parsed = QualityDetector.parseStoredQuality(explicit)
        if (parsed.badge.isNotEmpty() || parsed.extraTags.isNotEmpty()) {
            parsed.toStorageString()
        } else {
            QualityDetector.detectFromNameAndCategory(item.name, item.categoryName, explicit)
        }
    } else {
        QualityDetector.detectFromNameAndCategory(item.name, item.categoryName)
    }
}

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
    val effectiveQuality = resolveItemQuality(item)
    Box(
        modifier = modifier
            .width(135.dp)
            .height(205.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CinemaCard)
            .cardGlassRim(14.dp)
            .liquidPressClickable(onClick = onClick)
            .testTag("media_card_${item.id}")
    ) {
        // Poster Image
        val imageUrl = item.streamIcon.ifBlank { item.backdrop }.takeIf { it.isNotBlank() }
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = item.name,
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
                    text = item.name.firstOrNull()?.uppercase() ?: "K",
                    color = KrasRedBright,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

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
            // Resolution badge (empty Box preserves SpaceBetween layout if no badge is known)
            Box(modifier = Modifier.weight(1f, fill = false)) {
                ResolutionBadge(
                    resolution = effectiveQuality,
                    isLive = item.type == MediaType.LIVE,
                    maxExtraTags = 1
                )
            }

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
                fontFamily = OutfitFontFamily,
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
    val effectiveQuality = resolveItemQuality(item)
    Box(
        modifier = modifier
            .width(230.dp)
            .height(140.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CinemaCard)
            .cardGlassRim(14.dp)
            .liquidPressClickable(onClick = onClick)
            .testTag("media_card_wide_${item.id}")
    ) {
        val imageUrl = item.backdrop.ifBlank { item.streamIcon }.takeIf { it.isNotBlank() }
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = item.name,
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
                    text = item.name.firstOrNull()?.uppercase() ?: "K",
                    color = KrasRedBright,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

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
            Box(modifier = Modifier.weight(1f, fill = false)) {
                ResolutionBadge(
                    resolution = effectiveQuality,
                    isLive = item.type == MediaType.LIVE
                )
            }

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
                if (item.year.isNotEmpty() && item.year != "LIVE") {
                    Text(
                        text = item.year,
                        color = TextSecondary,
                        fontSize = 10.sp
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
    val effectiveQuality = resolveItemQuality(item)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(90.dp)
            .liquidGlassPanel(cornerRadius = 14.dp, surfaceAlpha = 0.72f, borderAlpha = 0.42f)
            .liquidPressClickable(onClick = onClick)
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
            val imageUrl = item.backdrop.ifBlank { item.streamIcon }.takeIf { it.isNotBlank() }
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = item.name,
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
                        text = item.name.firstOrNull()?.uppercase() ?: "K",
                        color = KrasRedBright,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
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

        Spacer(modifier = Modifier.width(12.dp))

        // Info
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = item.name,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                ResolutionBadge(
                    resolution = effectiveQuality,
                    isLive = item.type == MediaType.LIVE
                )
            }
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
    val effectiveQuality = resolveItemQuality(item)
    Box(
        modifier = modifier
            .width(280.dp)
            .height(170.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CinemaCard)
            .cardGlassRim(16.dp)
            .liquidPressClickable(onClick = onClick)
            .testTag("media_card_billboard_${item.id}")
    ) {
        val imageUrl = item.backdrop.ifBlank { item.streamIcon }.takeIf { it.isNotBlank() }
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = item.name,
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
                    text = item.name.firstOrNull()?.uppercase() ?: "K",
                    color = KrasRedBright,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

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
            ResolutionBadge(
                resolution = effectiveQuality,
                isLive = item.type == MediaType.LIVE
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (item.year.isNotEmpty() && item.year != "LIVE") {
                    Text(
                        text = item.year,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = item.plot.ifEmpty { item.genre },
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Renders the canonical resolution/quality badge ("8K", "4K UHD", "FHD", "HD", "SD")
 * with distinct colors per level (8K gold, 4K UHD purple, FHD blue, HD green, SD gray)
 * and optional small extra tags ("HEVC", "50 FPS", "60 FPS", "5.1").
 * If nothing is known, renders nothing (never a wrong badge).
 */
@Composable
fun ResolutionBadge(
    resolution: String,
    isLive: Boolean = false,
    showExtraTags: Boolean = true,
    maxExtraTags: Int = 3,
    modifier: Modifier = Modifier
) {
    val parsed = remember(resolution) {
        QualityDetector.parseStoredQuality(resolution)
    }

    if (parsed.badge.isEmpty() && (!showExtraTags || parsed.extraTags.isEmpty())) {
        return
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (parsed.badge.isNotEmpty()) {
            val (bgColor, textColor, label) = when (parsed.badge) {
                "8K" -> Triple(Badge8K, Color.Black, "8K")
                "4K UHD" -> Triple(Badge4K, Color.White, "4K UHD")
                "FHD" -> Triple(BadgeFHD, Color.White, "FHD")
                "HD" -> Triple(BadgeHD, Color.White, "HD")
                "SD" -> Triple(BadgeSD, Color.White, "SD")
                else -> Triple(BadgeHD, Color.White, parsed.badge)
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(bgColor)
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = label,
                    color = textColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.3.sp,
                    maxLines = 1
                )
            }
        }

        if (showExtraTags && parsed.extraTags.isNotEmpty()) {
            parsed.extraTags.take(maxExtraTags).forEach { tag ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xCC262626))
                        .border(0.5.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.5.dp)
                ) {
                    Text(
                        text = tag,
                        color = Color(0xFFE0E0E0),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
