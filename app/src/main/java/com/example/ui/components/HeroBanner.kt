package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.MediaItem
import com.example.ui.theme.*
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal const val HERO_INFINITE_MULTIPLIER = 2000

/**
 * Maps any virtual pager page index to the valid [0, itemCount - 1] candidate index,
 * supporting seamless infinite left/right looping.
 */
internal fun resolveHeroCarouselIndex(virtualPage: Int, itemCount: Int): Int {
    if (itemCount <= 0) return 0
    return ((virtualPage % itemCount) + itemCount) % itemCount
}

/**
 * Computes theInitial virtual page positioned near the center of the virtual range
 * and aligned to [initialIndex] modulo [itemCount] so users can swipe left or right infinitely.
 */
internal fun computeInitialVirtualPage(itemCount: Int, initialIndex: Int = 0): Int {
    if (itemCount <= 1) return 0
    val totalVirtualPages = itemCount * HERO_INFINITE_MULTIPLIER
    val midpoint = totalVirtualPages / 2
    val alignedBase = midpoint - (midpoint % itemCount)
    val safeInitial = ((initialIndex % itemCount) + itemCount) % itemCount
    return alignedBase + safeInitial
}

/**
 * Computes the nearest virtual page matching [targetIndex] relative to [currentVirtualPage]
 * so tapping a pagination dot animates smoothly across the shortest path.
 */
internal fun computeTargetVirtualPage(
    currentVirtualPage: Int,
    targetIndex: Int,
    itemCount: Int
): Int {
    if (itemCount <= 1) return 0
    val currentActual = resolveHeroCarouselIndex(currentVirtualPage, itemCount)
    val normalizedTarget = ((targetIndex % itemCount) + itemCount) % itemCount
    val diff = normalizedTarget - currentActual
    return currentVirtualPage + diff
}

/**
 * Interactive Netflix-inspired Hero Banner Carousel with:
 * - Smooth horizontal touch swipe gestures (LTR and RTL aware) directly across the poster & card
 * - Infinite bidirectional looping across all featured movies and series
 * - Synchronized, tappable pagination dots at top-center
 * - Zero gesture conflict with vertical Home screen scrolling (LazyColumn)
 * - Drag-state guard preventing accidental opening of movies/series while swiping
 */
