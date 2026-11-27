package com.example.ui.screens

import android.animation.ValueAnimator
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.ui.theme.KrasRed
import com.example.ui.theme.KrasRedBright
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Immutable frame state evaluated purely from elapsed milliseconds along the 0.0–1.8s
 * Cinematic K Zoom-Through timeline (unit-testable and jitter-free on 60Hz / 120Hz displays).
 */
data class CinematicKZoomThroughFrame(
    val kAlpha: Float,
    val kScale: Float,
    val lightSweepProgress: Float,
    val depthLightingAlpha: Float,
    val centerGlowAlpha: Float,
    val portalOpenProgress: Float,
    val motionBlurDp: Float,
    val perspectiveTiltX: Float,
    val perspectiveTiltY: Float,
    val backdropAlpha: Float,
    val kShellAlpha: Float,
    val speedStreaksAlpha: Float,
    val isFinished: Boolean
)

/**
 * Evaluates the exact 4-phase Cinematic K Zoom-Through timeline:
 * - 0.0–0.4s (0–400ms): Pure AMOLED black background; reveal the original metallic red 3D K at center.
 * - 0.4–0.8s (400–800ms): Cinematic light reflection sweeps across the K; realistic depth & red glow build.
 * - 0.8–1.5s (800–1500ms): Smoothly accelerate camera into the center of the K, enlarging dramatically
 *   beyond screen bounds with controlled K-silhouette portal mask, 3D perspective & subtle motion blur.
 * - 1.5–1.8s (1500–1800ms): Seamlessly reveal the actual KRAS TV Home screen directly from inside the
 *   enlarged K with continuous camera momentum — zero cut, zero fade-to-black.
 */
object CinematicKZoomThroughTimeline {
    const val PHASE_1_END_MS = 400L
    const val PHASE_2_END_MS = 800L
    const val PHASE_3_END_MS = 1500L
    const val TOTAL_DURATION_MS = 1800L

