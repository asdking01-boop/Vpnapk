package com.example.ui.screens

import android.animation.ValueAnimator
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.components.KrasLogo
import com.example.ui.theme.KrasRed
import com.example.ui.theme.KrasRedBright
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * KRAS TV Ultra-Premium 3D Glass & Metallic Silver Cinematic Splash Screen
 *
 * Timeline (2.2s total, zero artificial loading delay):
 * - 0.0–0.3s (0–300ms): Pure black screen (#000000).
 * - 0.3–0.8s (300–800ms): Reveal the 3D glass K symbol in center with subtle zoom, fade, and volumetric red glow.
 * - 0.8–1.3s (800–1300ms): Animate a cinematic red & metallic silver light sweep across the 3D glass K symbol.
 * - 1.3–1.8s (1300–1800ms): Reveal the full KRAS TV wordmark with elegant reflective lighting as the SAME K
 *   glides into its first-letter slot.
 * - 1.8–2.2s (1800–2200ms): Smoothly transition directly to the preloaded Home screen.
 *
 * Respects system reduced-motion accessibility settings and supports tap-to-skip.
 */
@Composable
fun SplashScreen(
    onSplashFinished: () -> Unit,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Detect system reduced-motion setting
    val isReducedMotion = remember(context) {
        try {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
            val animatorsEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ValueAnimator.areAnimatorsEnabled()
            } else {
                true
            }
            scale <= 0.05f || !animatorsEnabled
        } catch (_: Exception) {
            false
        }
    }

    // Measurement state so the single K element animates from exact screen center to its wordmark slot
    var isMeasured by remember { mutableStateOf(false) }
    var screenCenter by remember { mutableStateOf(Offset.Zero) }
    var kSlotCenter by remember { mutableStateOf(Offset.Zero) }

    // Phase 2 (0.3–0.8s): 3D Glass K reveal & volumetric red glow
    val kInitialZoom = remember { Animatable(0.80f) }
    val kAlpha = remember { Animatable(0f) }
    val step1GlowAlpha = remember { Animatable(0f) }
    val step1GlowScale = remember { Animatable(0.80f) }

    // Phase 3 (0.8–1.3s): Cinematic red & metallic silver light sweep across 3D glass K + volumetric rays
    val sweepProgress = remember { Animatable(0f) }
    val streakProgress = remember { Animatable(0f) }

    // Phase 4 (1.3–1.8s): Full KRAS TV wordmark reveal with reflective lighting
    val slideProgress = remember { Animatable(0f) }
    val wordSweepProgress = remember { Animatable(0f) }
    val letterRAlpha = remember { Animatable(0f) }
    val letterROffset = remember { Animatable(14f) }
    val letterAAlpha = remember { Animatable(0f) }
    val letterAOffset = remember { Animatable(14f) }
    val letterSAlpha = remember { Animatable(0f) }
    val letterSOffset = remember { Animatable(14f) }
    val letterTAlpha = remember { Animatable(0f) }
    val letterTOffset = remember { Animatable(14f) }
    val letterVAlpha = remember { Animatable(0f) }
    val letterVOffset = remember { Animatable(14f) }
    val wordGlowAlpha = remember { Animatable(0f) }
    val wordGlowScale = remember { Animatable(0.88f) }

    // Phase 5 (1.8–2.2s): Smooth transition into Home screen
    val fadeAlpha = remember { Animatable(1f) }
    val exitScale = remember { Animatable(1f) }

    var hasFinished by remember { mutableStateOf(false) }

    fun finishSplash() {
        if (!hasFinished) {
            hasFinished = true
            onSplashFinished()
        }
    }

    LaunchedEffect(isMeasured, isReducedMotion) {
        if (!isMeasured) return@LaunchedEffect

        // Shortened accessible path when system reduced-motion is enabled
        if (isReducedMotion) {
            slideProgress.snapTo(1f)
            kInitialZoom.snapTo(1f)
            kAlpha.snapTo(1f)
            letterRAlpha.snapTo(1f)
            letterROffset.snapTo(0f)
            letterAAlpha.snapTo(1f)
            letterAOffset.snapTo(0f)
            letterSAlpha.snapTo(1f)
            letterSOffset.snapTo(0f)
            letterTAlpha.snapTo(1f)
            letterTOffset.snapTo(0f)
            letterVAlpha.snapTo(1f)
            letterVOffset.snapTo(0f)
            wordGlowAlpha.snapTo(0.45f)
            delay(220)
            fadeAlpha.animateTo(0f, tween(durationMillis = 180, easing = LinearOutSlowInEasing))
            finishSplash()
            return@LaunchedEffect
        }

        // =====================================================================
        // 0.0–0.3s (300ms): Display a pure black screen (#000000)
        // =====================================================================
        delay(300)

        // =====================================================================
        // 0.3–0.8s (500ms): Reveal the 3D glass K symbol with subtle zoom & fade
        // =====================================================================
        launch {
            kAlpha.animateTo(1f, tween(durationMillis = 360, easing = FastOutSlowInEasing))
        }
        launch {
            step1GlowAlpha.animateTo(1f, tween(durationMillis = 480, easing = FastOutSlowInEasing))
        }
        launch {
            step1GlowScale.animateTo(1.12f, tween(durationMillis = 500, easing = FastOutSlowInEasing))
        }
        kInitialZoom.animateTo(1.04f, tween(durationMillis = 360, easing = FastOutSlowInEasing))
        kInitialZoom.animateTo(1.00f, tween(durationMillis = 140, easing = LinearOutSlowInEasing))

        // =====================================================================
        // 0.8–1.3s (500ms): Animate a cinematic red & silver light sweep across the K
        // =====================================================================
        launch {
            streakProgress.animateTo(1f, tween(durationMillis = 500, easing = FastOutSlowInEasing))
        }
        sweepProgress.animateTo(1f, tween(durationMillis = 500, easing = FastOutSlowInEasing))

        // =====================================================================
        // 1.3–1.8s (500ms): Reveal full KRAS TV wordmark with elegant reflective lighting
        // =====================================================================
        launch {
            step1GlowAlpha.animateTo(0f, tween(durationMillis = 220, easing = FastOutSlowInEasing))
        }
        launch {
            slideProgress.animateTo(1f, tween(durationMillis = 460, easing = FastOutSlowInEasing))
        }
        launch {
            wordGlowAlpha.animateTo(0.92f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
            wordGlowAlpha.animateTo(0.50f, tween(durationMillis = 240, easing = LinearOutSlowInEasing))
        }
        launch {
            wordGlowScale.animateTo(1.14f, tween(durationMillis = 500, easing = FastOutSlowInEasing))
        }
        launch {
            delay(40)
            wordSweepProgress.animateTo(1f, tween(durationMillis = 460, easing = FastOutSlowInEasing))
        }

        // Per-letter 3D glass reveal ("R", "A", "S", "T", "V")
        launch {
            delay(35)
            launch { letterRAlpha.animateTo(1f, tween(210, easing = FastOutSlowInEasing)) }
            letterROffset.animateTo(0f, tween(210, easing = FastOutSlowInEasing))
        }
        launch {
            delay(80)
            launch { letterAAlpha.animateTo(1f, tween(210, easing = FastOutSlowInEasing)) }
            letterAOffset.animateTo(0f, tween(210, easing = FastOutSlowInEasing))
        }
        launch {
            delay(125)
            launch { letterSAlpha.animateTo(1f, tween(210, easing = FastOutSlowInEasing)) }
            letterSOffset.animateTo(0f, tween(210, easing = FastOutSlowInEasing))
        }
        launch {
            delay(170)
            launch { letterTAlpha.animateTo(1f, tween(210, easing = FastOutSlowInEasing)) }
            letterTOffset.animateTo(0f, tween(210, easing = FastOutSlowInEasing))
        }
        launch {
            delay(215)
            launch { letterVAlpha.animateTo(1f, tween(210, easing = FastOutSlowInEasing)) }
            letterVOffset.animateTo(0f, tween(210, easing = FastOutSlowInEasing))
        }

        // Wait until 1.8s mark (500ms elapsed in Phase 4)
        delay(500)

        // =====================================================================
        // 1.8–2.2s (400ms): Smoothly transition directly to the existing Home screen
        // =====================================================================
        launch {
            exitScale.animateTo(1.04f, tween(durationMillis = 400, easing = FastOutSlowInEasing))
        }
        fadeAlpha.animateTo(0f, tween(durationMillis = 400, easing = FastOutSlowInEasing))
        finishSplash()
    }

    // Full-screen pure black (#000000) container with tap-to-skip
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = fadeAlpha.value
                scaleX = exitScale.value
                scaleY = exitScale.value
            }
            .background(Color.Black)
            .onGloballyPositioned { rootCoordinates ->
                screenCenter = Offset(
                    rootCoordinates.size.width / 2f,
                    rootCoordinates.size.height / 2f
                )
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                finishSplash()
            }
            .testTag("splash_screen"),
        contentAlignment = Alignment.Center
    ) {
        // Volumetric Red & Silver Atmospheric Glow behind centered 3D K (0.3–1.3s)
        if (step1GlowAlpha.value > 0.01f) {
            Canvas(
                modifier = Modifier
                    .size(440.dp)
                    .graphicsLayer(
                        scaleX = step1GlowScale.value,
                        scaleY = step1GlowScale.value,
                        alpha = step1GlowAlpha.value
                    )
            ) {
                val radius = size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            KrasRedBright.copy(alpha = 0.46f),
                            KrasRed.copy(alpha = 0.22f),
                            Color(0xFF64748B).copy(alpha = 0.06f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
            }
        }

        // Volumetric Red Crystal Light Streaks (0.8–1.3s)
        RibbonLightStreaks(
            progress = streakProgress.value,
            modifier = Modifier.size(420.dp)
        )

        // Volumetric Red & Metallic Halo behind full "KRAS TV" wordmark (1.3–2.2s)
        if (wordGlowAlpha.value > 0.01f) {
            Canvas(
                modifier = Modifier
                    .size(480.dp, 240.dp)
                    .graphicsLayer(
                        scaleX = wordGlowScale.value,
                        scaleY = wordGlowScale.value,
                        alpha = wordGlowAlpha.value
                    )
            ) {
                val radius = size.width / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            KrasRed.copy(alpha = 0.50f),
                            KrasRed.copy(alpha = 0.18f),
                            Color(0xFF94A3B8).copy(alpha = 0.05f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
            }
        }

        // --- SINGLE UNIFIED 3D BRANDING LOCKUP: [K] [RAS TV] ---
        // Before slide (slideProgress = 0): The single K is translated to screenCenter and scaled 4.0x.
        // During 1.3–1.8s (slideProgress 0 -> 1): The SAME K smoothly scales to 1.0x and settles
        // into its first-letter slot while "RAS TV" reveals with a metallic silver & red sweep.
        val s = slideProgress.value
        val bigKScale = 4.0f * (if (s == 0f) kInitialZoom.value else 1f)
        val currentKScale = bigKScale * (1f - s) + 1.0f * s

        val deltaX = if (isMeasured) screenCenter.x - kSlotCenter.x else 0f
        val deltaY = if (isMeasured) screenCenter.y - kSlotCenter.y else 0f
        val currentTransX = deltaX * (1f - s)
        val currentTransY = deltaY * (1f - s)

        KrasLogo(
            height = 40.dp,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 16.dp),
            kModifier = Modifier
                .graphicsLayer {
                    scaleX = currentKScale
                    scaleY = currentKScale
                    translationX = currentTransX
                    translationY = currentTransY
                    this.alpha = if (isMeasured) kAlpha.value else 0f
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    // 0.8–1.3s: Cinematic Red & Metallic Silver Light Sweep clipped strictly to the 3D K glyph
                    val sp = sweepProgress.value
                    if (sp > 0.01f && sp < 0.99f) {
                        val w = size.width
                        val h = size.height
                        val travel = (sp * (w * 2.8f)) - (w * 0.9f)
                        val beamWidth = w * 0.52f
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    KrasRedBright.copy(alpha = 0.55f),
                                    Color(0xFFF8FAFC).copy(alpha = 0.95f),
                                    Color(0xFFCBD5E1).copy(alpha = 0.65f),
                                    KrasRed.copy(alpha = 0.45f),
                                    Color.Transparent
                                ),
                                start = Offset(travel, 0f),
                                end = Offset(travel + beamWidth, h)
                            ),
                            blendMode = BlendMode.SrcAtop
                        )
                    }
                },
            wordSweepProgress = wordSweepProgress.value,
            onKPositioned = { kCoords ->
                val pos = kCoords.positionInRoot()
                kSlotCenter = Offset(
                    pos.x + kCoords.size.width / 2f,
                    pos.y + kCoords.size.height / 2f
                )
                isMeasured = true
            },
            letterAlphaProvider = { ch ->
                when (ch) {
                    'R' -> letterRAlpha.value
                    'A' -> letterAAlpha.value
                    'S' -> letterSAlpha.value
                    'T' -> letterTAlpha.value
                    'V' -> letterVAlpha.value
                    else -> 0f
                }
            },
            letterOffsetProvider = { ch ->
                when (ch) {
                    'R' -> letterROffset.value
                    'A' -> letterAOffset.value
                    'S' -> letterSOffset.value
                    'T' -> letterTOffset.value
                    'V' -> letterVOffset.value
                    else -> 0f
                }
            }
        )
    }
}