@Composable
fun HeroBannerCarousel(
    items: List<MediaItem>,
    currentIndex: Int,
    onCurrentIndexChange: (Int) -> Unit,
    onPlayClick: (MediaItem) -> Unit,
    onInfoClick: (MediaItem) -> Unit,
    isFavorite: (String) -> Boolean,
    onToggleFavorite: (MediaItem) -> Unit,
    onSearchClick: () -> Unit,
    onAccountClick: () -> Unit,
    modifier: Modifier = Modifier,
    scrollOffset: Float = 0f,
    autoAdvanceIntervalMs: Long = 11_000L
) {
    if (items.isEmpty()) return

    val itemCount = items.size
    val isLooping = itemCount > 1
    val totalVirtualPages = if (isLooping) itemCount * HERO_INFINITE_MULTIPLIER else 1
    val initialVirtualPage = computeInitialVirtualPage(itemCount, currentIndex)

    val pagerState = rememberPagerState(
        initialPage = initialVirtualPage,
        pageCount = { totalVirtualPages }
    )
    val coroutineScope = rememberCoroutineScope()

    // Keep external currentIndex synchronized whenever the settled or current page changes
    LaunchedEffect(pagerState, itemCount) {
        snapshotFlow { resolveHeroCarouselIndex(pagerState.currentPage, itemCount) }
            .distinctUntilChanged()
            .collect { actualIdx ->
                onCurrentIndexChange(actualIdx)
            }
    }

    // If external currentIndex is updated programmatically (e.g., outside the pager), sync pager
    LaunchedEffect(currentIndex, itemCount) {
        if (itemCount > 1 && !pagerState.isScrollInProgress) {
            val activeActual = resolveHeroCarouselIndex(pagerState.currentPage, itemCount)
            val normalizedTarget = resolveHeroCarouselIndex(currentIndex, itemCount)
            if (activeActual != normalizedTarget) {
                val targetVirtual = computeTargetVirtualPage(
                    currentVirtualPage = pagerState.currentPage,
                    targetIndex = normalizedTarget,
                    itemCount = itemCount
                )
                pagerState.animateScrollToPage(
                    page = targetVirtual,
                    animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing)
                )
            }
        }
    }

    // Auto-advance timer that pauses while the user is touching/dragging the banner
    LaunchedEffect(itemCount, pagerState.settledPage, pagerState.isScrollInProgress) {
        if (itemCount > 1 && !pagerState.isScrollInProgress && autoAdvanceIntervalMs > 0L) {
            delay(autoAdvanceIntervalMs)
            if (!pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage(
                    page = pagerState.currentPage + 1,
                    animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing)
                )
            }
        }
    }

    val activeSlideIndex = resolveHeroCarouselIndex(pagerState.currentPage, itemCount)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(500.dp)
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .testTag("hero_banner")
    ) {
        // 1. Horizontal Swipeable Pager for Featured Movies & Series
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = isLooping,
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                snapAnimationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ),
            modifier = Modifier
                .fillMaxSize()
                .testTag("hero_banner_pager")
        ) { virtualPage ->
            val candidateIndex = resolveHeroCarouselIndex(virtualPage, itemCount)
            val slideItem = items[candidateIndex]
            val pageOffset = (
                (pagerState.currentPage - virtualPage) + pagerState.currentPageOffsetFraction
            ).coerceIn(-1f, 1f)
            val isCurrentPage = virtualPage == pagerState.currentPage

            HeroBannerSlideContent(
                item = slideItem,
                onPlayClick = {
                    if (!pagerState.isScrollInProgress) {
                        onPlayClick(slideItem)
                    }
                },
                onInfoClick = {
                    if (!pagerState.isScrollInProgress) {
                        onInfoClick(slideItem)
                    }
                },
                isFavorite = isFavorite(slideItem.id),
                onToggleFavorite = {
                    if (!pagerState.isScrollInProgress) {
                        onToggleFavorite(slideItem)
                    }
                },
                scrollOffset = scrollOffset,
                pageOffset = pageOffset,
                includeActionTestTags = isCurrentPage
            )
        }

        // 2. Stationary Top Navigation Header with Kras TV Brand Wordmark & Liquid Glass Action Capsules
        HeroBannerTopHeader(
            onSearchClick = onSearchClick,
            onAccountClick = onAccountClick
        )

        // 3. Synchronized & Tappable Carousel Indicator Dots (top-center below header)
        if (itemCount > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.38f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("hero_pagination_dots"),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { idx, _ ->
                    val isCurrent = idx == activeSlideIndex
                    val dotWidth by animateDpAsState(
                        targetValue = if (isCurrent) 18.dp else 5.dp,
                        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                        label = "hero_dot_width"
                    )
                    val dotColor by animateColorAsState(
                        targetValue = if (isCurrent) KrasRedBright else Color.White.copy(alpha = 0.35f),
                        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                        label = "hero_dot_color"
                    )

                    Box(
                        modifier = Modifier
                            .height(5.dp)
                            .width(dotWidth)
                            .clip(CircleShape)
                            .background(dotColor)
                            .clickable {
                                val targetPage = computeTargetVirtualPage(
                                    currentVirtualPage = pagerState.currentPage,
                                    targetIndex = idx,
                                    itemCount = itemCount
                                )
                                onCurrentIndexChange(idx)
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(
                                        page = targetPage,
                                        animationSpec = tween(
                                            durationMillis = 420,
                                            easing = FastOutSlowInEasing
                                        )
                                    )
                                }
                            }
                            .testTag("hero_pagination_dot_$idx")
                    )
                }
            }
        }
    }
}

@Composable
fun HeroBanner(
    item: MediaItem,
    onPlayClick: () -> Unit,
    onInfoClick: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onSearchClick: () -> Unit,
    onAccountClick: () -> Unit,
    modifier: Modifier = Modifier,
    scrollOffset: Float = 0f
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(500.dp)
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .testTag("hero_banner")
    ) {
        HeroBannerSlideContent(
            item = item,
            onPlayClick = onPlayClick,
            onInfoClick = onInfoClick,
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            scrollOffset = scrollOffset,
            pageOffset = 0f,
            includeActionTestTags = true
        )

        HeroBannerTopHeader(
            onSearchClick = onSearchClick,
            onAccountClick = onAccountClick
        )
    }
}

