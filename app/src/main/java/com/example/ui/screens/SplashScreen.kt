package com.example.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.ui.components.KrasLogo
import com.example.ui.theme.CinemaBlack
import com.example.ui.theme.KrasRed
import com.example.ui.theme.KrasRedBright
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Kras TV Netflix-Style Cinematic Opening Animation
 *
 * Requirements:
 * 1. ONLY ONE K element in the entire animation. The same K composable animates from the
 *    center of the screen down to its final position as the first letter of "KRAS TV".
 * 2. Sequence:
 *    a) Glossy red K appears large in the center (zoom-in, soft red glow, specular light sweep,
 *       thin red ribbon light streaks).
 *    b) The SAME K smoothly scales down and moves to the left into its final slot over ~700ms
 *       (FastOutSlowInEasing), while "RAS TV" appears right next to it letter by letter
 *       (fade + slight rise, 60ms delay per letter).
 *    c) At the end, K and "RAS TV" form one centered word "KRAS TV", with identical height,
 *       weight, red gradient, and glass effect.
 *    d) One soft red glow pulse, hold ~0.5s, then fade smoothly into Home.
 * 3. Exact measurement: Row("KRAS TV") is rendered in the center with alpha=0 for letters initially.
 *    Target position and delta are measured, and K animates from (screenCenter - kSlotCenter) down to (0, 0).
 * 4. Specular light sweep clipped strictly to the K letter shape (no bounding box/rectangle visible).
 *    Soft radial glow that fades to transparent with no hard edge. Tap to skip enabled.
 */
