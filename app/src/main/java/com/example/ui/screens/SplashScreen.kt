package com.example.ui.screens

import android.animation.ValueAnimator
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.ui.theme.KrasRed
import com.example.ui.theme.KrasRedBright
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Smooth cinema-grade cubic-bezier easing curves
private val CinematicEmergenceEasing = CubicBezierEasing(0.22f, 1.0f, 0.36f, 1.0f)
private val CinematicSweepEasing = CubicBezierEasing(0.32f, 0.0f, 0.20f, 1.0f)
private val CinematicZoomEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1.0f)
private val CinematicFadeOutEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

/**
 * KRAS TV – Premium Cinematic Animated K Intro (2.5s Sequence)
 *
 * Strictly renders ONLY the approved red 3D folded-ribbon letter K on a pure AMOLED black
 * background (#000000) without displaying the full "KRAS TV" text during the animation.
 *
 * Timeline:
 * - 0.0–0.5s (0–500ms):
 *   Start with a completely black screen (#000000). A subtle red light begins to appear in the center.
 * - 0.5–1.2s (500–1200ms):
 *   The 3D letter K gradually emerges from darkness. A beautiful red light sweeps across the letter
 *   using smooth, premium easing animations.
 * - 1.2–2.0s (1200–2000ms):
 *   The K becomes fully visible with subtle 3D depth, soft reflections, horizontal red horizon flare,
 *   and elegant red lighting, accompanied by a very gentle cinematic zoom.
 * - 2.0–2.5s (2000–2500ms):
 *   The K smoothly fades into pure black and transitions directly to the KRAS TV home screen.
 */