/**
 * Subtle volumetric red & silver light streaks fanning behind the 3D K during the 0.8–1.3s light sweep.
 */
@Composable
private fun RibbonLightStreaks(
    progress: Float,
    modifier: Modifier = Modifier
) {
    if (progress <= 0f || progress >= 1f) return

    val alpha = if (progress < 0.35f) {
        (progress / 0.35f)
    } else {
        (1f - (progress - 0.35f) / 0.65f)
    }.coerceIn(0f, 1f)

    val beamLengthRatio = (0.38f + 0.62f * progress)

    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val maxBeamLength = size.width.coerceAtLeast(size.height) * 0.82f * beamLengthRatio

        val beamAngles = listOf(-36f, -22f, -10f, 0f, 10f, 22f, 36f)
        val beamWidths = listOf(8f, 12f, 16f, 20f, 16f, 12f, 8f)

        for (i in beamAngles.indices) {
            val angleDeg = beamAngles[i]
            val bWidth = beamWidths[i] * (0.8f + 0.35f * (1f - progress))

            rotate(degrees = angleDeg, pivot = Offset(centerX, centerY)) {
                // Primary upward volumetric ray
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            KrasRedBright.copy(alpha = 0.58f * alpha),
                            Color(0xFFCBD5E1).copy(alpha = 0.22f * alpha),
                            KrasRed.copy(alpha = 0.42f * alpha),
                            Color.Transparent
                        ),
                        startY = centerY - maxBeamLength,
                        endY = centerY
                    ),
                    topLeft = Offset(centerX - bWidth / 2f, centerY - maxBeamLength),
                    size = Size(bWidth, maxBeamLength)
                )

                // Secondary downward volumetric ray
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            KrasRed.copy(alpha = 0.38f * alpha),
                            Color.Transparent
                        ),
                        startY = centerY,
                        endY = centerY + maxBeamLength * 0.68f
                    ),
                    topLeft = Offset(centerX - bWidth * 0.4f, centerY),
                    size = Size(bWidth * 0.8f, maxBeamLength * 0.68f)
                )
            }
        }
    }
}