    fun evaluate(elapsedMs: Long): CinematicKZoomThroughFrame {
        val t = elapsedMs.coerceIn(0L, TOTAL_DURATION_MS)

        // Phase 1: 0.0–0.4s (0–400ms) — Reveal original metallic red 3D K on pure AMOLED black
        val p1 = (t.toFloat() / PHASE_1_END_MS.toFloat()).coerceIn(0f, 1f)
        val p1EaseOut = easeOutCubic(p1)

        // Phase 2: 0.4–0.8s (400–800ms) — Light sweep reflection, 3D depth & soft red glow
        val p2 = ((t - PHASE_1_END_MS).toFloat() / (PHASE_2_END_MS - PHASE_1_END_MS).toFloat()).coerceIn(0f, 1f)
        val p2Ease = easeInOutCubic(p2)

        // Phase 3: 0.8–1.5s (800–1500ms) — Accelerating camera dive into & through the K
        val p3 = ((t - PHASE_2_END_MS).toFloat() / (PHASE_3_END_MS - PHASE_2_END_MS).toFloat()).coerceIn(0f, 1f)
        val p3Accel = p3.pow(2.55f)

        // Phase 4: 1.5–1.8s (1500–1800ms) — Seamless Home screen emergence through enlarged K
        val p4 = ((t - PHASE_3_END_MS).toFloat() / (TOTAL_DURATION_MS - PHASE_3_END_MS).toFloat()).coerceIn(0f, 1f)
        val p4EaseOut = easeOutQuad(p4)

        val kAlpha = p1EaseOut
        val lightSweepProgress = p2Ease
        val depthLightingAlpha = (p1 * 0.25f + p2Ease * 0.75f).coerceIn(0f, 1f)
        val centerGlowAlpha = when {
            t <= PHASE_2_END_MS -> (p1EaseOut * 0.55f + p2Ease * 0.45f).coerceIn(0f, 1f)
            else -> (1f - p3 * 0.55f - p4EaseOut * 0.45f).coerceIn(0f, 1f)
        }

        // Continuous camera zoom scale across all 4 phases (never snaps or resets)
        val kScale = when {
            t <= PHASE_1_END_MS -> lerp(0.86f, 1.00f, p1EaseOut)
            t <= PHASE_2_END_MS -> lerp(1.00f, 1.07f, p2Ease)
            t <= PHASE_3_END_MS -> lerp(1.07f, 26.0f, p3Accel)
            else -> lerp(26.0f, 56.0f, p4EaseOut)
        }

        // Controlled K-shaped portal aperture opens smoothly as camera dives into K core (0.98s -> 1.72s)
        val portalRaw = ((t - 980L).toFloat() / 740f).coerceIn(0f, 1f)
        val portalOpenProgress = easeInOutCubic(portalRaw)

        // Subtle velocity-coupled GPU motion blur peaking during 1.05s–1.55s high-speed dive
        val blurWindow = ((t - 920L).toFloat() / 680f).coerceIn(0f, 1f)
        val motionBlurDp = if (t in 920L..1660L) {
            sin(blurWindow * PI).toFloat().coerceAtLeast(0f) * 4.5f
        } else {
            0f
        }

        // Subtle 3D camera perspective dive angle
        val perspectiveTiltX = lerp(6f, 0f, p1EaseOut) + (if (t > PHASE_2_END_MS) -p3 * 3.5f * (1f - p4) else 0f)
        val perspectiveTiltY = if (t > PHASE_2_END_MS) sin(p3 * PI).toFloat() * 4.5f else 0f

        // Crimson radial speed streaks during high-velocity camera dive
        val speedStreaksAlpha = if (t in 840L..1620L) {
            val s = ((t - 840L).toFloat() / 780f).coerceIn(0f, 1f)
            sin(s * PI).toFloat().coerceAtLeast(0f) * 0.45f
        } else {
            0f
        }

        // Backdrop and K outer rim smoothly clear completely as camera exits through K (1.42s -> 1.80s)
        val backdropClear = ((t - 1420L).toFloat() / 380f).coerceIn(0f, 1f)
        val backdropAlpha = (1f - easeOutQuad(backdropClear)).coerceIn(0f, 1f)

        val shellClear = ((t - 1380L).toFloat() / 420f).coerceIn(0f, 1f)
        val kShellAlpha = (kAlpha * (1f - easeInOutCubic(shellClear))).coerceIn(0f, 1f)

        return CinematicKZoomThroughFrame(
            kAlpha = kAlpha,
            kScale = kScale,
            lightSweepProgress = lightSweepProgress,
            depthLightingAlpha = depthLightingAlpha,
            centerGlowAlpha = centerGlowAlpha,
            portalOpenProgress = portalOpenProgress,
            motionBlurDp = motionBlurDp,
            perspectiveTiltX = perspectiveTiltX,
            perspectiveTiltY = perspectiveTiltY,
            backdropAlpha = backdropAlpha,
            kShellAlpha = kShellAlpha,
            speedStreaksAlpha = speedStreaksAlpha,
            isFinished = t >= TOTAL_DURATION_MS
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    private fun easeOutCubic(t: Float): Float = 1f - (1f - t).pow(3)
    private fun easeOutQuad(t: Float): Float = 1f - (1f - t) * (1f - t)
    private fun easeInOutCubic(t: Float): Float =
        if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f
}

/**
 * KRAS TV — Cinematic K Zoom-Through Startup Animation (1.8s).
 *
 * Layered directly over the preloaded live Home screen so the camera physically travels
 * into and through the original metallic red 3D letter K to reveal the actual Home screen
 * without any cut, black frame, or white flash.
 */
@Composable
fun CinematicKZoomThroughSplash(
    onSplashFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density

    // Respect Android reduced-motion accessibility settings
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

    var elapsedMs by remember { mutableLongStateOf(0L) }
    var reducedMotionAlpha by remember { mutableStateOf(1f) }
    var hasFinished by remember { mutableStateOf(false) }

    fun finishSplash() {
        if (!hasFinished) {
            hasFinished = true
            onSplashFinished()
        }
    }

    LaunchedEffect(isReducedMotion) {
        // Sync start precisely to the first composed display frame (60Hz / 120Hz lock)
        val startNanos = withFrameNanos { it }

        if (isReducedMotion) {
            // Reduced motion: calm static reveal of 3D K on pure black, then clean dissolve
            elapsedMs = 780L
            delay(360L)
            val fadeStart = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val progress = ((now - fadeStart) / 260_000_000f).coerceIn(0f, 1f)
                reducedMotionAlpha = 1f - progress
                if (progress >= 1f) break
            }
            finishSplash()
            return@LaunchedEffect
        }

        while (true) {
            val nowNanos = withFrameNanos { it }
            val currentElapsed = ((nowNanos - startNanos) / 1_000_000L)
                .coerceAtLeast(0L)
            elapsedMs = currentElapsed
            if (currentElapsed >= CinematicKZoomThroughTimeline.TOTAL_DURATION_MS) {
                break
            }
        }
        finishSplash()
    }

    val frame = remember(elapsedMs) {
        CinematicKZoomThroughTimeline.evaluate(elapsedMs)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                if (isReducedMotion) {
                    alpha = reducedMotionAlpha
                }
            }
            .semantics { contentDescription = "KRAS TV Cinematic K Zoom-Through Intro" }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                finishSplash()
            }
            .testTag("cinematic_k_zoom_through_splash"),
        contentAlignment = Alignment.Center
    ) {
        val minScreenDim = minOf(maxWidth, maxHeight)
        val kBoxSize = (minScreenDim * 0.44f).coerceIn(156.dp, 240.dp)
        val kBoxSizePx = with(LocalDensity.current) { kBoxSize.toPx() }

        // =====================================================================
        // LAYER 1: PURE AMOLED BLACK CANVAS WITH EXPANDING K-SILHOUETTE PORTAL
        // Punches through cleanly via BlendMode.DstOut so the real preloaded
        // Home screen emerges directly from inside the enlarging 3D letter K.
        // =====================================================================
        if (frame.backdropAlpha > 0.004f) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = frame.backdropAlpha
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
            ) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val minDim = size.minDimension

                // 1. Pure AMOLED Black (#000000) base
                drawRect(color = Color.Black)

                // 2. Soft volumetric red cinema glow behind the K (0.0–1.5s)
                if (frame.centerGlowAlpha > 0.005f) {
                    val glowRadius = (minDim * 0.46f * (0.85f + frame.depthLightingAlpha * 0.25f)).coerceAtLeast(1f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                KrasRedBright.copy(alpha = 0.30f * frame.centerGlowAlpha),
                                KrasRed.copy(alpha = 0.14f * frame.centerGlowAlpha),
                                Color(0xFF4A0206).copy(alpha = 0.05f * frame.centerGlowAlpha),
                                Color.Transparent
                            ),
                            center = Offset(cx, cy),
                            radius = glowRadius
                        ),
                        radius = glowRadius,
                        center = Offset(cx, cy)
                    )
                }

                // 3. Subtle crimson horizon laser beneath the K (0.4–1.1s, fades smoothly on dive)
                val horizonAlpha = (frame.depthLightingAlpha * (1f - frame.portalOpenProgress * 2.2f)).coerceIn(0f, 1f)
                if (horizonAlpha > 0.01f) {
                    val horizonY = cy + kBoxSizePx * 0.56f
                    val halfW = minDim * 0.34f
                    drawLine(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                KrasRed.copy(alpha = 0.32f * horizonAlpha),
                                Color(0xFFFF525D).copy(alpha = 0.80f * horizonAlpha),
                                Color.White.copy(alpha = 0.45f * horizonAlpha),
                                Color(0xFFFF525D).copy(alpha = 0.80f * horizonAlpha),
                                KrasRed.copy(alpha = 0.32f * horizonAlpha),
                                Color.Transparent
                            ),
                            startX = cx - halfW,
                            endX = cx + halfW
                        ),
                        start = Offset(cx - halfW, horizonY),
                        end = Offset(cx + halfW, horizonY),
                        strokeWidth = (minDim * 0.003f).coerceAtLeast(1.5f),
                        cap = StrokeCap.Round
                    )
                }

                // 4. Radial crimson cinematic speed streaks during camera dive (0.84–1.62s)
                if (frame.speedStreaksAlpha > 0.01f) {
                    val maxR = maxOf(size.width, size.height) * 0.78f
                    val innerR = maxR * (0.14f + frame.portalOpenProgress * 0.35f)
                    for (i in 0 until 12) {
                        val angleRad = (i * 30.0 + 12.0) * PI / 180.0
                        val cosA = cos(angleRad).toFloat()
                        val sinA = sin(angleRad).toFloat()
                        val start = Offset(cx + cosA * innerR, cy + sinA * innerR)
                        val end = Offset(cx + cosA * maxR, cy + sinA * maxR)
                        drawLine(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    KrasRedBright.copy(alpha = frame.speedStreaksAlpha * 0.55f),
                                    Color.Transparent
                                ),
                                start = start,
                                end = end
                            ),
                            start = start,
                            end = end,
                            strokeWidth = if (i % 2 == 0) 2.2f else 1.3f,
                            cap = StrokeCap.Round
                        )
                    }
                }

                // 5. CONTROLLED K-SHAPED PORTAL MASK (0.98–1.80s):
                //    Punches an enlarging K-shaped window straight through the black canvas
                //    so the live preloaded KRAS TV Home screen appears physically from inside the K!
                if (frame.portalOpenProgress > 0.005f) {
                    val kLeft = cx - kBoxSizePx / 2f
                    val kTop = cy - kBoxSizePx / 2f
                    val pivotX = kLeft + kBoxSizePx * 0.46f
                    val pivotY = kTop + kBoxSizePx * 0.49f

                    val portalPath = buildApproved3DKSilhouettePath(
                        width = kBoxSizePx,
                        height = kBoxSizePx
                    )
                    // Enlarging exact K-silhouette cutout synchronized to camera zoom
                    val portalScale = (frame.kScale * (0.62f + frame.portalOpenProgress * 0.52f)).coerceAtLeast(1f)
                    withTransform({
                        scale(scaleX = portalScale, scaleY = portalScale, pivot = Offset(pivotX, pivotY))
                        translate(left = kLeft, top = kTop)
                    }) {
                        drawPath(
                            path = portalPath,
                            color = Color.Black,
                            alpha = (frame.portalOpenProgress * 1.45f).coerceIn(0f, 1f),
                            blendMode = BlendMode.DstOut
                        )
                    }

                    // Expanding feathered radial aperture inside the K's widening core so the final
                    // 1.5–1.8s Home screen reveal reaches all four viewport corners seamlessly
                    if (frame.portalOpenProgress > 0.28f) {
                        val apertureNorm = ((frame.portalOpenProgress - 0.28f) / 0.72f).coerceIn(0f, 1f)
                        val diagonalRadius = kotlin.math.hypot(size.width.toDouble(), size.height.toDouble()).toFloat() * 0.68f
                        val r = (diagonalRadius * apertureNorm.pow(1.45f)).coerceAtLeast(1f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0.00f to Color.Black,
                                    0.72f to Color.Black,
                                    1.00f to Color.Transparent
                                ),
                                center = Offset(pivotX, pivotY),
                                radius = r
                            ),
                            radius = r,
                            center = Offset(pivotX, pivotY),
                            blendMode = BlendMode.DstOut
                        )
                    }
                }
            }
        }

        // =====================================================================
        // LAYER 2: ORIGINAL METALLIC RED 3D LETTER 'K' ZOOMING PAST CAMERA
        // Uses the EXACT drawApproved3DK geometry, with subtle 3D perspective,
        // velocity-coupled GPU motion blur, and an interior portal cutout as the
        // camera passes physically through the center of the K.
        // =====================================================================
        if (frame.kShellAlpha > 0.004f) {
            val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && frame.motionBlurDp > 0.15f) {
                Modifier.blur(frame.motionBlurDp.dp)
            } else {
                Modifier
            }

            Box(
                modifier = Modifier
                    .size(kBoxSize)
                    .then(blurModifier)
                    .graphicsLayer {
                        alpha = frame.kShellAlpha
                        scaleX = frame.kScale
                        scaleY = frame.kScale
                        rotationX = frame.perspectiveTiltX
                        rotationY = frame.perspectiveTiltY
                        cameraDistance = 11f * density
                        transformOrigin = TransformOrigin(0.46f, 0.49f)
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .testTag("zoom_through_k_glyph")
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Draw the EXACT original approved metallic red 3D letter K
                    drawApproved3DK(
                        lightSweepProgress = frame.lightSweepProgress,
                        depthLightingAlpha = frame.depthLightingAlpha
                    )

                    // As camera passes physically through the heart of the K (portalOpenProgress > 0),
                    // hollow out the center of the K itself so its glowing crimson metallic edges
                    // frame the emerging Home screen as they sweep past the viewport edges!
                    if (frame.portalOpenProgress > 0.04f) {
                        val coreNorm = ((frame.portalOpenProgress - 0.04f) / 0.96f).coerceIn(0f, 1f)
                        val coreCenter = Offset(size.width * 0.46f, size.height * 0.49f)
                        val coreRadius = (size.minDimension * (0.08f + coreNorm * 0.62f)).coerceAtLeast(1f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0.00f to Color.Black,
                                    0.58f to Color.Black.copy(alpha = (coreNorm * 1.25f).coerceIn(0f, 1f)),
                                    1.00f to Color.Transparent
                                ),
                                center = coreCenter,
                                radius = coreRadius
                            ),
                            radius = coreRadius,
                            center = coreCenter,
                            blendMode = BlendMode.DstOut
                        )
                    }
                }
            }
        }
    }
}