@Composable
fun SplashScreen(
    isLoading: Boolean = false,
    onSplashFinished: () -> Unit
) {
    val context = LocalContext.current

    // Respect system reduced-motion accessibility preferences
    val isReducedMotion = remember(context) {
        try {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
            scale == 0f || !ValueAnimator.areAnimatorsEnabled()
        } catch (_: Exception) {
            false
        }
    }

    // Phase 1 (0.0–0.5s): Center red ambient light emergence
    val centerLightAlpha = remember { Animatable(if (isReducedMotion) 0.55f else 0f) }
    val centerLightScale = remember { Animatable(if (isReducedMotion) 1f else 0.68f) }

    // Phase 2 (0.5–1.2s): 3D letter K emergence & red light sweep across the K
    val kAlpha = remember { Animatable(if (isReducedMotion) 1f else 0f) }
    val lightSweepProgress = remember { Animatable(0f) }

    // Phase 2 & 3 (0.5–2.0s): Gentle cinematic zoom & 3D depth/reflections/horizon lighting
    val kScale = remember { Animatable(if (isReducedMotion) 1f else 0.94f) }
    val depthLightingAlpha = remember { Animatable(if (isReducedMotion) 0.9f else 0f) }

    // Phase 4 (2.0–2.5s): Smooth fade into pure AMOLED black (#000000)
    val masterFadeAlpha = remember { Animatable(1f) }

    // Functional loading state indicator (shown only if actual loading is still required after 2.0s)
    var showLoadingIndicator by remember { mutableStateOf(false) }
    var hasFinished by remember { mutableStateOf(false) }

    fun finishSplash() {
        if (!hasFinished) {
            hasFinished = true
            onSplashFinished()
        }
    }

    LaunchedEffect(isReducedMotion) {
        // Wait for the very first composed frame so animation timing starts cleanly at 0.0s
        withFrameNanos { }

        if (isReducedMotion) {
            // Reduced motion accessibility mode: calm static display then smooth fade to black
            delay(450)
            masterFadeAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
            )
            finishSplash()
            return@LaunchedEffect
        }

        coroutineScope {
            // =========================================================================
            // 0.0–0.5s (0–500ms):
            // Start with a completely black screen. A subtle red light begins to appear in the center.
            // =========================================================================
            launch {
                centerLightAlpha.animateTo(
                    targetValue = 0.52f,
                    animationSpec = tween(durationMillis = 500, easing = LinearOutSlowInEasing)
                )
            }
            launch {
                centerLightScale.animateTo(
                    targetValue = 1.0f,
                    animationSpec = tween(durationMillis = 500, easing = LinearOutSlowInEasing)
                )
            }

            // =========================================================================
            // 0.5–1.2s (500–1200ms):
            // The letter K gradually emerges from darkness.
            // A beautiful red light sweeps across the letter with smooth, premium easing.
            // =========================================================================
            launch {
                delay(500)
                kAlpha.animateTo(
                    targetValue = 1.0f,
                    animationSpec = tween(durationMillis = 700, easing = CinematicEmergenceEasing)
                )
            }
            launch {
                delay(500)
                lightSweepProgress.animateTo(
                    targetValue = 1.0f,
                    animationSpec = tween(durationMillis = 700, easing = CinematicSweepEasing)
                )
            }
            launch {
                delay(500)
                centerLightAlpha.animateTo(
                    targetValue = 0.78f,
                    animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing)
                )
            }

            // =========================================================================
            // 0.5–2.0s (500–2000ms):
            // Very gentle continuous cinematic zoom across emergence and full visibility
            // =========================================================================
            launch {
                delay(500)
                kScale.animateTo(
                    targetValue = 1.045f,
                    animationSpec = tween(durationMillis = 1500, easing = CinematicZoomEasing)
                )
            }

            // =========================================================================
            // 1.2–2.0s (1200–2000ms):
            // The K becomes fully visible. Add subtle depth, soft reflections, and elegant red lighting.
            // =========================================================================
            launch {
                delay(950)
                depthLightingAlpha.animateTo(
                    targetValue = 1.0f,
                    animationSpec = tween(durationMillis = 850, easing = CinematicEmergenceEasing)
                )
            }
            launch {
                delay(1200)
                centerLightScale.animateTo(
                    targetValue = 1.14f,
                    animationSpec = tween(durationMillis = 800, easing = CinematicZoomEasing)
                )
            }
        }

        // At 2.0s (2000ms): If actual data loading is still active, wait briefly with a subtle
        // non-intrusive indicator so we never block startup unnecessarily (capped at 800ms max).
        if (isLoading) {
            showLoadingIndicator = true
            var waitedMs = 0
            while (isLoading && waitedMs < 800) {
                delay(50)
                waitedMs += 50
            }
            showLoadingIndicator = false
        }

        // =========================================================================
        // 2.0–2.5s (2000–2500ms):
        // The K smoothly fades into black. Transition directly to the KRAS TV home screen.
        // =========================================================================
        coroutineScope {
            launch {
                kScale.animateTo(
                    targetValue = 1.065f,
                    animationSpec = tween(durationMillis = 500, easing = CinematicFadeOutEasing)
                )
            }
            launch {
                masterFadeAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 500, easing = CinematicFadeOutEasing)
                )
            }
        }

        finishSplash()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black) // Pure AMOLED black (#000000)
            .semantics { contentDescription = "KRAS TV Animated K Intro" }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                // Never block application startup unnecessarily: allow instant tap-to-skip
                finishSplash()
            }
            .testTag("splash_screen_root"),
        contentAlignment = Alignment.Center
    ) {
        // Maintain proper aspect ratio across compact phones, Samsung Galaxy S26 Ultra, and tablets
        val minScreenDim = minOf(maxWidth, maxHeight)
        val kBoxSize = (minScreenDim * 0.44f).coerceIn(156.dp, 240.dp)

        // Master scene container that smoothly fades into pure AMOLED black during 2.0–2.5s
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = masterFadeAlpha.value
                },
            contentAlignment = Alignment.Center
        ) {
            // 1. AMBIENT CINEMATIC LIGHTING & CENTER RED LIGHT (0.0–2.0s)
            CinematicKLightingBackdrop(
                centerLightAlpha = centerLightAlpha.value,
                centerLightScale = centerLightScale.value,
                depthLightingAlpha = depthLightingAlpha.value
            )

            // 2. MAIN RED 3D LETTER 'K' LOGO (Visually identical to approved reference image, NO text)
            ApprovedRed3DKLogo(
                kAlpha = kAlpha.value,
                kScale = kScale.value,
                lightSweepProgress = lightSweepProgress.value,
                depthLightingAlpha = depthLightingAlpha.value,
                modifier = Modifier
                    .size(kBoxSize)
                    .testTag("splash_k_logo")
            )

            // 3. FUNCTIONAL LOADING INDICATOR (Only displayed if actual data loading is still active)
            if (showLoadingIndicator && isLoading) {
                SubtleFunctionalLoadingBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = (-56).dp)
                        .testTag("splash_loading_indicator")
                )
            }
        }
    }
}