@Composable
private fun HeroBannerTopHeader(
    onSearchClick: () -> Unit,
    onAccountClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        KrasLogo(
            height = 30.dp,
            modifier = Modifier.testTag("hero_brand_logo")
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .liquidGlassPanel(
                        cornerRadius = 21.dp,
                        surfaceAlpha = 0.65f,
                        borderAlpha = 0.6f
                    )
                    .clickable(onClick = onSearchClick)
                    .testTag("hero_search_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(KrasRedBright, KrasRedDark)
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                    .clickable(onClick = onAccountClick)
                    .testTag("hero_account_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = "Account Switcher",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun HeroBannerSlideContent(
    item: MediaItem,
    onPlayClick: () -> Unit,
    onInfoClick: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    scrollOffset: Float,
    pageOffset: Float,
    includeActionTestTags: Boolean
) {
    // Slow, battery-friendly 18-second Ken Burns zoom & gentle pan (GPU graphicsLayer only)
    val kenBurnsTransition = rememberInfiniteTransition(label = "hero_ken_burns")
    val kenBurnsScale by kenBurnsTransition.animateFloat(
        initialValue = 1.02f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ken_burns_scale"
    )
    val kenBurnsPanX by kenBurnsTransition.animateFloat(
        initialValue = -14f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ken_burns_pan_x"
    )

    val bgUrl = item.backdrop.ifBlank { item.streamIcon }.takeIf { it.isNotBlank() }
    val absPageOffset = abs(pageOffset).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = 1f - (absPageOffset * 0.18f)
            }
    ) {
        // 1. Ambient blurred poster glow underneath
        if (bgUrl != null) {
            AsyncImage(
                model = bgUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.3f
                        scaleY = 1.3f
                        alpha = 0.45f
                    }
                    .blur(42.dp)
            )

            // 2. Main Hero Artwork with slow Ken Burns zoom + vertical scroll parallax + horizontal swipe depth parallax
            AsyncImage(
                model = bgUrl,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val swipeZoom = 1f + (absPageOffset * 0.04f)
                        scaleX = kenBurnsScale * swipeZoom
                        scaleY = kenBurnsScale * swipeZoom
                        translationX = kenBurnsPanX + (pageOffset * size.width * 0.22f)
                        translationY = scrollOffset * 0.32f
                    }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                KrasRedDark.copy(alpha = 0.45f),
                                CinemaCardElevated,
                                CinemaBlack
                            )
                        )
                    )
            )
        }

        // 3. Multi-Stop Cinematic Lighting & AMOLED Vignette Scrim (cached for 120 FPS)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val verticalVignette = Brush.verticalGradient(
                        0.0f to CinemaBlack.copy(alpha = 0.82f),
                        0.22f to Color.Transparent,
                        0.55f to CinemaBlack.copy(alpha = 0.45f),
                        0.84f to CinemaBlack.copy(alpha = 0.92f),
                        1.0f to CinemaBlack
                    )
                    val redCinemaRim = Brush.radialGradient(
                        colors = listOf(
                            KrasRed.copy(alpha = 0.18f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.18f, size.height * 0.78f),
                        radius = size.width * 0.75f
                    )
                    onDrawBehind {
                        drawRect(verticalVignette)
                        drawRect(redCinemaRim)
                    }
                }
        )

        // 4. Hero Content Info inside a subtle Liquid Glass Showcase Card at Bottom
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .graphicsLayer {
                    alpha = 1f - (absPageOffset * 0.45f)
                    translationY = absPageOffset * 18f
                    val cardScale = 1f - (absPageOffset * 0.03f)
                    scaleX = cardScale
                    scaleY = cardScale
                }
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .liquidGlassPanel(
                    cornerRadius = 24.dp,
                    tintColor = Color(0xFF0B0D14),
                    surfaceAlpha = 0.62f,
                    borderAlpha = 0.48f,
                    accentGlow = KrasRed
                )
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            // Badges row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ResolutionBadge(
                    resolution = item.quality.ifBlank { item.resolution },
                    isLive = false
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    KrasRed.copy(alpha = 0.85f),
                                    KrasRedDark.copy(alpha = 0.85f)
                                )
                            )
                        )
                        .border(0.75.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "TOP 10 TODAY",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.6.sp
                    )
                }

                if (item.rating > 0.0) {
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
                            text = String.format("%.1f", item.rating),
                            color = RatingYellow,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (item.year.isNotEmpty()) {
                    Text(
                        text = item.year,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Main Title
            Text(
                text = item.name,
                color = Color.White,
                fontSize = 26.sp,
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.4).sp,
                lineHeight = 30.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Genre / Plot
            if (item.genre.isNotEmpty() || item.plot.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.genre.ifEmpty { item.plot },
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons (Play, My List, Info)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Primary Play Button (Luminous White + Specular Highlight)
                Button(
                    onClick = onPlayClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .then(
                            if (includeActionTestTags) Modifier.testTag("hero_play_button")
                            else Modifier
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.Black,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Play Now",
                        fontSize = 14.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                // Liquid Glass "My List" Button
                OutlinedButton(
                    onClick = onToggleFavorite,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.White.copy(alpha = 0.1f),
                        contentColor = Color.White
                    ),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                        brush = Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.5f),
                                Color.White.copy(alpha = 0.15f)
                            )
                        )
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .height(46.dp)
                        .then(
                            if (includeActionTestTags) Modifier.testTag("hero_favorite_button")
                            else Modifier
                        )
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Check else Icons.Default.Add,
                        contentDescription = "My List",
                        tint = if (isFavorite) KrasRedBright else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (isFavorite) "In List" else "My List",
                        fontSize = 13.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Liquid Glass Info Button
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .liquidGlassPanel(
                            cornerRadius = 14.dp,
                            surfaceAlpha = 0.55f,
                            borderAlpha = 0.55f
                        )
                        .clickable(onClick = onInfoClick)
                        .then(
                            if (includeActionTestTags) Modifier.testTag("hero_info_button")
                            else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "More Info",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
