package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

/**
 * KRAS TV — Premium Sculpted 3D Metallic Red Letters (R, A, S, T, V).
 *
 * Designed to match the EXACT visual identity of the original approved 3D folded-ribbon 'K'
 * (ic_kras_k):
 * - Glossy metallic crimson gradients (#FF5C66 -> #FF1E27 -> #E50914 -> #9E040B -> #590206)
 * - Dark obsidian/charcoal structural back pillars (#2E2E36 -> #191920 -> #050507) with glowing crimson rims
 * - Sculpted 3D depth extrusions & beveled upper-left specular highlight facets
 * - Realistic deep red & black cast shadows (#EE080102 -> #AA1C0204 -> Transparent) at stroke overlaps
 * - Razor-sharp ruby-white specular rim lighting & diagonal metallic sheen (BlendMode.SrcAtop)
 * - Transparent background and infinite vector sharpness at any display density or scale
 */
@Composable
internal fun Premium3DLogoSegment(
    text: String,
    height: Dp,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(height * 0.08f),
        modifier = modifier
    ) {
        text.forEach { ch ->
            Premium3DLogoGlyph(
                char = ch,
                height = height
            )
        }
    }
}

@Composable
internal fun Premium3DLogoGlyph(
    char: Char,
    height: Dp,
    alpha: Float = 1f,
    offsetY: Float = 0f,
    modifier: Modifier = Modifier
) {
    val widthRatio = when (char.uppercaseChar()) {
        'R' -> 0.80f
        'A' -> 0.84f
        'S' -> 0.78f
        'T' -> 0.78f
        'V' -> 0.84f
        else -> 0.78f
    }

    Box(
        modifier = modifier
            .size(width = height * widthRatio, height = height)
            .graphicsLayer {
                this.alpha = alpha
                this.translationY = offsetY
                compositingStrategy = CompositingStrategy.Offscreen
            }
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            when (char.uppercaseChar()) {
                'R' -> draw3DLetterR()
                'A' -> draw3DLetterA()
                'S' -> draw3DLetterS()
                'T' -> draw3DLetterT()
                'V' -> draw3DLetterV()
            }
            // Unified diagonal cinematic metallic sheen strictly clipped to glyph silhouette
            drawMetallicSheenOverlay()
        }
    }
}

// =============================================================================
// SHARED 3D MATERIAL PALETTE (Matches ic_kras_k's exact crimson & obsidian lighting)
// =============================================================================

private fun DrawScope.pt(x: Float, y: Float): Offset =
    Offset(size.width * (x / 100f), size.height * (y / 100f))

private fun DrawScope.drawExtrudedDepth(path: Path) {
    val dx = size.width * 0.036f
    val dy = size.height * 0.044f
    // Deep contact drop shadow
    withTransform({ translate(left = dx * 1.45f, top = dy * 1.5f) }) {
        drawPath(
            path = path,
            color = Color(0xA6030001)
        )
    }
    // 3D beveled metallic extrusion slab
    withTransform({ translate(left = dx, top = dy) }) {
        drawPath(
            path = path,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF420206),
                    Color(0xFF220103),
                    Color(0xFF0A0102)
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height)
            )
        )
    }
}

private fun DrawScope.obsidianPillarBrush(start: Offset, end: Offset): Brush =
    Brush.linearGradient(
        colors = listOf(
            Color(0xFF34343D),
            Color(0xFF1D1D24),
            Color(0xFF0E0E13),
            Color(0xFF050508)
        ),
        start = start,
        end = end
    )

private fun DrawScope.metallicRedRibbonBrush(start: Offset, end: Offset): Brush =
    Brush.linearGradient(
        colorStops = arrayOf(
            0.00f to Color(0xFFFF525D),
            0.18f to Color(0xFFFF1E27),
            0.46f to Color(0xFFE50914),
            0.78f to Color(0xFF9E040B),
            1.00f to Color(0xFF560206)
        ),
        start = start,
        end = end
    )

private fun DrawScope.crimsonLegBrush(start: Offset, end: Offset): Brush =
    Brush.linearGradient(
        colorStops = arrayOf(
            0.00f to Color(0xFF180103),
            0.22f to Color(0xFF520206),
            0.50f to Color(0xFFD90812),
            0.78f to Color(0xFFFF222C),
            1.00f to Color(0xFF7D0309)
        ),
        start = start,
        end = end
    )

