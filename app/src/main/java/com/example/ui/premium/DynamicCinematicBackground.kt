package com.example.ui.premium

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.example.ui.components.DynamicAmbientPosterBackdrop

/**
 * KRAS TV — Cinematic Premium 2.0 Dynamic Cinematic Background.
 *
 * Enhances Home, Movies, and Series screens with:
 * - High-resolution TMDb backdrops / posters bounded in memory (640x360 Coil cache)
 * - Adaptive dominant & vibrant colors extracted off the main thread and cached in LruCache
 * - Smooth crossfade transitions between selected artwork
 * - Subtle GPU-only parallax translation linked to vertical scroll offset
 * - Cinematic gradient scrims guaranteeing crisp text readability
 * - Automatic fallback to theme palette gradient when artwork is unavailable
 * - Zero continuous animations; respects Battery Saving Mode & Classic fallback
 */
@Composable
fun CinematicScreenBackdrop(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    scrollOffsetPx: Float = 0f,
    ambientAlpha: Float = 0.34f
) {
    val experience = LocalPremiumExperienceState.current

    // Preserve exact Classic Original backdrop behavior whenever not in Premium Cinematic mode
    if (!experience.isPremiumStyle) {
        DynamicAmbientPosterBackdrop(
            imageUrl = imageUrl,
            modifier = modifier,
            ambientAlpha = ambientAlpha.coerceAtMost(0.28f)
        )
        return
    }

    val palette = experience.palette

    // When Cinematic Backgrounds toggle is disabled or Battery Saving Mode is active:
    // render a crisp static theme backdrop with zero blur/image overhead
    if (!experience.cinematicBackgroundsActive) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .drawWithCache {
                    val gradient = Brush.verticalGradient(
                        0.0f to palette.gradientTop.copy(alpha = if (palette.isPureOled) 0.25f else 0.55f),
                        0.45f to palette.background,
                        1.0f to palette.background
                    )
                    onDrawBehind { drawRect(gradient) }
                }
        )
        return
    }

    val context = LocalContext.current
    var extractedColors by remember(palette.ambientDefault) {
        mutableStateOf(
            ArtworkColorExtractor.getCached(imageUrl) ?: ExtractedArtworkColors(
                dominant = palette.ambientDefault,
                vibrant = palette.primary,
                darkMuted = palette.background
            )
        )
    }

    LaunchedEffect(imageUrl, palette.ambientDefault) {
        extractedColors = ArtworkColorExtractor.extractFromUrl(
            context = context,
            imageUrl = imageUrl,
            fallbackColor = palette.ambientDefault
        )
    }

    val animatedDominant by animateColorAsState(
        targetValue = extractedColors.dominant,
        animationSpec = if (experience.animationsEnabled) {
            tween(durationMillis = 650, easing = FastOutSlowInEasing)
        } else {
            snap()
        },
        label = "cinematic_bg_dominant"
    )
    val animatedVibrant by animateColorAsState(
        targetValue = extractedColors.vibrant,
        animationSpec = if (experience.animationsEnabled) {
            tween(durationMillis = 700, easing = FastOutSlowInEasing)
        } else {
            snap()
        },
        label = "cinematic_bg_vibrant"
    )

    val useBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val parallaxShift = remember(scrollOffsetPx, experience.animationsEnabled) {
        if (!experience.animationsEnabled) 0f
        else -(scrollOffsetPx * 0.14f).coerceIn(0f, 120f)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        // 1. Crossfaded Artwork Layer with subtle scroll parallax
        Crossfade(
            targetState = imageUrl.orEmpty(),
            animationSpec = if (experience.animationsEnabled) {
                tween(durationMillis = 550, easing = FastOutSlowInEasing)
            } else {
                snap()
            },
            label = "cinematic_bg_artwork_crossfade"
        ) { targetUrl ->
            if (targetUrl.isNotBlank()) {
                val request = remember(targetUrl, context) {
                    ImageRequest.Builder(context)
                        .data(targetUrl)
                        .size(640, 360)
                        .precision(Precision.INEXACT)
                        .crossfade(false)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = if (palette.isPureOled) ambientAlpha * 0.72f else ambientAlpha
                            scaleX = 1.18f
                            scaleY = 1.18f
                            translationY = parallaxShift
                        }
                        .then(if (useBlur) Modifier.blur(42.dp) else Modifier)
                )
            }
        }

        // 2. Adaptive Color Atmosphere + High-Readability Cinematic Scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val topGlow = Brush.radialGradient(
                        colors = listOf(
                            animatedVibrant.copy(alpha = if (palette.isPureOled) 0.22f else 0.34f),
                            animatedDominant.copy(alpha = if (palette.isPureOled) 0.12f else 0.22f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.5f, size.height * 0.14f),
                        radius = size.width.coerceAtLeast(1f) * 1.1f
                    )
                    val readabilityScrim = Brush.verticalGradient(
                        0.0f to palette.background.copy(alpha = 0.26f),
                        0.32f to palette.background.copy(alpha = 0.68f),
                        0.68f to palette.background.copy(alpha = 0.92f),
                        1.0f to palette.background
                    )
                    onDrawBehind {
                        drawRect(topGlow)
                        drawRect(readabilityScrim)
                    }
                }
        )
    }
}