/**
 * Renders the subtle center red light (0.0–0.5s), the horizontal red horizon laser reflection
 * directly beneath the K (1.2–2.0s), and the delicate top-left / bottom-right red silk ambient
 * light contours from the reference artwork on a pure #000000 AMOLED canvas.
 */
@Composable
private fun CinematicKLightingBackdrop(
    centerLightAlpha: Float,
    centerLightScale: Float,
    depthLightingAlpha: Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val minDim = size.minDimension

        // 1. Center Volumetric Red Light (0.0–0.5s subtle emergence, intensifies through 2.0s)
        if (centerLightAlpha > 0.005f) {
            val glowRadius = (minDim * 0.42f * centerLightScale).coerceAtLeast(1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        KrasRedBright.copy(alpha = 0.28f * centerLightAlpha),
                        KrasRed.copy(alpha = 0.14f * centerLightAlpha),
                        Color(0xFF590206).copy(alpha = 0.05f * centerLightAlpha),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = glowRadius
                ),
                radius = glowRadius,
                center = Offset(cx, cy)
            )
        }

        // 2. Stage 3 (1.2–2.0s): Horizontal Red Horizon Reflection & Subtle Corner Silk Contours
        if (depthLightingAlpha > 0.005f) {
            val horizonY = cy + minDim * 0.285f
            val horizonHalfWidth = minDim * 0.36f

            // Soft red floor reflection pool below the horizon line (matching reference image)
            val floorGlowRadiusX = minDim * 0.24f
            val floorGlowRadiusY = minDim * 0.085f
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(
                        KrasRedBright.copy(alpha = 0.34f * depthLightingAlpha),
                        KrasRed.copy(alpha = 0.14f * depthLightingAlpha),
                        Color.Transparent
                    ),
                    center = Offset(cx, horizonY + floorGlowRadiusY * 0.25f),
                    radius = floorGlowRadiusX.coerceAtLeast(1f)
                ),
                topLeft = Offset(cx - floorGlowRadiusX, horizonY - floorGlowRadiusY * 0.3f),
                size = Size(floorGlowRadiusX * 2f, floorGlowRadiusY * 1.8f)
            )

            // Razor-thin horizontal red laser horizon line
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        KrasRed.copy(alpha = 0.35f * depthLightingAlpha),
                        Color(0xFFFF4D58).copy(alpha = 0.85f * depthLightingAlpha),
                        Color.White.copy(alpha = 0.55f * depthLightingAlpha),
                        Color(0xFFFF4D58).copy(alpha = 0.85f * depthLightingAlpha),
                        KrasRed.copy(alpha = 0.35f * depthLightingAlpha),
                        Color.Transparent
                    ),
                    startX = cx - horizonHalfWidth,
                    endX = cx + horizonHalfWidth
                ),
                start = Offset(cx - horizonHalfWidth, horizonY),
                end = Offset(cx + horizonHalfWidth, horizonY),
                strokeWidth = (minDim * 0.0032f).coerceAtLeast(1.5f),
                cap = StrokeCap.Round
            )

            // Subtle top-left and bottom-right ambient red silk rim waves (matching reference frame)
            val topSilkGlow = Path().apply {
                moveTo(0f, size.height * 0.31f)
                quadraticTo(
                    size.width * 0.15f,
                    size.height * 0.16f,
                    size.width * 0.26f,
                    0f
                )
                lineTo(0f, 0f)
                close()
            }
            drawPath(
                path = topSilkGlow,
                brush = Brush.linearGradient(
                    colors = listOf(
                        KrasRed.copy(alpha = 0.16f * depthLightingAlpha),
                        Color(0xFF4A0206).copy(alpha = 0.06f * depthLightingAlpha),
                        Color.Transparent
                    ),
                    start = Offset(size.width * 0.22f, size.height * 0.10f),
                    end = Offset(0f, 0f)
                )
            )

            val bottomSilkGlow = Path().apply {
                moveTo(size.width, size.height * 0.69f)
                quadraticTo(
                    size.width * 0.85f,
                    size.height * 0.85f,
                    size.width * 0.68f,
                    size.height
                )
                lineTo(size.width, size.height)
                close()
            }
            drawPath(
                path = bottomSilkGlow,
                brush = Brush.linearGradient(
                    colors = listOf(
                        KrasRed.copy(alpha = 0.16f * depthLightingAlpha),
                        Color(0xFF4A0206).copy(alpha = 0.06f * depthLightingAlpha),
                        Color.Transparent
                    ),
                    start = Offset(size.width * 0.78f, size.height * 0.88f),
                    end = Offset(size.width, size.height)
                )
            )
        }
    }
}