private fun DrawScope.specularBevelBrush(start: Offset, end: Offset): Brush =
    Brush.linearGradient(
        colors = listOf(
            Color(0xDDFF9EA5),
            Color(0x88FF4D58),
            Color(0xAAFF6B74),
            Color(0xEEFFC4C9)
        ),
        start = start,
        end = end
    )

private fun DrawScope.glowRimStrokeBrush(start: Offset, end: Offset): Brush =
    Brush.linearGradient(
        colors = listOf(
            Color(0xFFFFB4BA),
            Color(0xFFFF3842),
            Color(0xFFFF1E27),
            Color(0xFFFF6B74)
        ),
        start = start,
        end = end
    )

private fun DrawScope.drawMetallicSheenOverlay() {
    // Crisp top-left to lower-right cinema reflection band clipped to the rendered letter
    drawRect(
        brush = Brush.linearGradient(
            colorStops = arrayOf(
                0.00f to Color(0x38FFFFFF),
                0.22f to Color(0x16FF8A92),
                0.42f to Color.Transparent,
                0.68f to Color(0x12FFFFFF),
                1.00f to Color(0x28000000)
            ),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height)
        ),
        blendMode = BlendMode.SrcAtop
    )
}

// =============================================================================
// 1. SCULPTED 3D LETTER 'R'
//    Obsidian-crimson left vertical pillar (matching K), lower-right diagonal leg
//    with overlap cast shadow, and glossy 3D metallic red foreground bowl ribbon.
// =============================================================================
private fun DrawScope.draw3DLetterR() {
    val w = size.width
    val h = size.height
    val rimW = (w * 0.028f).coerceAtLeast(1.1f)

    // Left vertical pillar (rounded top-left corner, exact sibling of K's pillar)
    val leftPillar = Path().apply {
        moveTo(w * 0.22f, h * 0.02f)
        lineTo(w * 0.36f, h * 0.02f)
        lineTo(w * 0.36f, h * 0.96f)
        lineTo(w * 0.14f, h * 0.96f)
        cubicTo(w * 0.10f, h * 0.96f, w * 0.08f, h * 0.93f, w * 0.08f, h * 0.88f)
        lineTo(w * 0.08f, h * 0.14f)
        cubicTo(w * 0.08f, h * 0.06f, w * 0.14f, h * 0.02f, w * 0.22f, h * 0.02f)
        close()
    }

    // Lower-right diagonal kick leg (emerges behind mid-bowl with cast shadow)
    val lowerLeg = Path().apply {
        moveTo(w * 0.44f, h * 0.50f)
        lineTo(w * 0.66f, h * 0.48f)
        lineTo(w * 0.93f, h * 0.96f)
        lineTo(w * 0.72f, h * 0.96f)
        cubicTo(w * 0.67f, h * 0.96f, w * 0.63f, h * 0.93f, w * 0.60f, h * 0.88f)
        lineTo(w * 0.40f, h * 0.56f)
        close()
    }

    // Sweeping foreground 3D glossy metallic red upper bowl & mid-bar ribbon
    val bowlRibbon = Path().apply {
        moveTo(w * 0.22f, h * 0.02f)
        lineTo(w * 0.61f, h * 0.02f)
        cubicTo(w * 0.82f, h * 0.02f, w * 0.92f, h * 0.13f, w * 0.92f, h * 0.30f)
        cubicTo(w * 0.92f, h * 0.47f, w * 0.80f, h * 0.57f, w * 0.58f, h * 0.57f)
        lineTo(w * 0.31f, h * 0.57f)
        lineTo(w * 0.31f, h * 0.41f)
        lineTo(w * 0.57f, h * 0.41f)
        cubicTo(w * 0.67f, h * 0.41f, w * 0.72f, h * 0.37f, w * 0.72f, h * 0.295f)
        cubicTo(w * 0.72f, h * 0.22f, w * 0.67f, h * 0.18f, w * 0.57f, h * 0.18f)
        lineTo(w * 0.30f, h * 0.18f)
        cubicTo(w * 0.24f, h * 0.18f, w * 0.20f, h * 0.12f, w * 0.22f, h * 0.02f)
        close()
    }

    // 0. 3D Extruded Depth Slab
    drawExtrudedDepth(leftPillar)
    drawExtrudedDepth(lowerLeg)
    drawExtrudedDepth(bowlRibbon)

    // 1. Left Obsidian Pillar + Cast Shadow under bowl + Glowing Crimson Rim
    drawPath(leftPillar, brush = obsidianPillarBrush(pt(10f, 4f), pt(36f, 92f)))
    // Cast shadow from bowl onto pillar
    val pillarShadow = Path().apply {
        moveTo(w * 0.08f, h * 0.54f)
        lineTo(w * 0.36f, h * 0.54f)
        lineTo(w * 0.36f, h * 0.72f)
        lineTo(w * 0.08f, h * 0.68f)
        close()
    }
    drawPath(
        pillarShadow,
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xE8040001), Color(0x88140102), Color.Transparent),
            startY = h * 0.54f,
            endY = h * 0.72f
        )
    )
    val leftRim = Path().apply {
        moveTo(w * 0.095f, h * 0.93f)
        lineTo(w * 0.095f, h * 0.14f)
        cubicTo(w * 0.095f, h * 0.065f, w * 0.145f, h * 0.03f, w * 0.22f, h * 0.03f)
    }
    drawPath(
        leftRim,
        brush = glowRimStrokeBrush(pt(9f, 3f), pt(9f, 94f)),
        style = Stroke(width = rimW, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // 2. Lower-Right Leg + Deep Overlap Cast Shadow + Upper-Right Specular Rim
    drawPath(lowerLeg, brush = crimsonLegBrush(pt(46f, 50f), pt(90f, 96f)))
    val legShadow = Path().apply {
        moveTo(w * 0.40f, h * 0.56f)
        lineTo(w * 0.66f, h * 0.48f)
        lineTo(w * 0.74f, h * 0.62f)
        lineTo(w * 0.49f, h * 0.70f)
        close()
    }
    drawPath(
        legShadow,
        brush = Brush.linearGradient(
            colors = listOf(Color(0xF0070001), Color(0xAA1C0204), Color.Transparent),
            start = pt(48f, 52f),
            end = pt(66f, 68f)
        )
    )
    drawLine(
        brush = Brush.linearGradient(
            colors = listOf(Color.Transparent, Color(0xEEFF5C66), Color(0xBBFF1E27)),
            start = pt(66f, 49f),
            end = pt(92f, 95f)
        ),
        start = pt(66f, 49f),
        end = pt(92f, 95f),
        strokeWidth = rimW * 0.9f,
        cap = StrokeCap.Round
    )

    // 3. Foreground Glossy Metallic Red Bowl Ribbon + Bevel Facet + Specular Rim
    drawPath(bowlRibbon, brush = metallicRedRibbonBrush(pt(24f, 2f), pt(88f, 58f)))

    val bowlBevel = Path().apply {
        moveTo(w * 0.24f, h * 0.03f)
        lineTo(w * 0.61f, h * 0.03f)
        cubicTo(w * 0.80f, h * 0.03f, w * 0.895f, h * 0.13f, w * 0.895f, h * 0.28f)
        lineTo(w * 0.82f, h * 0.27f)
        cubicTo(w * 0.81f, h * 0.16f, w * 0.73f, h * 0.095f, w * 0.59f, h * 0.095f)
        lineTo(w * 0.26f, h * 0.095f)
        close()
    }
    drawPath(bowlBevel, brush = specularBevelBrush(pt(24f, 3f), pt(89f, 28f)))

    val bowlRim = Path().apply {
        moveTo(w * 0.22f, h * 0.03f)
        lineTo(w * 0.61f, h * 0.03f)
        cubicTo(w * 0.81f, h * 0.03f, w * 0.91f, h * 0.13f, w * 0.91f, h * 0.30f)
        cubicTo(w * 0.91f, h * 0.44f, w * 0.81f, h * 0.54f, w * 0.64f, h * 0.56f)
    }
    drawPath(
        bowlRim,
        brush = glowRimStrokeBrush(pt(22f, 3f), pt(90f, 55f)),
        style = Stroke(width = rimW * 0.95f, cap = StrokeCap.Round)
    )
}

// =============================================================================
// 2. SCULPTED 3D LETTER 'A'
//    Obsidian-crimson left ascending leg, recessed metallic crossbar with overlap
//    shadow, and sweeping glossy 3D metallic red right apex-to-foot ribbon.
// =============================================================================
private fun DrawScope.draw3DLetterA() {
    val w = size.width
    val h = size.height
    val rimW = (w * 0.027f).coerceAtLeast(1.1f)

    // Left structural ascending pillar (obsidian/crimson dark metallic with glowing rim)
    val leftLeg = Path().apply {
        moveTo(w * 0.42f, h * 0.02f)
        lineTo(w * 0.57f, h * 0.02f)
        lineTo(w * 0.30f, h * 0.96f)
        lineTo(w * 0.10f, h * 0.96f)
        cubicTo(w * 0.06f, h * 0.96f, w * 0.05f, h * 0.92f, w * 0.07f, h * 0.86f)
        lineTo(w * 0.36f, h * 0.09f)
        cubicTo(w * 0.38f, h * 0.04f, w * 0.40f, h * 0.02f, w * 0.42f, h * 0.02f)
        close()
    }

    // Recessed horizontal crossbar
    val crossBar = Path().apply {
        moveTo(w * 0.26f, h * 0.61f)
        lineTo(w * 0.74f, h * 0.61f)
        lineTo(w * 0.78f, h * 0.76f)
        lineTo(w * 0.22f, h * 0.76f)
        close()
    }

    // Sweeping foreground glossy metallic red right ribbon (wraps over apex down to bottom-right)
    val rightRibbon = Path().apply {
        moveTo(w * 0.43f, h * 0.02f)
        lineTo(w * 0.58f, h * 0.02f)
        cubicTo(w * 0.62f, h * 0.02f, w * 0.64f, h * 0.05f, w * 0.66f, h * 0.10f)
        lineTo(w * 0.94f, h * 0.89f)
        cubicTo(w * 0.955f, h * 0.935f, w * 0.935f, h * 0.96f, w * 0.89f, h * 0.96f)
        lineTo(w * 0.73f, h * 0.96f)
        cubicTo(w * 0.69f, h * 0.96f, w * 0.67f, h * 0.93f, w * 0.655f, h * 0.88f)
        lineTo(w * 0.46f, h * 0.25f)
        lineTo(w * 0.39f, h * 0.10f)
        cubicTo(w * 0.39f, h * 0.05f, w * 0.40f, h * 0.02f, w * 0.43f, h * 0.02f)
        close()
    }

    // 0. 3D Extruded Depth Slab
    drawExtrudedDepth(leftLeg)
    drawExtrudedDepth(crossBar)
    drawExtrudedDepth(rightRibbon)

    // 1. Left Obsidian Pillar + Glowing Crimson Rim
    drawPath(leftLeg, brush = obsidianPillarBrush(pt(38f, 4f), pt(16f, 94f)))
    // Cast shadow where crossbar meets left leg
    drawPath(
        path = Path().apply {
            moveTo(w * 0.20f, h * 0.56f)
            lineTo(w * 0.40f, h * 0.56f)
            lineTo(w * 0.34f, h * 0.78f)
            lineTo(w * 0.15f, h * 0.78f)
            close()
        },
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xDD060001), Color(0x66160102), Color.Transparent),
            startY = h * 0.56f,
            endY = h * 0.78f
        )
    )
    drawLine(
        brush = glowRimStrokeBrush(pt(40f, 3f), pt(8f, 94f)),
        start = pt(40f, 4f),
        end = pt(8.5f, 94f),
        strokeWidth = rimW,
        cap = StrokeCap.Round
    )

    // 2. Crossbar with metallic red bevel & right-side overlap cast shadow
    drawPath(crossBar, brush = metallicRedRibbonBrush(pt(24f, 61f), pt(76f, 76f)))
    drawLine(
        brush = specularBevelBrush(pt(26f, 62f), pt(72f, 62f)),
        start = pt(26f, 62.5f),
        end = pt(72f, 62.5f),
        strokeWidth = rimW * 0.85f,
        cap = StrokeCap.Round
    )
    drawPath(
        path = Path().apply {
            moveTo(w * 0.55f, h * 0.61f)
            lineTo(w * 0.74f, h * 0.61f)
            lineTo(w * 0.78f, h * 0.76f)
            lineTo(w * 0.60f, h * 0.76f)
            close()
        },
        brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, Color(0xDD080102)),
            startX = w * 0.55f,
            endX = w * 0.76f
        )
    )

    // 3. Foreground Sweeping Right Crimson Ribbon + Bevel Highlight + Rim
    drawPath(rightRibbon, brush = metallicRedRibbonBrush(pt(44f, 2f), pt(92f, 96f)))

    val rightBevel = Path().apply {
        moveTo(w * 0.46f, h * 0.03f)
        lineTo(w * 0.58f, h * 0.03f)
        lineTo(w * 0.91f, h * 0.91f)
        lineTo(w * 0.83f, h * 0.91f)
        lineTo(w * 0.52f, h * 0.10f)
        close()
    }
    drawPath(rightBevel, brush = specularBevelBrush(pt(46f, 3f), pt(91f, 91f)))

    val apexRim = Path().apply {
        moveTo(w * 0.42f, h * 0.03f)
        lineTo(w * 0.58f, h * 0.03f)
        cubicTo(w * 0.62f, h * 0.03f, w * 0.64f, h * 0.06f, w * 0.66f, h * 0.11f)
        lineTo(w * 0.93f, h * 0.92f)
    }
    drawPath(
        apexRim,
        brush = glowRimStrokeBrush(pt(42f, 3f), pt(93f, 92f)),
        style = Stroke(width = rimW * 0.95f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

// =============================================================================
// 3. SCULPTED 3D LETTER 'S'
//    Layered obsidian-crimson back fold terminals + sweeping glossy metallic red
//    continuous S-ribbon with sculpted 3D bevels, overlap shadows, and rim glow.
// =============================================================================
private fun DrawScope.draw3DLetterS() {
    val w = size.width
    val h = size.height
    val rimW = (w * 0.028f).coerceAtLeast(1.1f)

    // Upper-right back fold tail (dark obsidian-crimson recessed depth)
    val upperBackCurve = Path().apply {
        moveTo(w * 0.48f, h * 0.02f)
        cubicTo(w * 0.76f, h * 0.02f, w * 0.91f, h * 0.11f, w * 0.91f, h * 0.28f)
        lineTo(w * 0.70f, h * 0.28f)
        cubicTo(w * 0.70f, h * 0.20f, w * 0.62f, h * 0.17f, w * 0.48f, h * 0.17f)
        close()
    }

    // Lower-left back fold tail (dark obsidian-crimson recessed depth)
    val lowerBackCurve = Path().apply {
        moveTo(w * 0.09f, h * 0.70f)
        lineTo(w * 0.30f, h * 0.70f)
        cubicTo(w * 0.30f, h * 0.80f, w * 0.39f, h * 0.82f, w * 0.54f, h * 0.82f)
        lineTo(w * 0.54f, h * 0.97f)
        cubicTo(w * 0.24f, h * 0.97f, w * 0.09f, h * 0.88f, w * 0.09f, h * 0.70f)
        close()
    }

    // Main sweeping foreground 3D glossy crimson S-spine ribbon
    val mainSRibbon = Path().apply {
        moveTo(w * 0.52f, h * 0.02f)
        cubicTo(w * 0.24f, h * 0.02f, w * 0.10f, h * 0.12f, w * 0.10f, h * 0.28f)
        cubicTo(w * 0.10f, h * 0.45f, w * 0.24f, h * 0.51f, w * 0.48f, h * 0.55f)
        cubicTo(w * 0.67f, h * 0.58f, w * 0.72f, h * 0.63f, w * 0.72f, h * 0.71f)
        cubicTo(w * 0.72f, h * 0.79f, w * 0.64f, h * 0.82f, w * 0.50f, h * 0.82f)
        lineTo(w * 0.46f, h * 0.97f)
        lineTo(w * 0.52f, h * 0.97f)
        cubicTo(w * 0.80f, h * 0.97f, w * 0.93f, h * 0.86f, w * 0.93f, h * 0.70f)
        cubicTo(w * 0.93f, h * 0.52f, w * 0.78f, h * 0.44f, w * 0.54f, h * 0.40f)
        cubicTo(w * 0.36f, h * 0.37f, w * 0.31f, h * 0.33f, w * 0.31f, h * 0.26f)
        cubicTo(w * 0.31f, h * 0.19f, w * 0.39f, h * 0.17f, w * 0.52f, h * 0.17f)
        close()
    }

    // 0. 3D Extruded Depth Slab
    drawExtrudedDepth(upperBackCurve)
    drawExtrudedDepth(lowerBackCurve)
    drawExtrudedDepth(mainSRibbon)

    // 1. Recessed Obsidian-Crimson Terminals + Overlap Cast Shadows
    drawPath(upperBackCurve, brush = crimsonLegBrush(pt(50f, 2f), pt(90f, 28f)))
    drawPath(lowerBackCurve, brush = obsidianPillarBrush(pt(10f, 70f), pt(54f, 96f)))
    // Glowing crimson left rim on lower obsidian terminal
    val lowerRim = Path().apply {
        moveTo(w * 0.105f, h * 0.71f)
        cubicTo(w * 0.105f, h * 0.87f, w * 0.24f, h * 0.955f, w * 0.48f, h * 0.955f)
    }
    drawPath(
        lowerRim,
        brush = glowRimStrokeBrush(pt(10f, 71f), pt(48f, 95f)),
        style = Stroke(width = rimW * 0.95f, cap = StrokeCap.Round)
    )

    // Deep cast shadows under ribbon overlap junctions
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Color(0xE0070001), Color.Transparent),
            startX = w * 0.48f,
            endX = w * 0.66f
        ),
        topLeft = pt(48f, 2f),
        size = androidx.compose.ui.geometry.Size(w * 0.18f, h * 0.17f)
    )
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, Color(0xE0070001)),
            startX = w * 0.36f,
            endX = w * 0.54f
        ),
        topLeft = pt(36f, 81f),
        size = androidx.compose.ui.geometry.Size(w * 0.18f, h * 0.16f)
    )

    // 2. Main Glossy Metallic Red S-Ribbon
    drawPath(mainSRibbon, brush = metallicRedRibbonBrush(pt(14f, 4f), pt(90f, 94f)))

    // Sculpted upper-left beveled specular facet along the S-curve
    val sBevel = Path().apply {
        moveTo(w * 0.51f, h * 0.035f)
        cubicTo(w * 0.25f, h * 0.035f, w * 0.12f, h * 0.13f, w * 0.12f, h * 0.27f)
        cubicTo(w * 0.12f, h * 0.40f, w * 0.24f, h * 0.46f, w * 0.49f, h * 0.50f)
        cubicTo(w * 0.72f, h * 0.54f, w * 0.85f, h * 0.60f, w * 0.87f, h * 0.70f)
        lineTo(w * 0.91f, h * 0.69f)
        cubicTo(w * 0.90f, h * 0.54f, w * 0.76f, h * 0.46f, w * 0.53f, h * 0.42f)
        cubicTo(w * 0.29f, h * 0.38f, w * 0.21f, h * 0.33f, w * 0.21f, h * 0.26f)
        cubicTo(w * 0.21f, h * 0.16f, w * 0.32f, h * 0.095f, w * 0.51f, h * 0.095f)
        close()
    }
    drawPath(sBevel, brush = specularBevelBrush(pt(14f, 4f), pt(90f, 70f)))

    // Razor-sharp glowing specular rim stroke along upper & right outer S-contours
    val sOuterRim = Path().apply {
        moveTo(w * 0.86f, h * 0.14f)
        cubicTo(w * 0.77f, h * 0.045f, w * 0.65f, h * 0.03f, w * 0.50f, h * 0.03f)
        cubicTo(w * 0.25f, h * 0.03f, w * 0.11f, h * 0.125f, w * 0.11f, h * 0.28f)
        cubicTo(w * 0.11f, h * 0.42f, w * 0.23f, h * 0.49f, w * 0.50f, h * 0.53f)
    }
    drawPath(
        sOuterRim,
        brush = glowRimStrokeBrush(pt(11f, 3f), pt(86f, 54f)),
        style = Stroke(width = rimW * 0.95f, cap = StrokeCap.Round)
    )
}

