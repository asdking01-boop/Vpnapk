package com.example.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.api.TmdbMetadataMatcher
import com.example.ui.theme.CinemaBlack
import com.example.ui.theme.KrasRedDark

/**
 * Hero backdrop component for Movie & TV Series Details screens.
 *
 * Guarantees the top hero NEVER displays an empty black rectangle:
 * 1. If a genuine widescreen TMDb/Xtream backdrop URL is available and loads cleanly,
 *    renders it sharply with the cinematic dark top/bottom gradient scrim.
 * 2. If the backdrop is unavailable or fails to load in Coil, automatically falls back
 *    to rendering the IPTV/TMDb poster as a blurred, scaled atmospheric background with
 *    a dark cinematic gradient overlay.
 * 3. If neither backdrop nor poster is available, renders a rich subtle dark crimson-to-black
 *    atmospheric gradient instead of flat empty black.
 */
@Composable
fun CinematicDetailsBackdrop(
    backdropUrl: String?,
    posterUrl: String?,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val validBackdrop = remember(backdropUrl, posterUrl) {
        if (TmdbMetadataMatcher.isValidBackdropUrl(backdropUrl, posterUrl)) {
            TmdbMetadataMatcher.normalizeTmdbImageUrl(backdropUrl, size = "w1280")
        } else {
            ""
        }
    }
    val validPoster = remember(posterUrl) {
        TmdbMetadataMatcher.normalizeTmdbImageUrl(posterUrl, size = "w500")
    }

    var backdropLoadFailed by remember(validBackdrop) { mutableStateOf(false) }
    var posterFallbackFailed by remember(validPoster) { mutableStateOf(false) }

    val showRealBackdrop = validBackdrop.isNotBlank() && !backdropLoadFailed
    val showBlurredPoster = !showRealBackdrop && validPoster.isNotBlank() && !posterFallbackFailed

    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        KrasRedDark.copy(alpha = 0.28f),
                        Color(0xFF141118),
                        CinemaBlack
                    )
                )
            )
            .testTag("details_cinematic_backdrop")
    ) {
        if (showRealBackdrop) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(validBackdrop)
                    .crossfade(true)
                    .build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                onError = { backdropLoadFailed = true },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("details_backdrop_image")
            )
        } else if (showBlurredPoster) {
            val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.18f
                        scaleY = 1.18f
                        alpha = 0.72f
                    }
                    .blur(radius = 22.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            } else {
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.15f
                        scaleY = 1.15f
                        alpha = 0.45f
                    }
            }
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(validPoster)
                    .crossfade(true)
                    .build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                onError = { posterFallbackFailed = true },
                modifier = blurModifier.testTag("details_blurred_poster_backdrop")
            )
            // Extra atmospheric dim over blurred poster for crisp contrast
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f))
            )
        }

        // Top subtle vignette for back / action button legibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.65f),
                            0.24f to Color.Transparent,
                            0.55f to CinemaBlack.copy(alpha = 0.45f),
                            0.85f to CinemaBlack.copy(alpha = 0.90f),
                            1.0f to CinemaBlack
                        )
                    )
                )
        )
    }
}
