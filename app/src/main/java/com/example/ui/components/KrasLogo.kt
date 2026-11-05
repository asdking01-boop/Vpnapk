package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.ui.theme.KrasRed
import com.example.ui.theme.KrasRedBright
import com.example.ui.theme.OutfitFontFamily

/**
 * Kras TV Canonical 3D Glass & Metallic Silver Wordmark "KRAS TV"
 *
 * Features:
 * 1. Single reusable composable drawing the word "KRAS TV" identically across the opening
 *    intro animation, Home header, Login screen, and Video Player:
 *    - Sculpted 3D glossy red crystal K from ic_kras_k with metallic silver rim reflections.
 *    - "RAS TV" in bold geometric Outfit Black typography with 3D extruded bevel depth,
 *      glossy red crystal gradient (#FF525D -> #E50914 -> #B20710 -> #5E0206), and
 *      metallic silver & platinum glass reflections clipped strictly to the letter shapes.
 * 2. Scales cleanly to any requested height (e.g., 28.dp for Home header, 44.dp for Login, 42.dp for Intro).
 * 3. Supports per-letter reveal and reflective light sweep hooks for the 2.2s cinematic intro.
 */
@Composable
fun KrasLogo(
    height: Dp = 28.dp,
    modifier: Modifier = Modifier,
    kModifier: Modifier = Modifier,
    letterAlphaProvider: ((Char) -> Float)? = null,
    letterOffsetProvider: ((Char) -> Float)? = null,
    wordSweepProgress: Float = 0f,
    onKPositioned: ((LayoutCoordinates) -> Unit)? = null
) {
    // Exact aspect ratio of the K glyph in ic_kras_k is 44.5 / 54 ≈ 0.824
    val kWidth = height * (44.5f / 54f)
    val spacerLetter = height * 0.13f
    val spacerWord = height * 0.28f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .width(IntrinsicSize.Max)
            .testTag("kras_tv_logo")
    ) {
        // 1. THE 3D GLOSSY RED CRYSTAL & METALLIC SILVER 'K'
        Box(
            modifier = Modifier
                .size(width = kWidth, height = height)
                .then(
                    if (onKPositioned != null) {
                        Modifier.onGloballyPositioned { coords -> onKPositioned(coords) }
                    } else Modifier
                )
                .then(kModifier)
        ) {
            SharedGlassK(modifier = Modifier.fillMaxSize())
        }

        Spacer(modifier = Modifier.width(spacerLetter))

        // 2. "RAS TV" 3D Glass & Metallic Silver Letters
        if (letterAlphaProvider != null) {
            // Animated per-letter reveal mode with reflective light sweep (used in Intro sequence)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        if (wordSweepProgress > 0.01f && wordSweepProgress < 0.99f) {
                            val w = size.width
                            val h = size.height
                            val beamWidth = w * 0.38f
                            val startX = (wordSweepProgress * (w + beamWidth * 2f)) - beamWidth
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color(0x55E2E8F0),
                                        Color(0xE6FFFFFF),
                                        Color(0x88FF4D58),
                                        Color.Transparent
                                    ),
                                    start = Offset(startX, 0f),
                                    end = Offset(startX + beamWidth, h)
                                ),
                                blendMode = BlendMode.SrcAtop
                            )
                        }
                    }
            ) {
                listOf('R', 'A', 'S').forEach { ch ->
                    KrasLogoLetter(
                        char = ch,
                        height = height,
                        alpha = letterAlphaProvider(ch),
                        offsetY = letterOffsetProvider?.invoke(ch) ?: 0f
                    )
                }
                Spacer(modifier = Modifier.width(spacerWord))
                listOf('T', 'V').forEach { ch ->
                    KrasLogoLetter(
                        char = ch,
                        height = height,
                        alpha = letterAlphaProvider(ch),
                        offsetY = letterOffsetProvider?.invoke(ch) ?: 0f
                    )
                }
            }
        } else {
            // Static crisp mode (used in Home header, Login, Video Player, etc.)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.width(IntrinsicSize.Max)
            ) {
                KrasLogoText(text = "RAS", height = height)
                Spacer(modifier = Modifier.width(spacerWord))
                KrasLogoText(text = "TV", height = height)
            }
        }
    }
}

/**
 * Renders the vector drawable "ic_kras_k" via Android Native VectorDrawable.
 * Automatically fits the K glyph (viewport X: 32..76.5, Y: 27..81) to the canvas bounds
 * so there is zero empty padding around the letter, aligning seamlessly with "RAS TV".
 */
@Composable
fun SharedGlassK(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val kDrawable = remember(context) {
        ContextCompat.getDrawable(context, R.drawable.ic_kras_k)
    }

    Canvas(modifier = modifier) {
        kDrawable?.let { drawable ->
            val glyphLeft = 32f
            val glyphTop = 27f
            val glyphWidth = 44.5f
            val glyphHeight = 54f

            val scaleX = size.width / glyphWidth
            val scaleY = size.height / glyphHeight
            val scale = minOf(scaleX, scaleY)

            val drawnW = 108f * scale
            val drawnH = 108f * scale
            val offsetX = (size.width - glyphWidth * scale) / 2f - glyphLeft * scale
            val offsetY = (size.height - glyphHeight * scale) / 2f - glyphTop * scale

            drawable.setBounds(0, 0, drawnW.toInt(), drawnH.toInt())
            drawIntoCanvas { canvas ->
                canvas.save()
                canvas.translate(offsetX, offsetY)
                drawable.draw(canvas.nativeCanvas)
                canvas.restore()
            }
        }
    }
}

