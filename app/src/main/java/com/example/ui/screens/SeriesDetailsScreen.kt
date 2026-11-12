package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.*
import com.example.ui.components.MediaCard
import com.example.ui.components.ResolutionBadge
import com.example.ui.theme.*

/**
 * Premium Netflix-style TV Series Details Screen (Full-Screen / Modal Sheet).
 *
 * Features:
 * - Large cinematic backdrop image with subtle dark gradient overlays
 * - Series title, rating, year, genres, and description
 * - Prominent Red ▶ "Play Series" button (starts first available episode or resumes last-watched episode)
 * - Seasons selector tabs
 * - Complete episode list with episode number, title, thumbnail, duration, and plot
 * - Tapping an episode starts playback of that specific episode immediately
 * - Trailer button (opens YouTube search/trailer)
 * - Similar Series recommendations row
 * - Full Android back-button navigation support
 * - Bilingual support (Arabic and English)
 */
@Composable
fun SeriesDetailsScreen(
    seriesItem: MediaItem,
    seriesDetails: SeriesDetails?,
    allSeries: List<MediaItem>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onPlayEpisode: (MediaItem, Episode) -> Unit,
    onDownloadEpisode: (MediaItem, Episode) -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    watchHistory: List<WatchProgress>,
    onSeriesClick: (MediaItem) -> Unit,
    isArabic: Boolean = false,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onDismiss)

    val context = LocalContext.current
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
                name = if (isArabic) "الموسم 1" else "Season 1",
                episodeCount = 1,
                episodes = listOf(
                    Episode(
                        id = "ep_${seriesItem.streamId}_1",
                        episodeNum = 1,
                        title = if (isArabic) "الحلقة 1: البداية" else "Episode 1: Pilot",
                        plot = seriesItem.plot,
                        streamUrl = seriesItem.streamUrl,
                        seriesId = seriesItem.streamId
                    )
                )
            )
        )
    )

    val activeSeason = details.seasons.getOrNull(selectedSeasonIndex) ?: details.seasons.firstOrNull()

    // Find the last-watched episode if any in watchHistory
    val lastWatched = remember(watchHistory, seriesItem.id, seriesItem.streamId) {
        watchHistory.firstOrNull { it.mediaId.startsWith("ep_${seriesItem.streamId}") || it.mediaId == seriesItem.id }
    }

    // Determine target episode for "Play Series" primary action (resume last watched or first episode)
    val targetResumeEpisode: Episode? = remember(activeSeason, details.seasons, lastWatched) {
        if (lastWatched != null && lastWatched.episodeNum != null) {
            val allEps = details.seasons.flatMap { it.episodes }
            allEps.find { it.episodeNum == lastWatched.episodeNum && (lastWatched.seasonNum == null || it.seasonNumber == lastWatched.seasonNum) }
                ?: activeSeason?.episodes?.firstOrNull()
                ?: details.seasons.firstOrNull()?.episodes?.firstOrNull()
        } else {
            activeSeason?.episodes?.firstOrNull()
                ?: details.seasons.firstOrNull()?.episodes?.firstOrNull()
        }
    }

    // Similar series recommendations
    val similarSeries = remember(seriesItem, allSeries) {
        allSeries.filter { other ->
            other.id != seriesItem.id && (
                other.categoryId == seriesItem.categoryId ||
                other.categoryName.equals(seriesItem.categoryName, ignoreCase = true) ||
                (other.genre.isNotBlank() && seriesItem.genre.isNotBlank() && other.genre.contains(seriesItem.genre.split(",").firstOrNull()?.trim() ?: "", ignoreCase = true))
            )
        }.take(15)
    }

    val trailerQuery = remember(seriesItem) {
        val y = seriesItem.year.take(4)
        if (y.isNotBlank()) "${seriesItem.name} $y official trailer" else "${seriesItem.name} official trailer"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
            .testTag("series_details_screen")
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 80.dp)
        ) {
            // 1. Cinematic Backdrop Header Hero
            item(key = "series_backdrop_hero") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
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
                                .background(CinemaCardElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = details.name.take(1),
                                color = KrasRedBright,
                                fontSize = 48.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    // Multi-layer Gradient Scrim
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.75f),
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.5f),
                                        CinemaBlack
                                    )
                                )
                            )
                    )

                    // Top Bar (Back and Favorite)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f))
                                .border(1.dp, GlassBorder, CircleShape)
                                .testTag("series_details_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = if (isArabic) "رجوع" else "Back",
                                tint = Color.White
                            )
                        }

                        IconButton(
                            onClick = onToggleFavorite,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f))
                                .border(1.dp, GlassBorder, CircleShape)
                                .testTag("series_details_fav_button")
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = if (isArabic) "المفضلة" else "Favorite",
                                tint = if (isFavorite) KrasRed else Color.White
                            )
                        }
                    }

                    // Floating Poster & Main Series Title / Badges
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        val posterThumb = details.cover.ifBlank { seriesItem.streamIcon }.takeIf { it.isNotBlank() }
                        if (posterThumb != null) {
                            Box(
                                modifier = Modifier
                                    .width(100.dp)
                                    .height(148.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CinemaCard)
                                    .border(1.2.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                            ) {
                                AsyncImage(
                                    model = posterThumb,
                                    contentDescription = details.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = details.name,
                                color = Color.White,
                                fontSize = 22.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Black,
                                lineHeight = 26.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ResolutionBadge(
                                    resolution = details.quality.ifBlank {
                                        seriesItem.quality.ifBlank { seriesItem.resolution }
                                    },
                                    isLive = false
                                )

                                if (details.rating > 0.0) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color.White.copy(alpha = 0.12f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = null,
                                            tint = RatingYellow,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = String.format("%.1f", details.rating),
                                            color = RatingYellow,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                if (details.releaseDate.isNotBlank()) {
                                    Text(
                                        text = details.releaseDate,
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                if (details.seasons.isNotEmpty()) {
                                    Text(
                                        text = if (isArabic) "${details.seasons.size} مواسم" else "${details.seasons.size} Seasons",
                                        color = KrasRedBright,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            if (details.genre.isNotBlank() || seriesItem.genre.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = details.genre.ifBlank { seriesItem.genre.ifBlank { seriesItem.categoryName } },
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // 2. Play Series / Resume Primary Action & Secondary Buttons
            item(key = "series_action_buttons") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    val playLabel = if (lastWatched != null && lastWatched.seasonNum != null && lastWatched.episodeNum != null) {
                        if (isArabic) "متابعة م${lastWatched.seasonNum}:ح${lastWatched.episodeNum}"
                        else "Resume S${lastWatched.seasonNum}:E${lastWatched.episodeNum}"
                    } else {
                        if (isArabic) "تشغيل المسلسل (الحلقة 1)" else "Play Series (Episode 1)"
                    }

                    // Prominent Red "▶ Play Series" Button
                    Button(
                        onClick = {
                            if (targetResumeEpisode != null) {
                                onPlayEpisode(seriesItem, targetResumeEpisode)
                            } else {
                                val first = activeSeason?.episodes?.firstOrNull()
                                if (first != null) onPlayEpisode(seriesItem, first)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = KrasRed,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("series_play_primary_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = if (isArabic) "تشغيل" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = playLabel,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Secondary Buttons (My List + Trailer)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onToggleFavorite,
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.White.copy(alpha = 0.08f),
                                contentColor = Color.White
                            ),
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = Brush.horizontalGradient(listOf(GlassBorder, GlassBorder))
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("series_my_list_button")
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Check else Icons.Default.Add,
                                contentDescription = null,
                                tint = if (isFavorite) KrasRed else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isFavorite) {
                                    if (isArabic) "في قائمتي" else "In My List"
                                } else {
                                    if (isArabic) "قائمتي" else "My List"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Trailer Button
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(trailerQuery)}")
                                    )
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.White.copy(alpha = 0.08f),
                                contentColor = Color.White
                            ),
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = Brush.horizontalGradient(listOf(GlassBorder, GlassBorder))
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("series_trailer_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Movie,
                                contentDescription = null,
                                tint = RatingYellow,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isArabic) "الإعلان الدعائي" else "Trailer",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // 3. Series Synopsis & Cast
            item(key = "series_synopsis_section") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isArabic) "عن المسلسل" else "About the Series",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = details.plot.ifEmpty {
                            if (isArabic) "رحلة درامية مشوقة وصراعات مليئة بالإثارة والغموض عبر مواسم متعددة."
                            else "An extraordinary story unfolding across space and time with high-stakes conflicts."
                        },
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )

                    if (details.cast.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row {
                            Text(
                                text = if (isArabic) "بطولة: " else "Starring: ",
                                color = TextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = details.cast,
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (details.director.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row {
                            Text(
                                text = if (isArabic) "إخراج: " else "Director: ",
                                color = TextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = details.director,
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 4. Seasons Selector Tabs
            if (details.seasons.isNotEmpty()) {
                item(key = "seasons_selector_section") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        Text(
                            text = if (isArabic) "الحلقات والمواسم" else "Episodes & Seasons",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(details.seasons.indices.toList()) { index ->
                                val season = details.seasons[index]
                                val isSelected = index == selectedSeasonIndex
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) KrasRed else CinemaCard)
                                        .border(
                                            1.dp,
                                            if (isSelected) KrasRedBright else GlassBorder,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable { selectedSeasonIndex = index }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                        .testTag("season_tab_$index")
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

            // 5. Episode List with Numbers, Thumbnails, Duration, and Plot
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
                        // Episode Thumbnail or Play Icon Badge
                        val epThumb = ep.coverUrl.ifBlank { details.backdrop.ifBlank { details.cover } }.takeIf { it.isNotBlank() }
                        Box(
                            modifier = Modifier
                                .width(80.dp)
                                .height(52.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(CinemaCardElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            if (epThumb != null) {
                                AsyncImage(
                                    model = epThumb,
                                    contentDescription = ep.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.35f))
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = if (isArabic) "تشغيل الحلقة" else "Play Episode",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Episode Details (Number, Title, Quality, Duration, Plot)
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "${ep.episodeNum}. ${ep.title}",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                val epBadge = ep.quality.ifBlank {
                                    details.quality.ifBlank { seriesItem.quality.ifBlank { seriesItem.resolution } }
                                }
                                ResolutionBadge(resolution = epBadge, isLive = false)
                            }

                            if (ep.plot.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = ep.plot,
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            if (ep.durationSec > 0) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (isArabic) "${ep.durationSec / 60} دقيقة" else "${ep.durationSec / 60}m",
                                    color = TextMuted,
                                    fontSize = 10.sp
                                )
                            }
                        }

                        // Download Episode button
                        IconButton(onClick = { onDownloadEpisode(seriesItem, ep) }) {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = if (isArabic) "تنزيل الحلقة" else "Download Episode",
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            } else if (isLoading) {
                item(key = "series_episodes_loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = KrasRedBright,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            // 6. Similar Series Recommendations Row
            if (similarSeries.isNotEmpty()) {
                item(key = "similar_series_section") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp, bottom = 12.dp)
                    ) {
                        Text(
                            text = if (isArabic) "مسلسلات مشابهة" else "More Like This",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(similarSeries, key = { it.id }) { sim ->
                                MediaCard(
                                    item = sim,
                                    onClick = { onSeriesClick(sim) },
                                    cardStyle = CardStyle.POSTER,
                                    isFavorite = false,
                                    onToggleFavorite = null
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
