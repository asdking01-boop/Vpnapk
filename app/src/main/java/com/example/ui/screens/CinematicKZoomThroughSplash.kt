package com.example.ui.screens

import android.animation.ValueAnimator
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.ui.theme.KrasRed
import com.example.ui.theme.KrasRedBright
import kotlinx.coroutines.delay
import kotlin.math.pow

/**
 * Immutable frame state evaluated purely from elapsed milliseconds along the 0.0–1.8s
 * Seamless Cinematic K Intro timeline (jitter-free on 60Hz and 120Hz displays).
 */
data class CinematicKZoomThroughFrame(
    val kAlpha: Float,
    val kScale: Float,
    val lightSweepProgress: Float,
    val ambientGlowAlpha: Float,
    val backdropAlpha: Float,
    val isFinished: Boolean
)

/**
 * Evaluates the clean 4-phase Seamless Cinematic K Intro sequence (zero masks, zero polygons,
 * zero clipping paths, zero geometric overlays):
 * - 0.0–0.3s (0–300ms): Display the original metallic red 3D K centered on pure AMOLED black (#000000).
 * - 0.3–0.7s (300–700ms): Apply a subtle cinematic reflection across the letter K and begin a smooth zoom.
 * - 0.7–1.5s (700–1500ms): Gradually accelerate the K toward the viewer past screen boundaries while
 *   simultaneously revealing the live Home screen behind it via a synchronized crossfade and gradual
 *   opacity reduction of the K.
 * - 1.5–1.8s (1500–1800ms): Fully reveal the Home screen and dissolve the final trace of the K cleanly
 *   before removing the startup layer completely.
 */
object CinematicKZoomThroughTimeline {
    const val PHASE_1_END_MS = 300L
    const val PHASE_2_END_MS = 700L
    const val PHASE_3_END_MS = 1500L
    const val TOTAL_DURATION_MS = 1800L