// =============================================================================
// 4. SCULPTED 3D LETTER 'T'
//    Obsidian-crimson vertical column with deep cast shadow under crossbar +
//    foreground glossy metallic red beveled top crossbar ribbon.
// =============================================================================
private fun DrawScope.draw3DLetterT() {
    val w = size.width
    val h = size.height
    val rimW = (w * 0.028f).coerceAtLeast(1.1f)

    // Central vertical pillar
    val stem = Path().apply {
        moveTo(w * 0.37f, h * 0.17f)
        lineTo(w * 0.63f, h * 0.17f)
        lineTo(w * 0.63f, h * 0.89f)
        cubicTo(w * 0.63f, h * 0.94f, w * 0.59f, h * 0.96f, w * 0.54f, h * 0.96f)
        lineTo(w * 0.44f, h * 0.96f)
        cubicTo(w * 0.39f, h * 0.96f, w * 0.37f, h * 0.93f, w * 0.37f, h * 0.88f)
        close()
    }

    // Foreground glossy metallic red beveled top crossbar
    val topBar = Path().apply {
        moveTo(w * 0.12f, h * 0.02f)
        lineTo(w * 0.88f, h * 0.02f)
        cubicTo(w * 0.93f, h * 0.02f, w * 0.96f, h * 0.055f, w * 0.96f, h * 0.105f)
        lineTo(w * 0.96f, h * 0.15f)
        cubicTo(w * 0.96f, h * 0.195f, w * 0.92f, h * 0.22f, w * 0.86f, h * 0.22f)
        lineTo(w * 0.12f, h * 0.22f)
        cubicTo(w * 0.065f, h * 0.22f, w * 0.04f, h * 0.185f, w * 0.04f, h * 0.135f)
        lineTo(w * 0.04f, h * 0.10f)
        cubicTo(w * 0.04f, h * 0.05f, w * 0.07f, h * 0.02f, w * 0.12f, h * 0.02f)
        close()
    }

    // 0. 3D Extruded Depth Slab
    drawExtrudedDepth(stem)
    drawExtrudedDepth(topBar)

    // 1. Vertical Pillar (dark obsidian-to-rich-crimson metallic gradient) + Cast Shadow under topBar
    drawPath(
        stem,
        brush = Brush.linearGradient(
            colorStops = arrayOf(
                0.00f to Color(0xFF1F1F26),
                0.22f to Color(0xFF2B0205),
                0.55f to Color(0xFFD60812),
                0.85f to Color(0xFFFF1E27),
                1.00f to Color(0xFF6E0208)
            ),
            start = pt(37f, 18f),
            end = pt(63f, 96f)
        )
    )
    // Realistic deep cast shadow directly below overhanging top bar
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xF2060001), Color(0x991C0103), Color.Transparent),
            startY = h * 0.21f,
            endY = h * 0.42f
        ),
        topLeft = pt(37f, 21f),
        size = androidx.compose.ui.geometry.Size(w * 0.26f, h * 0.21f)
    )
    // Glowing left crimson rim along stem
    drawLine(
        brush = glowRimStrokeBrush(pt(38.5f, 23f), pt(38.5f, 93f)),
        start = pt(38.5f, 24f),
        end = pt(38.5f, 93f),
        strokeWidth = rimW * 0.95f,
        cap = StrokeCap.Round
    )

    // 2. Foreground Glossy Metallic Red Top Bar + Upper Bevel Facet + Razor Rim
    drawPath(topBar, brush = metallicRedRibbonBrush(pt(6f, 2f), pt(94f, 22f)))

    val topBevel = Path().apply {
        moveTo(w * 0.12f, h * 0.032f)
        lineTo(w * 0.88f, h * 0.032f)
        cubicTo(w * 0.92f, h * 0.032f, w * 0.945f, h * 0.06f, w * 0.945f, h * 0.095f)
        lineTo(w * 0.055f, h * 0.095f)
        cubicTo(w * 0.055f, h * 0.06f, w * 0.08f, h * 0.032f, w * 0.12f, h * 0.032f)
        close()
    }
    drawPath(topBevel, brush = specularBevelBrush(pt(6f, 3f), pt(94f, 10f)))

    drawLine(
        brush = glowRimStrokeBrush(pt(7f, 3f), pt(93f, 3f)),
        start = pt(9f, 3.2f),
        end = pt(91f, 3.2f),
        strokeWidth = rimW,
        cap = StrokeCap.Round
    )
}

