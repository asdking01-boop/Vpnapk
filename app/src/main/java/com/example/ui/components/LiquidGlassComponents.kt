package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ui.theme.CinemaBlack
import com.example.ui.theme.GlassInnerReflection
import com.example.ui.theme.GlassSpecularHighlight
import com.example.ui.theme.GlassSpecularMid
import com.example.ui.theme.GlassSpecularShadow
import com.example.ui.theme.KrasRed
import com.example.ui.theme.OutfitFontFamily

/**
 * High-performance iOS 26 Liquid Glass panel modifier using [drawWithCache] so Brushes and
 * geometry are cached across frames for 60/120 FPS scrolling smoothness.
 */
fun Modifier.liquidGlassPanel(
    cornerRadius: Dp = 18.dp,
    tintColor: Color = Color(0xFF141824),
    surfaceAlpha: Float = 0.68f,
    borderAlpha: Float = 0.45f,
    accentGlow: Color = Color.Transparent
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius))
    .drawWithCache {
        val radiusPx = cornerRadius.toPx()
        val corner = CornerRadius(radiusPx, radiusPx)
        val strokeWidthPx = 1.dp.toPx()

        val fillBrush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.14f * surfaceAlpha),
                tintColor.copy(alpha = surfaceAlpha),
                tintColor.copy(alpha = (surfaceAlpha + 0.12f).coerceAtMost(0.95f))
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height)
        )

        val glowBrush = if (accentGlow != Color.Transparent) {
            Brush.radialGradient(
                colors = listOf(
                    accentGlow.copy(alpha = 0.22f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.25f, 0f),
                radius = size.width.coerceAtLeast(1f) * 0.85f
            )
        } else null

        val borderBrush = Brush.linearGradient(
            colors = listOf(
                GlassSpecularHighlight.copy(alpha = borderAlpha),
                GlassSpecularMid.copy(alpha = borderAlpha * 0.5f),
                GlassSpecularShadow.copy(alpha = borderAlpha * 0.2f),
                GlassSpecularMid.copy(alpha = borderAlpha * 0.6f)
            ),
            start = Offset.Zero,
            end = Offset(size.width, size.height)
        )

        val topHighlightBrush = Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                GlassInnerReflection.copy(alpha = borderAlpha * 0.8f),
                Color.Transparent
            )
        )

        onDrawBehind {
            drawRoundRect(
                brush = fillBrush,
                cornerRadius = corner
            )
            if (glowBrush != null) {
                drawRoundRect(
                    brush = glowBrush,
                    cornerRadius = corner
                )
            }
            // Subtle top specular reflection line
            drawLine(
                brush = topHighlightBrush,
                start = Offset(radiusPx * 0.6f, strokeWidthPx),
                end = Offset(size.width - radiusPx * 0.6f, strokeWidthPx),
                strokeWidth = strokeWidthPx
            )
            drawRoundRect(
                brush = borderBrush,
                cornerRadius = corner,
                style = Stroke(width = strokeWidthPx)
            )
        }
    }

/**
 * Hardware-accelerated press-scale interaction modifier using [graphicsLayer]
 * so card/button presses animate smoothly at 120Hz without triggering layout passes.
 */
fun Modifier.liquidPressClickable(
    pressedScale: Float = 0.96f,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "liquid_press_scale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

/**
 * Dynamic blurred ambient background extracted from a movie/series poster.
 * Renders a soft, low-alpha blurred artwork layer blended into deep AMOLED black.
 */
@Composable
fun DynamicAmbientPosterBackdrop(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    ambientAlpha: Float = 0.26f
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaBlack)
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = ambientAlpha
                        scaleX = 1.25f
                        scaleY = 1.25f
                    }
                    .blur(56.dp)
            )
        }
        // Deep AMOLED radial + vertical vignette so content stays ultra-legible
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val radialScrim = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            CinemaBlack.copy(alpha = 0.72f),
                            CinemaBlack
                        ),
                        center = Offset(size.width * 0.5f, size.height * 0.22f),
                        radius = size.height.coerceAtLeast(1f) * 0.75f
                    )
                    val verticalScrim = Brush.verticalGradient(
                        0.0f to CinemaBlack.copy(alpha = 0.35f),
                        0.45f to CinemaBlack.copy(alpha = 0.75f),
                        1.0f to CinemaBlack
                    )
                    onDrawBehind {
                        drawRect(radialScrim)
                        drawRect(verticalScrim)
                    }
                }
        )
    }
}

/**
 * Translucent iOS 26 Liquid Glass capsule filter chip with glowing active state.
 */
@Composable
fun LiquidGlassChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = KrasRed
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .clip(shape)
            .drawWithCache {
                val corner = CornerRadius(size.height / 2f, size.height / 2f)
                val fillBrush = if (isSelected) {
                    Brush.linearGradient(
                        colors = listOf(
                            activeColor.copy(alpha = 0.92f),
                            activeColor.copy(alpha = 0.68f)
                        )
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.11f),
                            Color.White.copy(alpha = 0.04f)
                        )
                    )
                }
                val borderBrush = Brush.linearGradient(
                    colors = if (isSelected) {
                        listOf(
                            Color.White.copy(alpha = 0.65f),
                            activeColor.copy(alpha = 0.4f)
                        )
                    } else {
                        listOf(
                            Color.White.copy(alpha = 0.28f),
                            Color.White.copy(alpha = 0.06f)
                        )
                    }
                )
                onDrawBehind {
                    drawRoundRect(brush = fillBrush, cornerRadius = corner)
                    drawRoundRect(
                        brush = borderBrush,
                        cornerRadius = corner,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
            fontFamily = OutfitFontFamily,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = 0.2.sp
        )
    }
}
