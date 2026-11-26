package com.example.ui.premium

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.example.data.model.AmbientCinemaIntensity
import kotlinx.coroutines.delay

/**
 * KRAS TV — Ambient Cinema Mode Backdrop for Video Playback.
 *
 * Design & Safety Guarantees:
 * - Renders soft dynamic theater lighting around the video canvas using GPU radial gradients.
 * - NEVER captures or accesses protected hardware video surfaces (MediaCodec / DRM-safe).
 * - NEVER interferes with hardware decoding, subtitles, audio tracks, aspect ratio, or fullscreen.
 * - Extracts adaptive cinema colors from movie/series artwork off the main thread (cached in LruCache)
 *   and shifts softly at low frequency (every 2.6 seconds while playing) to avoid GPU churn.
 * - Automatically disables when Battery Saving Mode or low-motion mode is active.
 */
@Composable
fun AmbientCinemaBackdrop(
    enabled: Boolean,
    intensity: AmbientCinemaIntensity,
    artworkUrl: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val experience = LocalPremiumExperienceState.current
    if (!enabled || experience.batterySavingActive) return

    val context = LocalContext.current
    val fallbackColor = experience.palette.ambientDefault

    var extracted by remember(artworkUrl, fallbackColor) {
        mutableStateOf(
            ArtworkColorExtractor.getCached(artworkUrl) ?: ExtractedArtworkColors(
                dominant = fallbackColor,
                vibrant = experience.palette.primary,
                darkMuted = Color.Black
            )
        )
    }

    LaunchedEffect(artworkUrl, fallbackColor) {
        extracted = ArtworkColorExtractor.extractFromUrl(
            context = context,
            imageUrl = artworkUrl,
            fallbackColor = fallbackColor
        )
    }

    // Low-frequency cinema scene aura phase (changes gently every 2.6s only while actively playing)
    var auraPhase by remember { mutableIntStateOf(0) }
    LaunchedEffect(isPlaying, experience.animationsEnabled) {
        if (isPlaying && experience.animationsEnabled) {
            while (true) {
                delay(2600L)
                auraPhase = (auraPhase + 1) % 3
            }
        }
    }

    val targetTopColor = when (auraPhase) {
        0 -> extracted.vibrant
        1 -> extracted.dominant
        else -> blendColors(extracted.vibrant, extracted.dominant, 0.5f)
    }
    val targetBottomColor = when (auraPhase) {
        0 -> extracted.dominant
        1 -> extracted.vibrant
        else -> extracted.darkMuted
    }

    val topGlowColor by animateColorAsState(
        targetValue = targetTopColor,
        animationSpec = if (experience.animationsEnabled) {
            tween(durationMillis = 1800, easing = FastOutSlowInEasing)
        } else {
            snap()
        },
        label = "ambient_cinema_top_color"
    )
    val bottomGlowColor by animateColorAsState(
        targetValue = targetBottomColor,
        animationSpec = if (experience.animationsEnabled) {
            tween(durationMillis = 1800, easing = FastOutSlowInEasing)
        } else {
            snap()
        },
        label = "ambient_cinema_bottom_color"
    )

    val effectiveAlpha by animateFloatAsState(
        targetValue = (intensity.alphaMultiplier * if (isPlaying) 0.52f else 0.30f).coerceIn(0.08f, 0.55f),
        animationSpec = if (experience.animationsEnabled) tween(600) else snap(),
        label = "ambient_cinema_alpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("ambient_cinema_overlay")
            .drawWithCache {
                val w = size.width.coerceAtLeast(1f)
                val h = size.height.coerceAtLeast(1f)
                val topAura = Brush.radialGradient(
                    colors = listOf(
                        topGlowColor.copy(alpha = effectiveAlpha),
                        topGlowColor.copy(alpha = effectiveAlpha * 0.35f),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.5f, h * 0.04f),
                    radius = maxOf(w, h) * 0.68f
                )
                val bottomAura = Brush.radialGradient(
                    colors = listOf(
                        bottomGlowColor.copy(alpha = effectiveAlpha * 0.92f),
                        bottomGlowColor.copy(alpha = effectiveAlpha * 0.30f),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.5f, h * 0.96f),
                    radius = maxOf(w, h) * 0.68f
                )
                val sideLeftAura = Brush.radialGradient(
                    colors = listOf(
                        topGlowColor.copy(alpha = effectiveAlpha * 0.48f),
                        Color.Transparent
                    ),
                    center = Offset(0f, h * 0.5f),
                    radius = w * 0.42f
                )
                val sideRightAura = Brush.radialGradient(
                    colors = listOf(
                        bottomGlowColor.copy(alpha = effectiveAlpha * 0.48f),
                        Color.Transparent
                    ),
                    center = Offset(w, h * 0.5f),
                    radius = w * 0.42f
                )
                onDrawBehind {
                    drawRect(Color.Black)
                    drawRect(topAura)
                    drawRect(bottomAura)
                    drawRect(sideLeftAura)
                    drawRect(sideRightAura)
                }
            }
    )
}