    fun evaluate(elapsedMs: Long): CinematicKZoomThroughFrame {
        val t = elapsedMs.coerceIn(0L, TOTAL_DURATION_MS)

        // Phase 1: 0.0–0.3s (0–300ms) — Crisp emergence of original 3D K on pure AMOLED black
        val p1 = (t.toFloat() / PHASE_1_END_MS.toFloat()).coerceIn(0f, 1f)
        val p1EaseOut = easeOutCubic(p1)

        // Phase 2: 0.3–0.7s (300–700ms) — Subtle cinematic reflection sweep & gentle zoom onset
        val p2 = ((t - PHASE_1_END_MS).toFloat() / (PHASE_2_END_MS - PHASE_1_END_MS).toFloat())
            .coerceIn(0f, 1f)
        val p2EaseInOut = easeInOutCubic(p2)
        val p2EaseIn = p2 * p2

        // Phase 3: 0.7–1.5s (700–1500ms) — Accelerating zoom past screen edges + synchronized crossfade
        val p3 = ((t - PHASE_2_END_MS).toFloat() / (PHASE_3_END_MS - PHASE_2_END_MS).toFloat())
            .coerceIn(0f, 1f)
        val p3EaseInOut = easeInOutCubic(p3)

        // Phase 4: 1.5–1.8s (1500–1800ms) — Full Home reveal & final clean overlay dissolution
        val p4 = ((t - PHASE_3_END_MS).toFloat() / (TOTAL_DURATION_MS - PHASE_3_END_MS).toFloat())
            .coerceIn(0f, 1f)
        val p4EaseOut = easeOutQuad(p4)

        // C1-continuous scale curve across all 4 phases (zero speed hesitation or snap):
        //   0.0–0.3s: 0.94x -> 1.00x (gentle settle)
        //   0.3–0.7s: 1.00x -> 1.14x (smooth zoom onset, exit velocity = 0.70/s)
        //   0.7–1.5s: 1.14x -> 16.00x (matching entry velocity 0.70/s, accelerating smoothly beyond screen edges)
        //   1.5–1.8s: 16.00x -> 22.00x (silky outward momentum as final opacity reaches 0)
        val kScale = when {
            t <= PHASE_1_END_MS -> lerp(0.94f, 1.00f, p1EaseOut)
            t <= PHASE_2_END_MS -> lerp(1.00f, 1.14f, p2EaseIn)
            t <= PHASE_3_END_MS -> 1.14f + 0.56f * p3 + 14.30f * p3.pow(2.8f)
            else -> lerp(16.00f, 22.00f, p4EaseOut)
        }

        // Subtle cinematic light reflection sweeping strictly across the K during 0.3–0.7s
        val lightSweepProgress = when {
            t <= PHASE_1_END_MS -> 0f
            t <= PHASE_2_END_MS -> p2EaseInOut
            else -> 1f
        }

        // Synchronized pure-black backdrop crossfade (1.0 -> 0.0 across 0.7–1.5s)
        val backdropAlpha = when {
            t <= PHASE_2_END_MS -> 1f
            t <= PHASE_3_END_MS -> (1f - p3EaseInOut).coerceIn(0f, 1f)
            else -> 0f
        }

        // Soft ambient crimson glow behind the K on the full-screen backdrop (never clipped by K bounds)
        val ambientGlowAlpha = when {
            t <= PHASE_1_END_MS -> p1EaseOut * 0.55f
            t <= PHASE_2_END_MS -> lerp(0.55f, 0.85f, p2EaseInOut)
            t <= PHASE_3_END_MS -> (0.85f * (1f - easeOutQuad(p3))).coerceIn(0f, 1f)
            else -> 0f
        }

        // Smooth K opacity: reveals cleanly in 0.0–0.3s, holds full richness in 0.3–0.7s,
        // then gradually dissolves in sync with the accelerating zoom across 0.7–1.76s.
        val kAlpha = when {
            t <= PHASE_1_END_MS -> p1EaseOut
            t <= PHASE_2_END_MS -> 1f
            t <= PHASE_3_END_MS -> {
                val fadeP = ((t - 760L).toFloat() / 740f).coerceIn(0f, 1f)
                lerp(1f, 0.14f, easeInOutQuad(fadeP))
            }
            else -> {
                val finalFadeP = ((t - PHASE_3_END_MS).toFloat() / 260f).coerceIn(0f, 1f)
                (0.14f * (1f - easeOutCubic(finalFadeP))).coerceIn(0f, 1f)
            }
        }

        return CinematicKZoomThroughFrame(
            kAlpha = kAlpha,
            kScale = kScale,
            lightSweepProgress = lightSweepProgress,
            ambientGlowAlpha = ambientGlowAlpha,
            backdropAlpha = backdropAlpha,
            isFinished = t >= TOTAL_DURATION_MS
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    private fun easeOutCubic(t: Float): Float = 1f - (1f - t).pow(3)
    private fun easeOutQuad(t: Float): Float = 1f - (1f - t) * (1f - t)
    private fun easeInOutQuad(t: Float): Float =
        if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f).pow(2) / 2f
    private fun easeInOutCubic(t: Float): Float =
        if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f
}

/**
 * KRAS TV — Seamless Cinematic K Intro (1.8s).
 *
 * Renders directly over the preloaded live Home screen using pure Jetpack Compose
 * `graphicsLayer` (`scaleX`, `scaleY`, `alpha`, `clip = false`) and a synchronized
 * crossfade — completely free of triangles, polygon masks, clipping paths, geometric
 * overlays, white lines, or visible borders.
 */
@Composable
fun CinematicKZoomThroughSplash(
    onSplashFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

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
    var reducedMotionAlpha by remember { mutableFloatStateOf(1f) }
    var hasFinished by remember { mutableStateOf(false) }

    fun finishSplash() {
        if (!hasFinished) {
            hasFinished = true
            onSplashFinished()
        }
    }

    LaunchedEffect(isReducedMotion) {
        // Lock cleanly to the display VSYNC frame clock (60Hz / 120Hz)
        val startNanos = withFrameNanos { it }

        if (isReducedMotion) {
            // Reduced motion: calm static display of original 3D K on pure black, then smooth dissolve
            elapsedMs = CinematicKZoomThroughTimeline.PHASE_1_END_MS
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
            val currentElapsed = ((nowNanos - startNanos) / 1_000_000L).coerceAtLeast(0L)
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
                clip = false
                if (isReducedMotion) {
                    alpha = reducedMotionAlpha
                }
            }
            .semantics { contentDescription = "KRAS TV Seamless Cinematic K Intro" }
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

        // =====================================================================
        // LAYER 1: PURE AMOLED BLACK BACKDROP + SOFT AMBIENT CRIMSON AURA
        // Fades smoothly via synchronized crossfade (0.7–1.5s) to reveal the
        // live, preloaded Home screen directly underneath — zero masks or shapes.
        // =====================================================================
        if (frame.backdropAlpha > 0.002f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = frame.backdropAlpha
                        clip = false
                    }
                    .background(Color.Black)
                    .testTag("cinematic_k_backdrop")
            ) {
                if (frame.ambientGlowAlpha > 0.005f) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val glowRadius = (size.minDimension * 0.42f).coerceAtLeast(1f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    KrasRedBright.copy(alpha = 0.22f * frame.ambientGlowAlpha),
                                    KrasRed.copy(alpha = 0.09f * frame.ambientGlowAlpha),
                                    Color.Transparent
                                ),
                                center = Offset(cx, cy),
                                radius = glowRadius
                            ),
                            radius = glowRadius,
                            center = Offset(cx, cy)
                        )
                    }
                }
            }
        }

        // =====================================================================
        // LAYER 2: ORIGINAL METALLIC RED 3D LETTER 'K'
        // Pure GPU graphicsLayer scaleX/scaleY/alpha with clip = false so the
        // expanding K never clips and never displays any rectangular boundaries.
        // =====================================================================
        if (frame.kAlpha > 0.002f) {
            val isSweepActive = frame.lightSweepProgress > 0.005f && frame.lightSweepProgress < 0.995f
            Box(
                modifier = Modifier
                    .size(kBoxSize)
                    .graphicsLayer {
                        scaleX = frame.kScale
                        scaleY = frame.kScale
                        alpha = frame.kAlpha
                        clip = false
                        transformOrigin = TransformOrigin.Center
                        // Offscreen layer is used strictly during the 0.3–0.7s light reflection sweep
                        // (when scale is ~1.0x–1.14x) so SrcAtop stays confined inside the K paths.
                        compositingStrategy = if (isSweepActive) {
                            CompositingStrategy.Offscreen
                        } else {
                            CompositingStrategy.Auto
                        }
                    }
                    .testTag("zoom_through_k_glyph")
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Exact original metallic red 3D K with generous internal transparent margins
                    // (depthLightingAlpha = 0f avoids outer edge circles touching canvas bounds)
                    drawApproved3DK(
                        lightSweepProgress = if (isSweepActive) frame.lightSweepProgress else 0f,
                        depthLightingAlpha = 0f
                    )
                }
            }
        }
    }
}