/**
 * 3D Glass & Metallic Silver text renderer for static wordmark segments ("RAS", "TV")
 * with sculpted depth extrusion, ruby crystal gradient, and platinum-silver specular highlight
 * strictly clipped to the letter shapes.
 */
@Composable
private fun KrasLogoText(
    text: String,
    height: Dp,
    modifier: Modifier = Modifier
) {
    val fontSize = (height.value * 1.15f).sp
    val letterSpacing = (height.value * 0.045f).sp
    val shadowBlur = height.value * 0.20f
    val shadowOffset = height.value * 0.055f
    val depthOffsetDp = (height.value * 0.032f).dp

    val crystalRedBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFF525D),
                KrasRedBright,
                KrasRed,
                Color(0xFF9E040B),
                Color(0xFF590206)
            )
        )
    }

    val extrusionBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF64748B),
                Color(0xFF3B0205),
                Color(0xFF1F0103)
            )
        )
    }

    Box(modifier = modifier) {
        // 1. 3D Extruded Bevel Depth Layer
        Text(
            text = text,
            maxLines = 1,
            softWrap = false,
            style = TextStyle(
                brush = extrusionBrush,
                fontSize = fontSize,
                fontWeight = FontWeight.Black,
                fontFamily = OutfitFontFamily,
                letterSpacing = letterSpacing,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            ),
            modifier = Modifier.offset(x = depthOffsetDp * 0.75f, y = depthOffsetDp)
        )

        // 2. Main Glossy Red Crystal Body + Metallic Silver Specular Reflections (clipped to glyph)
        Text(
            text = text,
            maxLines = 1,
            softWrap = false,
            style = TextStyle(
                brush = crystalRedBrush,
                fontSize = fontSize,
                fontWeight = FontWeight.Black,
                fontFamily = OutfitFontFamily,
                letterSpacing = letterSpacing,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                shadow = Shadow(
                    color = KrasRed.copy(alpha = 0.55f),
                    offset = Offset(0f, shadowOffset),
                    blurRadius = shadowBlur
                )
            ),
            modifier = Modifier
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    val h = size.height
                    // Upper metallic silver & crystal glass reflection
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFFF8FAFC).copy(alpha = 0.44f),
                                Color(0xFFCBD5E1).copy(alpha = 0.14f),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = h * 0.48f
                        ),
                        blendMode = BlendMode.SrcAtop
                    )
                    // Subtle lower metallic silver rim reflection
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xFFCBD5E1).copy(alpha = 0.18f)
                            ),
                            startY = h * 0.82f,
                            endY = h
                        ),
                        blendMode = BlendMode.SrcAtop
                    )
                }
        )
    }
}

/**
 * Individual 3D glass & metallic silver animated letter for the opening intro reveal.
 */
@Composable
private fun KrasLogoLetter(
    char: Char,
    height: Dp,
    alpha: Float,
    offsetY: Float,
    modifier: Modifier = Modifier
) {
    val fontSize = (height.value * 1.15f).sp
    val letterSpacing = (height.value * 0.045f).sp
    val shadowBlur = height.value * 0.20f
    val shadowOffset = height.value * 0.055f
    val depthOffsetDp = (height.value * 0.032f).dp

    val crystalRedBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFF525D),
                KrasRedBright,
                KrasRed,
                Color(0xFF9E040B),
                Color(0xFF590206)
            )
        )
    }

    val extrusionBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF64748B),
                Color(0xFF3B0205),
                Color(0xFF1F0103)
            )
        )
    }

    Box(
        modifier = modifier.graphicsLayer {
            this.alpha = alpha
            this.translationY = offsetY
        }
    ) {
        // 1. 3D Extruded Bevel Depth Layer
        Text(
            text = char.toString(),
            maxLines = 1,
            softWrap = false,
            style = TextStyle(
                brush = extrusionBrush,
                fontSize = fontSize,
                fontWeight = FontWeight.Black,
                fontFamily = OutfitFontFamily,
                letterSpacing = letterSpacing,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            ),
            modifier = Modifier.offset(x = depthOffsetDp * 0.75f, y = depthOffsetDp)
        )

        // 2. Main Glossy Red Crystal Body + Metallic Silver Specular Reflections (clipped to glyph)
        Text(
            text = char.toString(),
            maxLines = 1,
            softWrap = false,
            style = TextStyle(
                brush = crystalRedBrush,
                fontSize = fontSize,
                fontWeight = FontWeight.Black,
                fontFamily = OutfitFontFamily,
                letterSpacing = letterSpacing,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                shadow = Shadow(
                    color = KrasRed.copy(alpha = 0.55f),
                    offset = Offset(0f, shadowOffset),
                    blurRadius = shadowBlur
                )
            ),
            modifier = Modifier
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    val h = size.height
                    // Upper metallic silver & crystal glass reflection
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFFF8FAFC).copy(alpha = 0.44f),
                                Color(0xFFCBD5E1).copy(alpha = 0.14f),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = h * 0.48f
                        ),
                        blendMode = BlendMode.SrcAtop
                    )
                    // Subtle lower metallic silver rim reflection
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xFFCBD5E1).copy(alpha = 0.18f)
                            ),
                            startY = h * 0.82f,
                            endY = h
                        ),
                        blendMode = BlendMode.SrcAtop
                    )
                }
        )
    }
}
