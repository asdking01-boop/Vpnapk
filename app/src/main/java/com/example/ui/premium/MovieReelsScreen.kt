package com.example.ui.premium

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.data.api.TmdbMetadataMatcher
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.UserSettings
import com.example.ui.components.LocalPreferArabicMetadata
import com.example.ui.components.ResolutionBadge
import com.example.ui.theme.OutfitFontFamily
import com.example.ui.theme.RatingYellow
import com.example.ui.theme.TextSecondary
import java.util.Locale

/**
 * KRAS TV — Cinematic Premium 2.0 Movie Reels Screen.
 *
 * Vertical-swipe discovery feed connected directly to the user's real IPTV Movies & Series catalog:
 * - Smooth VerticalPager navigation (60Hz/120Hz)
 * - Plays official YouTube trailers strictly via YouTube's official embed player (never scrapes/downloads streams)
 * - Respects autoplay disable & Wi-Fi-only autoplay settings
 * - Automatically pauses & cleans up embedded trailers when leaving screen or backgrounding app
 * - Watch Now launches existing IPTV playback / series screen directly
 * - Add to My List & Full Details connect seamlessly to existing KrasViewModel state
 */
@Composable
fun MovieReelsScreen(
    movies: List<MediaItem>,
    series: List<MediaItem>,
    userSettings: UserSettings,
    isFavorite: (String) -> Boolean,
    onToggleFavorite: (MediaItem) -> Unit,
    onWatchNow: (MediaItem) -> Unit,
    onOpenDetails: (MediaItem) -> Unit,
    onResolveTrailerKey: suspend (MediaItem) -> String?,
    onUpdateSettings: (UserSettings) -> Unit,
    isArabic: Boolean,
    onEnsureLocalizedMetadata: (MediaItem) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val experience = LocalPremiumExperienceState.current
    val palette = experience.palette
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Build curated vertical reel feed from the real catalog (preferring items with backdrops & ratings)
    val reelItems = remember(movies, series) {
        val combined = (movies.take(40) + series.take(25))
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<MediaItem> { it.backdrop.isNotBlank() }
                    .thenByDescending { it.rating }
                    .thenByDescending { it.addedTimestamp }
            )
            .take(30)
        combined
    }

    var isMuted by remember { mutableStateOf(true) }
    var isLifecycleResumed by remember { mutableStateOf(true) }
    var isOnWifi by remember { mutableStateOf(isWifiConnected(context)) }
    val trailerKeyMap = remember { mutableStateMapOf<String, String>() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isLifecycleResumed = true
                    isOnWifi = isWifiConnected(context)
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    isLifecycleResumed = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            isLifecycleResumed = false
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
            .testTag("movie_reels_screen")
    ) {
        if (reelItems.isEmpty()) {
            // Friendly empty state when catalog hasn't loaded yet
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = null,
                    tint = palette.primary,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = if (isArabic) "لا توجد أفلام أو مسلسلات للعرض حالياً" else "No Movies or Series Available Yet",
                    color = Color.White,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isArabic) {
                        "تظهر هنا مقاطع الأفلام والمسلسلات المتوفرة في اشتراكك بمجرد تحميل المكتبة."
                    } else {
                        "Cinematic reels from your IPTV library will appear here as soon as your catalog syncs."
                    },
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
            return@Box
        }

        val pagerState = rememberPagerState(pageCount = { reelItems.size })

        // Pre-resolve official trailer key for active page + next page
        LaunchedEffect(userSettings.tmdbMetadataLanguage, userSettings.language) {
            trailerKeyMap.clear()
        }

        LaunchedEffect(pagerState.settledPage, reelItems, userSettings.tmdbMetadataLanguage) {
            val idx = pagerState.settledPage
            val targets = listOfNotNull(
                reelItems.getOrNull(idx),
                reelItems.getOrNull(idx + 1)
            )
            for (item in targets) {
                onEnsureLocalizedMetadata(item)
                if (!trailerKeyMap.containsKey(item.id)) {
                    val resolved = try {
                        onResolveTrailerKey(item).orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                    trailerKeyMap[item.id] = resolved
                }
            }
        }

        VerticalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("movie_reels_pager"),
            beyondViewportPageCount = 1
        ) { page ->
            val item = reelItems[page]
            val isActivePage = pagerState.settledPage == page
            val trailerKey = trailerKeyMap[item.id].orEmpty()
            val autoplayAllowed = userSettings.reelsAutoplayEnabled &&
                (!userSettings.reelsWifiOnlyAutoplay || isOnWifi) &&
                !experience.batterySavingActive

            var manualPlayRequested by remember(item.id, page) { mutableStateOf(false) }
            LaunchedEffect(isActivePage) {
                if (!isActivePage) {
                    manualPlayRequested = false
                }
            }

            val shouldEmbedTrailer = isActivePage &&
                isLifecycleResumed &&
                trailerKey.isNotBlank() &&
                (autoplayAllowed || manualPlayRequested)

            MovieReelPage(
                item = item,
                trailerKey = trailerKey,
                shouldEmbedTrailer = shouldEmbedTrailer,
                isMuted = isMuted,
                isFavorite = isFavorite(item.id),
                autoplayEnabled = userSettings.reelsAutoplayEnabled,
                wifiOnlyEnabled = userSettings.reelsWifiOnlyAutoplay,
                isOnWifi = isOnWifi,
                isArabic = isArabic,
                onToggleMute = { isMuted = !isMuted },
                onRequestTrailerPlay = {
                    if (trailerKey.isNotBlank()) {
                        manualPlayRequested = true
                    } else {
                        openOfficialYoutubeTrailerSearch(context, item)
                    }
                },
                onWatchNow = { onWatchNow(item) },
                onToggleFavorite = { onToggleFavorite(item) },
                onOpenDetails = { onOpenDetails(item) }
            )
        }

        // Top Floating Header with Reels Title & Quick Autoplay / Wi-Fi-Only Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.52f))
                    .border(1.dp, palette.glassBorder, RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(palette.primary)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isArabic) "ريلز السينما" else "Movie Reels",
                    color = Color.White,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Wi-Fi-Only Autoplay Toggle Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (userSettings.reelsWifiOnlyAutoplay) {
                                palette.primary.copy(alpha = 0.28f)
                            } else {
                                Color.Black.copy(alpha = 0.52f)
                            }
                        )
                        .border(
                            1.dp,
                            if (userSettings.reelsWifiOnlyAutoplay) palette.primary else palette.glassBorder,
                            RoundedCornerShape(18.dp)
                        )
                        .clickable {
                            onUpdateSettings(
                                userSettings.copy(reelsWifiOnlyAutoplay = !userSettings.reelsWifiOnlyAutoplay)
                            )
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("reels_wifi_only_toggle")
                ) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = "Wi-Fi Only Autoplay",
                        tint = if (userSettings.reelsWifiOnlyAutoplay) palette.primaryBright else Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isArabic) "واي فاي" else "Wi-Fi",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Autoplay On/Off Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (userSettings.reelsAutoplayEnabled) {
                                palette.primary.copy(alpha = 0.28f)
                            } else {
                                Color.Black.copy(alpha = 0.52f)
                            }
                        )
                        .border(
                            1.dp,
                            if (userSettings.reelsAutoplayEnabled) palette.primary else palette.glassBorder,
                            RoundedCornerShape(18.dp)
                        )
                        .clickable {
                            onUpdateSettings(
                                userSettings.copy(reelsAutoplayEnabled = !userSettings.reelsAutoplayEnabled)
                            )
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("reels_autoplay_toggle")
                ) {
                    Text(
                        text = if (userSettings.reelsAutoplayEnabled) {
                            if (isArabic) "تشغيل تلقائي" else "Autoplay"
                        } else {
                            if (isArabic) "تلقائي متوقف" else "Manual"
                        },
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun MovieReelPage(
    item: MediaItem,
    trailerKey: String,
    shouldEmbedTrailer: Boolean,
    isMuted: Boolean,
    isFavorite: Boolean,
    autoplayEnabled: Boolean,
    wifiOnlyEnabled: Boolean,
    isOnWifi: Boolean,
    isArabic: Boolean,
    onToggleMute: () -> Unit,
    onRequestTrailerPlay: () -> Unit,
    onWatchNow: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenDetails: () -> Unit
) {
    val palette = LocalPremiumExperienceState.current.palette
    val artworkUrl = remember(item.backdrop, item.streamIcon) {
        item.backdrop.ifBlank { item.streamIcon }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        // 1. Cinematic Artwork Backdrop (always rendered underneath for instant zero-flash transitions)
        if (artworkUrl.isNotBlank()) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. Official YouTube Trailer Embed (only active page, respects autoplay & Wi-Fi settings)
        if (shouldEmbedTrailer && trailerKey.isNotBlank()) {
            OfficialYoutubeTrailerEmbed(
                videoKey = trailerKey,
                isMuted = isMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .align(Alignment.Center)
            )
        }

        // 3. Cinematic Top + Bottom Vignette Scrim for Text Legibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.60f),
                        0.28f to Color.Black.copy(alpha = 0.14f),
                        0.55f to Color.Black.copy(alpha = 0.35f),
                        0.78f to palette.background.copy(alpha = 0.88f),
                        1.0f to palette.background
                    )
                )
        )

        // 4. Right-side Floating Glass Action Rail (Mute / My List / Full Details)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 14.dp, bottom = 112.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ReelSideActionButton(
                icon = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                label = if (isMuted) {
                    if (isArabic) "صامت" else "Muted"
                } else {
                    if (isArabic) "صوت" else "Sound"
                },
                highlighted = !isMuted,
                accentColor = palette.primary,
                onClick = onToggleMute,
                testTagName = "reels_mute_button"
            )

            ReelSideActionButton(
                icon = if (isFavorite) Icons.Default.Check else Icons.Default.Add,
                label = if (isFavorite) {
                    if (isArabic) "بقائمتي" else "In List"
                } else {
                    if (isArabic) "قائمتي" else "My List"
                },
                highlighted = isFavorite,
                accentColor = palette.primary,
                onClick = onToggleFavorite,
                testTagName = "reels_mylist_button"
            )

            ReelSideActionButton(
                icon = Icons.Default.Info,
                label = if (isArabic) "التفاصيل" else "Details",
                highlighted = false,
                accentColor = palette.primary,
                onClick = onOpenDetails,
                testTagName = "reels_details_button"
            )
        }

        // 5. Bottom Metadata & Primary Watch Now CTA
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 84.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Media type + Quality + Rating + Year Badges
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(palette.primary)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (item.type == MediaType.SERIES) {
                            if (isArabic) "مسلسل" else "SERIES"
                        } else {
                            if (isArabic) "فيلم" else "MOVIE"
                        },
                        color = palette.onPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                if (item.rating > 0.0) {
                    Text(
                        text = "★ ${String.format(Locale.US, "%.1f", item.rating)}",
                        color = RatingYellow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (item.year.isNotBlank()) {
                    Text(
                        text = item.year,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                val q = item.quality.ifBlank { item.resolution }
                if (q.isNotBlank()) {
                    ResolutionBadge(resolution = q)
                }
            }

            // Title
            Text(
                text = item.name,
                color = Color.White,
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 24.sp,
                lineHeight = 28.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Localized Genre line (with RTL alignment when Arabic)
            val preferArabicMeta = LocalPreferArabicMetadata.current
            val localizedReelGenre = remember(item.genre, preferArabicMeta) {
                TmdbMetadataMatcher.localizeGenres(item.genre, preferArabic = preferArabicMeta)
            }
            if (localizedReelGenre.isNotBlank()) {
                val genreIsArabic = TmdbMetadataMatcher.containsArabicScript(localizedReelGenre)
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (genreIsArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                    Text(
                        text = localizedReelGenre,
                        color = palette.primaryBright,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                        style = TextStyle(
                            textDirection = if (genreIsArabic) TextDirection.Rtl else TextDirection.Ltr
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Brief Synopsis (verified & RTL aligned when Arabic)
            val verifiedReelPlot = remember(item.plot, item.name) {
                TmdbMetadataMatcher.sanitizeSynopsis(item.plot, item.name)
            }
            if (verifiedReelPlot.isNotBlank()) {
                val plotIsArabic = TmdbMetadataMatcher.containsArabicScript(verifiedReelPlot)
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (plotIsArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                    Text(
                        text = verifiedReelPlot,
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                        style = TextStyle(
                            textDirection = if (plotIsArabic) TextDirection.Rtl else TextDirection.Ltr
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Official Trailer Status / Trigger Chip (when not currently playing embedded trailer)
            if (!shouldEmbedTrailer) {
                val trailerChipText = when {
                    trailerKey.isNotBlank() && !autoplayEnabled ->
                        if (isArabic) "اضغط لتشغيل الإعلان الرسمي" else "Tap to Play Official Trailer"
                    trailerKey.isNotBlank() && wifiOnlyEnabled && !isOnWifi ->
                        if (isArabic) "التشغيل التلقائي على Wi-Fi فقط • اضغط للتشغيل" else "Wi-Fi Autoplay Only • Tap to Play Trailer"
                    else ->
                        if (isArabic) "مشاهدة الإعلان الرسمي على YouTube" else "Official Trailer on YouTube"
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.14f))
                        .border(1.dp, palette.glassBorder, RoundedCornerShape(10.dp))
                        .clickable(onClick = onRequestTrailerPlay)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("reels_trailer_action")
                ) {
                    Text(
                        text = trailerChipText,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Primary Watch Now + Full Details Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onWatchNow,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.primary,
                        contentColor = palette.onPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("reels_watch_now_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isArabic) "شاهد الآن" else "Watch Now",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp
                    )
                }

                OutlinedButton(
                    onClick = onOpenDetails,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.glassBorder),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("reels_full_details_button")
                ) {
                    Text(
                        text = if (isArabic) "التفاصيل" else "Full Details",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ReelSideActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    highlighted: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    testTagName: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(onClick = onClick)
            .testTag(testTagName)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (highlighted) accentColor.copy(alpha = 0.85f)
                    else Color.Black.copy(alpha = 0.56f)
                )
                .border(
                    1.dp,
                    if (highlighted) Color.White.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.25f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Official YouTube IFrame Embed player (respects YouTube Terms of Service,
 * never downloads or extracts raw media URLs, and pauses/destroys cleanly on dispose).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OfficialYoutubeTrailerEmbed(
    videoKey: String,
    isMuted: Boolean,
    modifier: Modifier = Modifier
) {
    val sanitizedKey = remember(videoKey) {
        videoKey.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "")
    }
    if (sanitizedKey.isBlank()) return

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(isMuted, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        val cmd = if (isMuted) "mute" else "unMute"
        wv.evaluateJavascript(
            "try { document.querySelector('iframe')?.contentWindow?.postMessage('{\"event\":\"command\",\"func\":\"$cmd\",\"args\":\"\"}','*'); } catch(e) {}",
            null
        )
    }

    DisposableEffect(sanitizedKey) {
        onDispose {
            webViewRef?.apply {
                onPause()
                loadUrl("about:blank")
                destroy()
            }
            webViewRef = null
        }
    }

    val embedHtml = remember(sanitizedKey) {
        val muteParam = if (isMuted) 1 else 0
        """
        <!DOCTYPE html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
          <style>
            html, body { margin: 0; padding: 0; width: 100%; height: 100%; background: #000; overflow: hidden; }
            iframe { width: 100%; height: 100%; border: 0; }
          </style>
        </head>
        <body>
          <iframe
            src="https://www.youtube-nocookie.com/embed/$sanitizedKey?autoplay=1&mute=$muteParam&playsinline=1&rel=0&modestbranding=1&controls=0&enablejsapi=1"
            allow="autoplay; encrypted-media; picture-in-picture"
            allowfullscreen>
          </iframe>
        </body>
        </html>
        """.trimIndent()
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val host = request?.url?.host.orEmpty()
                        return !(host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com"))
                    }
                }
                loadDataWithBaseURL("https://www.youtube-nocookie.com", embedHtml, "text/html", "utf-8", null)
                webViewRef = this
            }
        },
        modifier = modifier
    )
}

private fun openOfficialYoutubeTrailerSearch(context: Context, item: MediaItem) {
    try {
        val query = buildString {
            append(item.name)
            if (item.year.isNotBlank()) {
                append(" ")
                append(item.year)
            }
            append(" official trailer")
        }
        val uri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

private fun isWifiConnected(context: Context): Boolean {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    } catch (_: Exception) {
        false
    }
}