/**
 * High-resolution vector/canvas renderer for the approved red 3D folded-ribbon letter 'K'.
 *
 * Visually matches the approved reference image:
 * 1. Left Vertical Pillar: Rounded top-left corner, dark charcoal/obsidian body (#2B2B33 -> #07070A)
 *    with a glowing neon-crimson left & top rim highlight (#FF6B74 -> #FF1E27 -> #E50914).
 * 2. Lower-Right Leg: Emerges from behind the center of the ribbon with a realistic deep cast shadow
 *    (#0C0102) transitioning into a luminous 3D crimson bevel (#E50914 -> #FF1E27 -> #780308).
 * 3. Sweeping Foreground 3D Crimson Ribbon: Wraps from the bottom-left corner in an S-curve up to
 *    the rounded top-right shoulder and sharp top-right point, complete with sculpted 3D bevels,
 *    specular rim highlights, and an animated diagonal red light sweep (0.5–1.2s).
 */
@Composable
fun ApprovedRed3DKLogo(
    kAlpha: Float,
    kScale: Float,
    lightSweepProgress: Float,
    depthLightingAlpha: Float,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .graphicsLayer {
                alpha = kAlpha
                scaleX = kScale
                scaleY = kScale
                compositingStrategy = CompositingStrategy.Offscreen
            }
    ) {
        drawApproved3DK(
            lightSweepProgress = lightSweepProgress,
            depthLightingAlpha = depthLightingAlpha
        )
    }
}