// =============================================================================
// 5. SCULPTED 3D LETTER 'V'
//    Recessed right diagonal arm with deep vertex cast shadow + foreground
//    sweeping glossy metallic red left-to-apex 3D ribbon.
// =============================================================================
private fun DrawScope.draw3DLetterV() {
    val w = size.width
    val h = size.height
    val rimW = (w * 0.027f).coerceAtLeast(1.1f)

    // Recessed right ascending arm
    val rightArm = Path().apply {
        moveTo(w * 0.44f, h * 0.86f)
        lineTo(w * 0.72f, h * 0.07f)
        cubicTo(w * 0.74f, h * 0.035f, w * 0.77f, h * 0.02f, w * 0.82f, h * 0.02f)
        lineTo(w * 0.92f, h * 0.02f)
        cubicTo(w * 0.95f, h * 0.02f, w * 0.96f, h * 0.055f, w * 0.945f, h * 0.10f)
        lineTo(w * 0.62f, h * 0.91f)
        cubicTo(w * 0.60f, h * 0.95f, w * 0.57f, h * 0.965f, w * 0.52f, h * 0.965f)
        lineTo(w * 0.45f, h * 0.965f)
        close()
    }

    // Sweeping foreground glossy metallic red left arm (overlaps right arm cleanly at vertex)
    val leftRibbon = Path().apply {
        moveTo(w * 0.08f, h * 0.02f)
        lineTo(w * 0.23f, h * 0.02f)
        cubicTo(w * 0.27f, h * 0.02f, w * 0.295f, h * 0.045f, w * 0.31f, h * 0.095f)
        lineTo(w * 0.55f, h * 0.76f)
        lineTo(w * 0.59f, h * 0.91f)
        cubicTo(w * 0.57f, h * 0.95f, w * 0.54f, h * 0.965f, w * 0.48f, h * 0.965f)
        lineTo(w * 0.43f, h * 0.965f)
        cubicTo(w * 0.39f, h * 0.965f, w * 0.365f, h * 0.94f, w * 0.345f, h * 0.89f)
        lineTo(w * 0.055f, h * 0.10f)
        cubicTo(w * 0.04f, h * 0.055f, w * 0.05f, h * 0.02f, w * 0.08f, h * 0.02f)
        close()
    }

    // 0. 3D Extruded Depth Slab
    drawExtrudedDepth(rightArm)
    drawExtrudedDepth(leftRibbon)

    // 1. Right Recessed Arm + Deep Cast Shadow where left ribbon overlaps + Rim
    drawPath(rightArm, brush = crimsonLegBrush(pt(50f, 88f), pt(90f, 6f)))
    val vertexShadow = Path().apply {
        moveTo(w * 0.46f, h * 0.86f)
        lineTo(w * 0.55f, h * 0.54f)
        lineTo(w * 0.68f, h * 0.62f)
        lineTo(w * 0.58f, h * 0.95f)
        close()
    }
    drawPath(
        vertexShadow,
        brush = Brush.linearGradient(
            colors = listOf(Color(0xF2060001), Color(0xAA1C0204), Color.Transparent),
            start = pt(50f, 82f),
            end = pt(66f, 62f)
        )
    )
    drawLine(
        brush = Brush.linearGradient(
            colors = listOf(Color.Transparent, Color(0xEEFF5C66), Color(0xFFFFB4BA)),
            start = pt(58f, 88f),
            end = pt(93f, 5f)
        ),
        start = pt(58f, 88f),
        end = pt(93f, 5f),
        strokeWidth = rimW * 0.9f,
        cap = StrokeCap.Round
    )

    // 2. Foreground Sweeping Glossy Metallic Red Left Ribbon + Bevel Facet + Rim
    drawPath(leftRibbon, brush = metallicRedRibbonBrush(pt(8f, 4f), pt(54f, 96f)))

    val leftBevel = Path().apply {
        moveTo(w * 0.08f, h * 0.032f)
        lineTo(w * 0.16f, h * 0.032f)
        lineTo(w * 0.45f, h * 0.91f)
        lineTo(w * 0.37f, h * 0.91f)
        close()
    }
    drawPath(leftBevel, brush = specularBevelBrush(pt(8f, 3f), pt(44f, 91f)))

    drawLine(
        brush = glowRimStrokeBrush(pt(7f, 4f), pt(40f, 93f)),
        start = pt(7f, 4f),
        end = pt(39f, 93f),
        strokeWidth = rimW,
        cap = StrokeCap.Round
    )
}
