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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.ui.theme.KrasRed
import com.example.ui.theme.KrasRedBright

/**
 * Kras TV Canonical Wordmark "KRAS TV"
 *
 * Requirements:
 * 1. Single reusable composable drawing the word "KRAS TV" exactly like the final frame
 *    of the intro animation:
 *    - The glossy glass red K from ic_kras_k
 *    - "RAS TV" in bold wide font with matching red gradient (#FF3A44 -> #E50914 -> #B20710 -> #7A0409)
 *      and soft white glass specular sheen clipped strictly to the letter shapes (no rectangle or box).
 *    - Consistent height, weight, baseline, and letter spacing forming one clean word "KRAS TV".
 * 2. Scales smoothly to any requested height (e.g., 28.dp for Home header, 44.dp for Login, 36.dp for Intro).
 * 3. Static and crisp by default; supports optional letter animation hooks for the intro sequence.
 */
@Composable
fun KrasLogo(
    height: Dp = 28.dp,
    modifier: Modifier = Modifier,
    kModifier: Modifier = Modifier,
    letterAlphaProvider: ((Char) -> Float)? = null,
    letterOffsetProvider: ((Char) -> Float)? = null,
    onKPositioned: ((LayoutCoordinates) -> Unit)? = null
) {
    // Exact aspect ratio of the K glyph in ic_kras_k is 44.5 / 54 ≈ 0.824
    val kWidth = height * (44.5f / 54f)
    val spacerLetter = height * 0.12f
    val spacerWord = height * 0.28f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier.testTag("kras_tv_logo")
    ) {
        // 1. THE GLOSSY GLASS RED 'K'
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

        // 2. "RAS TV"
        if (letterAlphaProvider != null) {
            // Animated per-letter reveal mode (used in Intro sequence)
            Row(verticalAlignment = Alignment.CenterVertically) {
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
            // Static crisp mode (used in Home header, Login, Dialogs, etc.)
            Row(verticalAlignment = Alignment.CenterVertically) {
                KrasLogoText(text = "RAS", height = height)
                Spacer(modifier = Modifier.width(spacerWord))
                KrasLogoText(text = "TV", height = height)
            }
        }
    }
}

/**
 * Renders the EXACT vector drawable "ic_kras_k" via Android Native VectorDrawable.
 * Automatically fits the K glyph (viewport X: 32..76.5, Y: 27..81) to the canvas bounds
 * so there is zero empty padding around the letter, aligning perfectly with surrounding letters.
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
 * Text renderer for static wordmark segments ("RAS", "TV") with deep glossy red gradient
 * and subtle glass top highlight clipped strictly to the text shape.
 */
@Composable
private fun KrasLogoText(
    text: String,
    height: Dp,
    modifier: Modifier = Modifier
) {
    val fontSize = (height.value * 1.167f).sp
    val letterSpacing = (height.value * 0.05f).sp
    val shadowBlur = height.value * 0.18f
    val shadowOffset = height.value * 0.05f

    val glossyRedBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFF3A44),
                KrasRedBright,
                KrasRed,
                Color(0xFF7A0409)
            )
        )
    }

    Text(
        text = text,
        style = TextStyle(
            brush = glossyRedBrush,
            fontSize = fontSize,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = letterSpacing,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            shadow = Shadow(
                color = KrasRed.copy(alpha = 0.5f),
                offset = Offset(0f, shadowOffset),
                blurRadius = shadowBlur
            )
        ),
        modifier = modifier
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                // Soft white glass highlight across upper half of the letters (clipped to letter glyphs)
                val h = size.height
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.35f),
                            Color.White.copy(alpha = 0.08f),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = h * 0.50f
                    ),
                    blendMode = BlendMode.SrcAtop
                )
            }
    )
}

/**
 * Individual animated letter for the opening intro reveal.
 */
@Composable
private fun KrasLogoLetter(
    char: Char,
    height: Dp,
    alpha: Float,
    offsetY: Float,
    modifier: Modifier = Modifier
) {
    val fontSize = (height.value * 1.167f).sp
    val letterSpacing = (height.value * 0.05f).sp
    val shadowBlur = height.value * 0.18f
    val shadowOffset = height.value * 0.05f

    val glossyRedBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFF3A44),
                KrasRedBright,
                KrasRed,
                Color(0xFF7A0409)
            )
        )
    }

    Text(
        text = char.toString(),
        style = TextStyle(
            brush = glossyRedBrush,
            fontSize = fontSize,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = letterSpacing,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            shadow = Shadow(
                color = KrasRed.copy(alpha = 0.5f),
                offset = Offset(0f, shadowOffset),
                blurRadius = shadowBlur
            )
        ),
        modifier = modifier
            .graphicsLayer {
                this.alpha = alpha
                this.translationY = offsetY
                this.compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                val h = size.height
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.35f),
                            Color.White.copy(alpha = 0.08f),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = h * 0.50f
                    ),
                    blendMode = BlendMode.SrcAtop
                )
            }
    )
}