@Composable
fun SplashScreen(
    onSplashFinished: () -> Unit,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Measurement state
    var isMeasured by remember { mutableStateOf(false) }
    var screenCenter by remember { mutableStateOf(Offset.Zero) }
    var kSlotCenter by remember { mutableStateOf(Offset.Zero) }

    // Step 1: Initial Zoom and Ambient Glow
    val kInitialZoom = remember { Animatable(0.72f) }
    val kAlpha = remember { Animatable(0f) }
    val step1GlowAlpha = remember { Animatable(0f) }
    val step1GlowScale = remember { Animatable(0.75f) }

    // Step 2: Light sweep across K and ribbon streaks
    val sweepProgress = remember { Animatable(0f) }
    val streakProgress = remember { Animatable(0f) }

    // Step 3: Slide & Scale from center to final Row position, letters "RAS TV" reveal
    val slideProgress = remember { Animatable(0f) }
    val letterRAlpha = remember { Animatable(0f) }
    val letterROffset = remember { Animatable(16f) }
    val letterAAlpha = remember { Animatable(0f) }
    val letterAOffset = remember { Animatable(16f) }
    val letterSAlpha = remember { Animatable(0f) }
    val letterSOffset = remember { Animatable(16f) }
    val letterTAlpha = remember { Animatable(0f) }
    val letterTOffset = remember { Animatable(16f) }
    val letterVAlpha = remember { Animatable(0f) }
    val letterVOffset = remember { Animatable(16f) }

    // Step 4: Glow pulse and final fade
    val wordGlowAlpha = remember { Animatable(0f) }
    val wordGlowScale = remember { Animatable(0.85f) }
    val fadeAlpha = remember { Animatable(1f) }

    var hasFinished by remember { mutableStateOf(false) }

    fun finishSplash() {
        if (!hasFinished) {
            hasFinished = true
            onSplashFinished()
        }
    }

    // Sequence runs once layout is measured to ensure exact pixel coordinates
    LaunchedEffect(isMeasured) {
        if (!isMeasured) return@LaunchedEffect

        // --- STEP 1: Big K appears in center with quick zoom-in + soft radial glow (~0-600ms) ---
        launch {
            kAlpha.animateTo(1f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
        }
        launch {
            step1GlowAlpha.animateTo(1f, tween(durationMillis = 450, easing = FastOutSlowInEasing))
            step1GlowScale.animateTo(1.15f, tween(durationMillis = 550, easing = FastOutSlowInEasing))
        }
        kInitialZoom.animateTo(1.06f, tween(durationMillis = 450, easing = FastOutSlowInEasing))
        kInitialZoom.animateTo(1.00f, tween(durationMillis = 150, easing = LinearOutSlowInEasing))

        // --- STEP 2: Specular light sweep across K + fanning red ribbon streaks (~600-1250ms) ---
        launch {
            streakProgress.animateTo(1f, tween(durationMillis = 650, easing = FastOutSlowInEasing))
        }
        sweepProgress.animateTo(1f, tween(durationMillis = 550, easing = FastOutSlowInEasing))

        // Fade out Step 1 ambient glow
        launch {
            step1GlowAlpha.animateTo(0f, tween(durationMillis = 250))
        }
        delay(60)

        // --- STEP 3: SAME K scales down and slides from center to final slot (~1250-1950ms, 700ms) ---
        launch {
            slideProgress.animateTo(1f, tween(durationMillis = 700, easing = FastOutSlowInEasing))
        }

        // Letters "RAS TV" appear right next to K letter by letter (60ms delay per letter, fade + slight rise)
        launch {
            delay(60)
            launch { letterRAlpha.animateTo(1f, tween(220)) }
            letterROffset.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
        }
        launch {
            delay(120)
            launch { letterAAlpha.animateTo(1f, tween(220)) }
            letterAOffset.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
        }
        launch {
            delay(180)
            launch { letterSAlpha.animateTo(1f, tween(220)) }
            letterSOffset.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
        }
        launch {
            delay(240)
            launch { letterTAlpha.animateTo(1f, tween(220)) }
            letterTOffset.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
        }
        launch {
            delay(300)
            launch { letterVAlpha.animateTo(1f, tween(220)) }
            letterVOffset.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
        }

        // Wait for slide & letter animations to fully complete
        delay(720)

        // --- STEP 4: Soft red glow pulse behind full word "KRAS TV", hold 0.5s, then fade (~1950-2800ms) ---
        launch {
            wordGlowAlpha.animateTo(0.95f, tween(240, easing = FastOutSlowInEasing))
            wordGlowAlpha.animateTo(0.35f, tween(260, easing = LinearOutSlowInEasing))
        }
        wordGlowScale.animateTo(1.20f, tween(240, easing = FastOutSlowInEasing))
        wordGlowScale.animateTo(1.00f, tween(260, easing = LinearOutSlowInEasing))

        // Hold for half a second
        delay(500)

        // Non-blocking: keep last frame visible if data is still loading
        while (isLoading) {
            delay(100)
        }

        // Smooth fade out into Home screen
        fadeAlpha.animateTo(0f, tween(durationMillis = 350, easing = FastOutSlowInEasing))
        finishSplash()
    }

    // Full screen pure black container with tap-to-skip
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
            .alpha(fadeAlpha.value)
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
                // Tap anywhere to skip intro immediately
                finishSplash()
            }
            .testTag("splash_screen"),
        contentAlignment = Alignment.Center
    ) {
        // Step 1: Soft red radial glow behind big centered K (fades to transparent, no hard edge)
        if (step1GlowAlpha.value > 0.01f) {
            Canvas(
                modifier = Modifier
                    .size(420.dp)
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
                            KrasRed.copy(alpha = 0.50f),
                            KrasRed.copy(alpha = 0.18f),
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

        // Step 2: Red ribbon light streaks fanning out behind K
        RibbonLightStreaks(
            progress = streakProgress.value,
            modifier = Modifier.size(420.dp)
        )

        // Step 4: Pulsing soft red glow behind "KRAS TV"
        if (wordGlowAlpha.value > 0.01f) {
            Canvas(
                modifier = Modifier
                    .size(460.dp, 240.dp)
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
                            KrasRed.copy(alpha = 0.55f),
                            KrasRed.copy(alpha = 0.20f),
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

        // --- BRANDING LOCKUP: ONE ROW [K] [RAS TV] ---
        // The Row is centered in the screen.
        // The K slot is measured on first pass to find its final position (kSlotCenter).
        // Before slide (slideProgress = 0): K is translated by (screenCenter - kSlotCenter) and scaled 4.2x
        // so it appears large in the center of the screen.
        // During slide (slideProgress 0 -> 1): K smoothly scales to 1.0x and moves to (0, 0),
        // settling seamlessly into the first letter slot with zero jump or overlap!
        val s = slideProgress.value
        val bigKScale = 4.2f * (if (s == 0f) kInitialZoom.value else 1f)
        val currentKScale = bigKScale * (1f - s) + 1.0f * s

        val deltaX = if (isMeasured) screenCenter.x - kSlotCenter.x else 0f
        val deltaY = if (isMeasured) screenCenter.y - kSlotCenter.y else 0f
        val currentTransX = deltaX * (1f - s)
        val currentTransY = deltaY * (1f - s)

        // Canonical Kras TV Wordmark: The SAME K animates from center down to its final slot
        KrasLogo(
            height = 36.dp,
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
                    val sp = sweepProgress.value
                    if (sp > 0f && sp < 1f) {
                        val w = size.width
                        val h = size.height
                        val travel = (sp * (w * 2.8f)) - (w * 0.9f)
                        val beamWidth = w * 0.45f
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.35f),
                                    Color.White.copy(alpha = 0.95f),
                                    Color.White.copy(alpha = 0.35f),
                                    Color.Transparent
                                ),
                                start = Offset(travel, 0f),
                                end = Offset(travel + beamWidth, h)
                            ),
                            blendMode = BlendMode.SrcIn
                        )
                    }
                },
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
 * Thin red ribbon light streaks fanning out symmetrically behind the K in Step 2.
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

    val beamLengthRatio = (0.35f + 0.65f * progress)

    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val maxBeamLength = size.width.coerceAtLeast(size.height) * 0.85f * beamLengthRatio

        val beamAngles = listOf(-38f, -25f, -12f, 0f, 12f, 25f, 38f)
        val beamWidths = listOf(10f, 14f, 18f, 22f, 18f, 14f, 10f)

        for (i in beamAngles.indices) {
            val angleDeg = beamAngles[i]
            val bWidth = beamWidths[i] * (0.8f + 0.4f * (1f - progress))

            rotate(degrees = angleDeg, pivot = Offset(centerX, centerY)) {
                // Primary upward beam
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            KrasRedBright.copy(alpha = 0.70f * alpha),
                            KrasRed.copy(alpha = 0.50f * alpha),
                            Color.Transparent
                        ),
                        startY = centerY - maxBeamLength,
                        endY = centerY
                    ),
                    topLeft = Offset(centerX - bWidth / 2f, centerY - maxBeamLength),
                    size = Size(bWidth, maxBeamLength)
                )

                // Secondary downward flare
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            KrasRed.copy(alpha = 0.45f * alpha),
                            Color.Transparent
                        ),
                        startY = centerY,
                        endY = centerY + maxBeamLength * 0.7f
                    ),
                    topLeft = Offset(centerX - bWidth * 0.4f, centerY),
                    size = Size(bWidth * 0.8f, maxBeamLength * 0.7f)
                )
            }
        }
    }
}