private fun DrawScope.drawApproved3DK(
    lightSweepProgress: Float,
    depthLightingAlpha: Float
) {
    val w = size.width
    val h = size.height

    // Normalized coordinate helper (0..100 x 0..100 viewport)
    fun pt(x: Float, y: Float): Offset = Offset(w * (x / 100f), h * (y / 100f))

    // =========================================================================
    // 0. SOFT OUTER RIM BLOOM (Behind the K edges, intensifies during 1.2–2.0s)
    // =========================================================================
    if (depthLightingAlpha > 0.01f) {
        // Subtle neon-red edge bloom behind the left vertical rim and top-right tip
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    KrasRedBright.copy(alpha = 0.24f * depthLightingAlpha),
                    Color.Transparent
                ),
                center = pt(18f, 50f),
                radius = w * 0.28f
            ),
            radius = w * 0.28f,
            center = pt(18f, 50f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFFF4D58).copy(alpha = 0.22f * depthLightingAlpha),
                    Color.Transparent
                ),
                center = pt(74f, 18f),
                radius = w * 0.22f
            ),
            radius = w * 0.22f,
            center = pt(74f, 18f)
        )
    }

    // =========================================================================
    // 1. LEFT VERTICAL PILLAR (Dark Obsidian Body + Glowing Red Left/Top Rim)
    // =========================================================================
    val leftPillarPath = Path().apply {
        moveTo(w * 0.30f, h * 0.12f)
        lineTo(w * 0.43f, h * 0.12f)
        lineTo(w * 0.43f, h * 0.60f)
        lineTo(w * 0.18f, h * 0.88f)
        lineTo(w * 0.18f, h * 0.24f)
        cubicTo(
            w * 0.18f, h * 0.165f,
            w * 0.228f, h * 0.12f,
            w * 0.30f, h * 0.12f
        )
        close()
    }

    // Dark brushed charcoal / obsidian fill matching the reference image's left pillar
    drawPath(
        path = leftPillarPath,
        brush = Brush.linearGradient(
            colors = listOf(
                Color(0xFF32323A),
                Color(0xFF1E1E24),
                Color(0xFF101015),
                Color(0xFF060609)
            ),
            start = pt(20f, 12f),
            end = pt(42f, 76f)
        )
    )

    // Cast shadow on the lower portion of the vertical pillar right above the ribbon fold
    val pillarShadowPath = Path().apply {
        moveTo(w * 0.18f, h * 0.50f)
        lineTo(w * 0.43f, h * 0.32f)
        lineTo(w * 0.43f, h * 0.60f)
        lineTo(w * 0.18f, h * 0.88f)
        close()
    }
    drawPath(
        path = pillarShadowPath,
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                Color(0xAA040406),
                Color(0xF2020203)
            ),
            start = pt(30f, 34f),
            end = pt(34f, 64f)
        )
    )

    // Subtle dark crimson inner right edge of the vertical pillar
    drawLine(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xAAFF3842),
                Color(0x77A8050D),
                Color.Transparent
            ),
            startY = h * 0.12f,
            endY = h * 0.46f
        ),
        start = pt(43f, 12.2f),
        end = pt(43f, 46f),
        strokeWidth = (w * 0.009f).coerceAtLeast(1.2f)
    )

    // Glowing neon-crimson rim highlight along the left & top edges of the vertical pillar
    val leftPillarRimPath = Path().apply {
        moveTo(w * 0.183f, h * 0.86f)
        lineTo(w * 0.183f, h * 0.24f)
        cubicTo(
            w * 0.183f, h * 0.168f,
            w * 0.23f, h * 0.123f,
            w * 0.30f, h * 0.123f
        )
        lineTo(w * 0.428f, h * 0.123f)
    }
    // Soft outer glow stroke along left rim
    drawPath(
        path = leftPillarRimPath,
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0x99FF525D),
                Color(0xBBFF1E27),
                Color(0x99E50914)
            ),
            startY = h * 0.12f,
            endY = h * 0.86f
        ),
        style = Stroke(
            width = (w * 0.026f).coerceAtLeast(3f),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
    // Crisp hot ruby core stroke along left & top rim
    drawPath(
        path = leftPillarRimPath,
        brush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFF949B),
                Color(0xFFFF3842),
                Color(0xFFFF1E27),
                Color(0xFFFF525D)
            ),
            startY = h * 0.12f,
            endY = h * 0.86f
        ),
        style = Stroke(
            width = (w * 0.011f).coerceAtLeast(1.5f),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )

    // =========================================================================
    // 2. LOWER-RIGHT LEG (Emerges from behind the diagonal ribbon to bottom-right)
    // =========================================================================
    val lowerRightLegPath = Path().apply {
        moveTo(w * 0.44f, h * 0.52f)
        lineTo(w * 0.565f, h * 0.385f)
        lineTo(w * 0.855f, h * 0.88f)
        lineTo(w * 0.665f, h * 0.88f)
        cubicTo(
            w * 0.615f, h * 0.88f,
            w * 0.582f, h * 0.852f,
            w * 0.552f, h * 0.800f
        )
        lineTo(w * 0.425f, h * 0.585f)
        close()
    }

    // Sculpted 3D crimson bevel gradient on lower-right leg
    drawPath(
        path = lowerRightLegPath,
        brush = Brush.linearGradient(
            colors = listOf(
                Color(0xFF140103),
                Color(0xFF520206),
                Color(0xFFE50914),
                Color(0xFFFF222C),
                Color(0xFF7D0309)
            ),
            start = pt(46f, 44f),
            end = pt(82f, 88f)
        )
    )

    // Center-right glowing crimson core highlight on the lower-right leg
    val legHighlightPath = Path().apply {
        moveTo(w * 0.535f, h * 0.47f)
        lineTo(w * 0.565f, h * 0.385f)
        lineTo(w * 0.845f, h * 0.865f)
        lineTo(w * 0.72f, h * 0.81f)
        close()
    }
    drawPath(
        path = legHighlightPath,
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                Color(0x99FF2A34),
                Color(0xCCFF1E27),
                Color(0x44B20710)
            ),
            start = pt(53f, 45f),
            end = pt(80f, 84f)
        )
    )

    // Deep realistic cast shadow on the lower-right leg directly beneath the overlapping ribbon
    val legCastShadowPath = Path().apply {
        moveTo(w * 0.425f, h * 0.585f)
        lineTo(w * 0.565f, h * 0.385f)
        lineTo(w * 0.635f, h * 0.505f)
        lineTo(w * 0.495f, h * 0.695f)
        close()
    }
    drawPath(
        path = legCastShadowPath,
        brush = Brush.linearGradient(
            colors = listOf(
                Color(0xF5070001),
                Color(0xCC180103),
                Color(0x553B0205),
                Color.Transparent
            ),
            start = pt(48f, 45f),
            end = pt(60f, 62f)
        )
    )

    // Upper-right diagonal specular rim on the lower-right leg
    drawLine(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                Color(0xEEFF525D),
                Color(0xAAFF1E27)
            ),
            start = pt(56.5f, 38.8f),
            end = pt(85.2f, 87.6f)
        ),
        start = pt(56.5f, 38.8f),
        end = pt(85.2f, 87.6f),
        strokeWidth = (w * 0.010f).coerceAtLeast(1.3f),
        cap = StrokeCap.Round
    )

    // =========================================================================
    // 3. SWEEPING FOREGROUND 3D CRIMSON RIBBON (Bottom-Left to Top-Right)
    // =========================================================================
    val foregroundRibbonPath = Path().apply {
        // Start at bottom-left tip (18, 88) where the ribbon curls around the base
        moveTo(w * 0.18f, h * 0.88f)
        // Left/upper sweeping S-curve from bottom-left up to the rounded top-right shoulder
        cubicTo(
            w * 0.18f, h * 0.71f,
            w * 0.27f, h * 0.54f,
            w * 0.45f, h * 0.355f
        )
        lineTo(w * 0.615f, h * 0.185f)
        cubicTo(
            w * 0.665f, h * 0.135f,
            w * 0.715f, h * 0.12f,
            w * 0.785f, h * 0.12f
        )
        // Flat horizontal top edge to sharp top-right point (85.5, 12)
        lineTo(w * 0.855f, h * 0.12f)
        // Right/lower edge sweeping diagonally down-left to the rounded bottom-left heel
        lineTo(w * 0.555f, h * 0.45f)
        cubicTo(
            w * 0.435f, h * 0.582f,
            w * 0.362f, h * 0.705f,
            w * 0.318f, h * 0.822f
        )
        cubicTo(
            w * 0.300f, h * 0.865f,
            w * 0.272f, h * 0.88f,
            w * 0.225f, h * 0.88f
        )
        close()
    }

    // Main sculpted 3D crimson ribbon body gradient
    drawPath(
        path = foregroundRibbonPath,
        brush = Brush.linearGradient(
            colorStops = arrayOf(
                0.00f to Color(0xFFFF3842), // Bright ruby curl at bottom-left tip
                0.14f to Color(0xFF8C0309), // Deep curved fold shadow near bottom-left
                0.36f to Color(0xFFC8060F), // Rich crimson lower-mid ribbon
                0.62f to Color(0xFFE50914), // Signature KRAS red center
                0.84f to Color(0xFFFF1E27), // Luminous scarlet upper arm
                1.00f to Color(0xFFFF5C66)  // Specular ruby top-right tip
            ),
            start = pt(18f, 88f),
            end = pt(85.5f, 12f)
        )
    )

    // Upper-left 3D bevel highlight along the sweeping ribbon surface
    val ribbonBevelHighlightPath = Path().apply {
        moveTo(w * 0.184f, h * 0.875f)
        cubicTo(
            w * 0.186f, h * 0.71f,
            w * 0.276f, h * 0.54f,
            w * 0.454f, h * 0.356f
        )
        lineTo(w * 0.618f, h * 0.186f)
        cubicTo(
            w * 0.668f, h * 0.137f,
            w * 0.716f, h * 0.123f,
            w * 0.785f, h * 0.123f
        )
        lineTo(w * 0.848f, h * 0.123f)
        lineTo(w * 0.805f, h * 0.17f)
        cubicTo(
            w * 0.735f, h * 0.17f,
            w * 0.688f, h * 0.186f,
            w * 0.642f, h * 0.232f
        )
        lineTo(w * 0.482f, h * 0.398f)
        cubicTo(
            w * 0.318f, h * 0.568f,
            w * 0.228f, h * 0.725f,
            w * 0.205f, h * 0.875f
        )
        close()
    }
    drawPath(
        path = ribbonBevelHighlightPath,
        brush = Brush.linearGradient(
            colors = listOf(
                Color(0xDDFF7A82),
                Color(0x66FF2A34),
                Color(0xAAFF4D58),
                Color(0xEEFF949B)
            ),
            start = pt(18.4f, 87.5f),
            end = pt(84.8f, 12.3f)
        )
    )

    // Razor-sharp glowing specular rim along the ribbon's upper/left S-curve and top edge
    val ribbonOuterRimPath = Path().apply {
        moveTo(w * 0.184f, h * 0.876f)
        cubicTo(
            w * 0.184f, h * 0.71f,
            w * 0.272f, h * 0.54f,
            w * 0.452f, h * 0.355f
        )
        lineTo(w * 0.616f, h * 0.185f)
        cubicTo(
            w * 0.666f, h * 0.136f,
            w * 0.716f, h * 0.123f,
            w * 0.785f, h * 0.123f
        )
        lineTo(w * 0.852f, h * 0.123f)
    }
    drawPath(
        path = ribbonOuterRimPath,
        brush = Brush.linearGradient(
            colors = listOf(
                Color(0xFFFF949B),
                Color(0xCCFF3842),
                Color(0xEEFF5C66),
                Color(0xFFFFC2C7)
            ),
            start = pt(18.4f, 87.6f),
            end = pt(85.2f, 12.3f)
        ),
        style = Stroke(
            width = (w * 0.0115f).coerceAtLeast(1.5f),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )

    // =========================================================================
    // 4. STAGE 2 (0.5–1.2s): CINEMATIC RED LIGHT SWEEP ACROSS THE LETTER 'K'
    //    Strictly clipped to the K silhouette via BlendMode.SrcAtop
    // =========================================================================
    if (lightSweepProgress > 0.01f && lightSweepProgress < 0.99f) {
        val diagSpan = w + h
        val sweepCenter = -w * 0.35f + lightSweepProgress * (diagSpan + w * 0.70f)
        val bandHalfWidth = w * 0.26f

        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    KrasRed.copy(alpha = 0.28f),
                    Color(0xFFFF4D58).copy(alpha = 0.65f),
                    Color(0xFFFFD6D9).copy(alpha = 0.82f),
                    Color(0xFFFF4D58).copy(alpha = 0.65f),
                    KrasRed.copy(alpha = 0.28f),
                    Color.Transparent
                ),
                start = Offset(sweepCenter - bandHalfWidth, sweepCenter - bandHalfWidth * 0.65f),
                end = Offset(sweepCenter + bandHalfWidth, sweepCenter + bandHalfWidth * 0.65f)
            ),
            blendMode = BlendMode.SrcAtop
        )
    }

    // =========================================================================
    // 5. STAGE 3 (1.2–2.0s): SUBTLE 3D DEPTH & SOFT SPECULAR SHEEN OVERLAY
    //    Strictly clipped to the K silhouette via BlendMode.SrcAtop
    // =========================================================================
    if (depthLightingAlpha > 0.01f) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFFF858D).copy(alpha = 0.16f * depthLightingAlpha),
                    Color.Transparent,
                    Color(0xFFFF1E27).copy(alpha = 0.14f * depthLightingAlpha)
                ),
                start = Offset(0f, 0f),
                end = Offset(w, h)
            ),
            blendMode = BlendMode.SrcAtop
        )
    }
}

/**
 * Minimal, non-intrusive loading indicator shown only if network/database initialization
 * is still running after the main K reveal completes.
 */
@Composable
private fun SubtleFunctionalLoadingBar(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "splash_loading")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Canvas(
        modifier = modifier.size(width = 72.dp, height = 2.5.dp)
    ) {
        drawRoundRect(
            color = Color.White.copy(alpha = 0.12f),
            cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
        )
        val indicatorW = size.width * 0.45f
        val startX = (size.width - indicatorW) * pulseProgress
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    KrasRedBright,
                    Color.Transparent
                ),
                startX = startX,
                endX = startX + indicatorW
            ),
            topLeft = Offset(startX, 0f),
            size = Size(indicatorW, size.height),
            cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
        )
    }
}
