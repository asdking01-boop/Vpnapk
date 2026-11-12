package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import com.example.data.model.CardStyle
import com.example.data.model.MediaItem
import com.example.data.model.WatchProgress
import com.example.ui.components.MediaCard
import com.example.ui.components.ResolutionBadge
import com.example.ui.components.liquidGlassPanel
import com.example.ui.theme.*

/**
 * Premium Netflix-style Movie Details Screen.
 *
 * Features:
 * - Large cinematic backdrop with atmospheric vertical gradient overlays
 * - Title, release year, rating (star), genre, duration, and resolution badge (4K, FHD, HD)
 * - Prominent Red ▶ "Play Movie" button (launches internal video player with actual IPTV stream URL)
 * - "My List" / Favorite toggle button
 * - Trailer button (opens YouTube trailer via Intent when available)
 * - Full movie description, cast, and director
 * - Similar Movies recommendations row with poster navigation
 * - Back button and Android BackHandler support
 * - Bilingual support (Arabic and English)
 */
@Composable
fun MovieDetailsScreen(
    movie: MediaItem,
    allMovies: List<MediaItem>,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onPlayMovie: (MediaItem) -> Unit,
    onMovieClick: (MediaItem) -> Unit,
    onBack: () -> Unit,
    watchHistory: List<WatchProgress> = emptyList(),
    isArabic: Boolean = false,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current

    // Extract backdrop & poster
    val backdropUrl = movie.backdrop.ifBlank { movie.streamIcon }.takeIf { it.isNotBlank() }
    val posterUrl = movie.streamIcon.ifBlank { movie.backdrop }.takeIf { it.isNotBlank() }

    // Similar movies from same category or genre, excluding current item
    val similarMovies = remember(movie, allMovies) {
        allMovies.filter { other ->
            other.id != movie.id && (
                other.categoryId == movie.categoryId ||
                (other.genre.isNotBlank() && movie.genre.isNotBlank() && other.genre.contains(movie.genre.split(",").firstOrNull()?.trim() ?: "", ignoreCase = true)) ||
                other.categoryName.equals(movie.categoryName, ignoreCase = true)
            )
        }.take(15)
    }

    // Trailer URL or ID
    val trailerQuery = remember(movie) {
        val y = movie.year.take(4)
        if (y.isNotBlank()) "${movie.name} $y official trailer" else "${movie.name} official trailer"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
            .testTag("movie_details_screen")
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 80.dp)
        ) {
            // 1. Large Cinematic Backdrop Hero (Netflix-Style)
            item(key = "movie_backdrop_hero") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                ) {
                    if (backdropUrl != null) {
                        AsyncImage(
                            model = backdropUrl,
                            contentDescription = movie.name,
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
                                text = movie.name.take(1),
                                color = KrasRedBright,
                                fontSize = 48.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    // Multi-layer Cinematic Gradient Scrim (Top status bar darkening + Bottom fade to CinemaBlack)
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

                    // Top App Bar with Back and Favorite Buttons
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f))
                                .border(1.dp, GlassBorder, CircleShape)
                                .testTag("movie_details_back_button")
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
                                .testTag("movie_details_fav_button")
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = if (isArabic) "المفضلة" else "Favorite",
                                tint = if (isFavorite) KrasRed else Color.White
                            )
                        }
                    }

                    // Floating Poster & Main Title Badge Overlay at bottom of hero
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Poster Thumbnail Card
                        if (posterUrl != null) {
                            Box(
                                modifier = Modifier
                                    .width(100.dp)
                                    .height(148.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CinemaCard)
                                    .border(1.2.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                            ) {
                                AsyncImage(
                                    model = posterUrl,
                                    contentDescription = movie.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        // Title, Year, Quality, and Rating Badges
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = movie.name,
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
                                    resolution = movie.quality.ifBlank { movie.resolution },
                                    isLive = false
                                )

                                if (movie.rating > 0.0) {
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
                                            text = String.format("%.1f", movie.rating),
                                            color = RatingYellow,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                if (movie.year.isNotBlank()) {
                                    Text(
                                        text = movie.year,
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                if (movie.duration.isNotBlank()) {
                                    Text(
                                        text = movie.duration,
                                        color = TextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            if (movie.genre.isNotBlank() || movie.categoryName.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = movie.genre.ifBlank { movie.categoryName },
                                    color = KrasRedBright,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // 2. Action Buttons (Prominent Red ▶ Play Movie, Add to My List, Trailer)
            item(key = "movie_action_buttons") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Prominent Red "▶ Play Movie" Button (Netflix-Style)
                    Button(
                        onClick = { onPlayMovie(movie) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = KrasRed,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("movie_play_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = if (isArabic) "تشغيل الفيلم" else "Play Movie",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isArabic) "تشغيل الفيلم" else "Play Movie",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Secondary Buttons: Add to My List + Trailer Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // My List Button
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
                                .testTag("movie_my_list_button")
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

                        // Trailer Button (YouTube Intent)
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
                                .testTag("movie_trailer_button")
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

            // 3. Full Movie Overview / Description & Metadata
            item(key = "movie_description_section") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isArabic) "القصة" else "Overview",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = movie.plot.ifBlank {
                            if (isArabic) "استمتع بمشاهدة هذا الفيلم بجودة عالية وتجربة سينمائية مميزة."
                            else "Experience an extraordinary cinematic story with stunning visuals and immersive audio."
                        },
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )

                    if (movie.cast.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row {
                            Text(
                                text = if (isArabic) "طاقم العمل: " else "Starring: ",
                                color = TextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = movie.cast,
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (movie.director.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row {
                            Text(
                                text = if (isArabic) "المخرج: " else "Director: ",
                                color = TextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = movie.director,
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 4. Similar Movies Recommendations
            if (similarMovies.isNotEmpty()) {
                item(key = "similar_movies_section") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp, bottom = 12.dp)
                    ) {
                        Text(
                            text = if (isArabic) "أفلام مشابهة" else "More Like This",
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
                            items(similarMovies, key = { it.id }) { sim ->
                                MediaCard(
                                    item = sim,
                                    onClick = { onMovieClick(sim) },
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