/**
 * Subtle feathered perimeter cinema aura drawn along the extreme outer edges of the player frame
 * during fullscreen playback (100% transparent center so video clarity, subtitles, and hardware
 * decoding are never obscured or captured).
 */
@Composable
fun AmbientCinemaEdgeAura(
    enabled: Boolean,
    intensity: AmbientCinemaIntensity,
    artworkUrl: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val experience = LocalPremiumExperienceState.current
    if (!enabled || experience.batterySavingActive) return

    val context = LocalContext.current
    val fallbackColor = experience.palette.ambientDefault

    var extracted by remember(artworkUrl, fallbackColor) {
        mutableStateOf(
            ArtworkColorExtractor.getCached(artworkUrl) ?: ExtractedArtworkColors(
                dominant = fallbackColor,
                vibrant = experience.palette.primary,
                darkMuted = Color.Black
            )
        )
    }

    LaunchedEffect(artworkUrl, fallbackColor) {
        extracted = ArtworkColorExtractor.extractFromUrl(
            context = context,
            imageUrl = artworkUrl,
            fallbackColor = fallbackColor
        )
    }

    val edgeColor by animateColorAsState(
        targetValue = extracted.vibrant,
        animationSpec = if (experience.animationsEnabled) tween(1400, easing = FastOutSlowInEasing) else snap(),
        label = "ambient_edge_color"
    )
    val edgeAlpha by animateFloatAsState(
        targetValue = (intensity.alphaMultiplier * if (isPlaying) 0.24f else 0.14f).coerceIn(0.05f, 0.26f),
        animationSpec = if (experience.animationsEnabled) tween(500) else snap(),
        label = "ambient_edge_alpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val topEdge = Brush.verticalGradient(
                    0.0f to edgeColor.copy(alpha = edgeAlpha),
                    0.12f to Color.Transparent,
                    0.88f to Color.Transparent,
                    1.0f to edgeColor.copy(alpha = edgeAlpha * 0.85f)
                )
                val sideEdge = Brush.horizontalGradient(
                    0.0f to edgeColor.copy(alpha = edgeAlpha * 0.72f),
                    0.08f to Color.Transparent,
                    0.92f to Color.Transparent,
                    1.0f to edgeColor.copy(alpha = edgeAlpha * 0.72f)
                )
                onDrawBehind {
                    drawRect(topEdge)
                    drawRect(sideEdge)
                }
            }
    )
}

private fun blendColors(a: Color, b: Color, ratio: Float): Color {
    val t = ratio.coerceIn(0f, 1f)
    return Color(
        red = a.red * (1f - t) + b.red * t,
        green = a.green * (1f - t) + b.green * t,
        blue = a.blue * (1f - t) + b.blue * t,
        alpha = 1f
    )
}
