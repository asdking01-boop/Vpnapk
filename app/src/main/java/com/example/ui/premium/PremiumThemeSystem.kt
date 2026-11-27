package com.example.ui.premium

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import com.example.data.model.AmbientCinemaIntensity
import com.example.data.model.InterfaceStyle
import com.example.data.model.KrasPremiumTheme
import com.example.data.model.UserSettings
import com.example.ui.theme.CinemaBlack
import com.example.ui.theme.CinemaCard
import com.example.ui.theme.CinemaCardElevated
import com.example.ui.theme.CinemaDark
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.KrasRed
import com.example.ui.theme.KrasRedBright
import com.example.ui.theme.KrasRedDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Immutable color palette for each KRAS Premium 2.0 theme.
 * Preserves high contrast and crisp readability across buttons, dialogs, and navigation indicators.
 */
@Immutable
data class PremiumPalette(
    val theme: KrasPremiumTheme,
    val primary: Color,
    val primaryBright: Color,
    val primaryDark: Color,
    val onPrimary: Color,
    val background: Color,
    val surface: Color,
    val surfaceCard: Color,
    val surfaceElevated: Color,
    val glassTint: Color,
    val glassBorder: Color,
    val gradientTop: Color,
    val gradientBottom: Color,
    val ambientDefault: Color,
    val navIndicator: Color,
    val isPureOled: Boolean = false
)

object KrasPremiumPalettes {

    val MidnightBlack = PremiumPalette(
        theme = KrasPremiumTheme.MIDNIGHT_BLACK,
        primary = Color(0xFFE50914),
        primaryBright = Color(0xFFFF2B38),
        primaryDark = Color(0xFF8F060E),
        onPrimary = Color.White,
        background = Color(0xFF05070D),
        surface = Color(0xFF0B0F1A),
        surfaceCard = Color(0xFF121828),
        surfaceElevated = Color(0xFF1A2236),
        glassTint = Color(0xFF111829),
        glassBorder = Color(0x38DCE6FF),
        gradientTop = Color(0xFF15203A),
        gradientBottom = Color(0xFF05070D),
        ambientDefault = Color(0xFF1E325C),
        navIndicator = Color(0xFFE50914),
        isPureOled = false
    )

    val NetflixRed = PremiumPalette(
        theme = KrasPremiumTheme.NETFLIX_RED,
        primary = Color(0xFFE50914),
        primaryBright = Color(0xFFFF2432),
        primaryDark = Color(0xFF99040C),
        onPrimary = Color.White,
        background = Color(0xFF080405),
        surface = Color(0xFF11080A),
        surfaceCard = Color(0xFF1B0C0F),
        surfaceElevated = Color(0xFF271217),
        glassTint = Color(0xFF1D0B0E),
        glassBorder = Color(0x40FF5C66),
        gradientTop = Color(0xFF3A0A10),
        gradientBottom = Color(0xFF080405),
        ambientDefault = Color(0xFF7A0A12),
        navIndicator = Color(0xFFFF1F2D),
        isPureOled = false
    )

    val RoyalGold = PremiumPalette(
        theme = KrasPremiumTheme.ROYAL_GOLD,
        primary = Color(0xFFD4AF37),
        primaryBright = Color(0xFFF4D160),
        primaryDark = Color(0xFF8C6E14),
        onPrimary = Color(0xFF120E04),
        background = Color(0xFF070605),
        surface = Color(0xFF100E0A),
        surfaceCard = Color(0xFF19150E),
        surfaceElevated = Color(0xFF241E14),
        glassTint = Color(0xFF1B160D),
        glassBorder = Color(0x47F4D160),
        gradientTop = Color(0xFF332710),
        gradientBottom = Color(0xFF070605),
        ambientDefault = Color(0xFF6B5215),
        navIndicator = Color(0xFFF4D160),
        isPureOled = false
    )

    val TitaniumSilver = PremiumPalette(
        theme = KrasPremiumTheme.TITANIUM_SILVER,
        primary = Color(0xFFC5CDD8),
        primaryBright = Color(0xFFE8EEF6),
        primaryDark = Color(0xFF6B7482),
        onPrimary = Color(0xFF0B0D12),
        background = Color(0xFF080A0E),
        surface = Color(0xFF10131A),
        surfaceCard = Color(0xFF181D26),
        surfaceElevated = Color(0xFF222936),
        glassTint = Color(0xFF171C24),
        glassBorder = Color(0x4AE6EEF8),
        gradientTop = Color(0xFF252D3B),
        gradientBottom = Color(0xFF080A0E),
        ambientDefault = Color(0xFF475468),
        navIndicator = Color(0xFFE2E8F2),
        isPureOled = false
    )

    val AmoledBlack = PremiumPalette(
        theme = KrasPremiumTheme.AMOLED_BLACK,
        primary = Color(0xFFE50914),
        primaryBright = Color(0xFFFF1F2D),
        primaryDark = Color(0xFF8C050C),
        onPrimary = Color.White,
        background = Color(0xFF000000),
        surface = Color(0xFF050507),
        surfaceCard = Color(0xFF0B0B0F),
        surfaceElevated = Color(0xFF14141A),
        glassTint = Color(0xFF09090D),
        glassBorder = Color(0x33FFFFFF),
        gradientTop = Color(0xFF121218),
        gradientBottom = Color(0xFF000000),
        ambientDefault = Color(0xFF2B080C),
        navIndicator = Color(0xFFE50914),
        isPureOled = true
    )

    val ClassicFallback = PremiumPalette(
        theme = KrasPremiumTheme.MIDNIGHT_BLACK,
        primary = KrasRed,
        primaryBright = KrasRedBright,
        primaryDark = KrasRedDark,
        onPrimary = Color.White,
        background = CinemaBlack,
        surface = CinemaDark,
        surfaceCard = CinemaCard,
        surfaceElevated = CinemaCardElevated,
        glassTint = Color(0xFF141824),
        glassBorder = GlassBorder,
        gradientTop = Color(0xFF141620),
        gradientBottom = CinemaBlack,
        ambientDefault = Color(0xFF3A0C10),
        navIndicator = KrasRed,
        isPureOled = false
    )

    fun forTheme(theme: KrasPremiumTheme): PremiumPalette = when (theme) {
        KrasPremiumTheme.MIDNIGHT_BLACK -> MidnightBlack
        KrasPremiumTheme.NETFLIX_RED -> NetflixRed
        KrasPremiumTheme.ROYAL_GOLD -> RoyalGold
        KrasPremiumTheme.TITANIUM_SILVER -> TitaniumSilver
        KrasPremiumTheme.AMOLED_BLACK -> AmoledBlack
    }
}

/**
 * Resolved runtime experience state combining user toggles, system battery saver,
 * and Android system animation scale accessibility settings.
 */
@Immutable
data class PremiumExperienceState(
    val isPremiumStyle: Boolean = false,
    val palette: PremiumPalette = KrasPremiumPalettes.ClassicFallback,
    val cinematicBackgroundsActive: Boolean = false,
    val liquidGlassNavigationActive: Boolean = false,
    val movieReelsActive: Boolean = false,
    val ambientCinemaActive: Boolean = false,
    val ambientCinemaIntensity: AmbientCinemaIntensity = AmbientCinemaIntensity.MEDIUM,
    val animationsEnabled: Boolean = true,
    val hapticsEnabled: Boolean = false,
    val batterySavingActive: Boolean = false,
    val systemReducedMotion: Boolean = false,
    val usePremium3DLogo: Boolean = true
)

val LocalPremiumExperienceState = compositionLocalOf { PremiumExperienceState() }

/**
 * Computes [PremiumExperienceState] safely respecting Android system Power Saver
 * and Accessibility/Developer animation scale settings.
 */
@Composable
fun rememberPremiumExperienceState(userSettings: UserSettings): PremiumExperienceState {
    val context = LocalContext.current
    val systemPowerSave = remember(context, userSettings.batterySavingMode) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isPowerSaveMode == true
        } catch (_: Exception) {
            false
        }
    }
    val systemReducedMotion = remember(context) {
        try {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
            scale == 0f
        } catch (_: Exception) {
            false
        }
    }

    return remember(userSettings, systemPowerSave, systemReducedMotion) {
        resolvePremiumExperienceState(
            userSettings = userSettings,
            systemPowerSave = systemPowerSave,
            systemReducedMotion = systemReducedMotion
        )
    }
}

/**
 * Pure function resolving effective runtime flags (unit-testable).
 */
fun resolvePremiumExperienceState(
    userSettings: UserSettings,
    systemPowerSave: Boolean = false,
    systemReducedMotion: Boolean = false
): PremiumExperienceState {
    val isPremium = userSettings.interfaceStyle == InterfaceStyle.CINEMATIC_PREMIUM_2
    val batterySaving = userSettings.batterySavingMode || systemPowerSave
    val palette = if (isPremium) {
        KrasPremiumPalettes.forTheme(userSettings.premiumTheme)
    } else {
        KrasPremiumPalettes.ClassicFallback
    }
    val animationsAllowed = !systemReducedMotion && !batterySaving &&
        (!isPremium || userSettings.premiumAnimationsEnabled)

    return PremiumExperienceState(
        isPremiumStyle = isPremium,
        palette = palette,
        cinematicBackgroundsActive = isPremium && userSettings.cinematicBackgroundsEnabled && !batterySaving,
        liquidGlassNavigationActive = isPremium && userSettings.liquidGlassNavigationEnabled,
        movieReelsActive = isPremium && userSettings.movieReelsEnabled,
        ambientCinemaActive = userSettings.ambientCinemaModeEnabled && !batterySaving,
        ambientCinemaIntensity = userSettings.ambientCinemaIntensity,
        animationsEnabled = animationsAllowed,
        hapticsEnabled = isPremium && userSettings.hapticFeedbackEnabled && !batterySaving,
        batterySavingActive = batterySaving,
        systemReducedMotion = systemReducedMotion,
        usePremium3DLogo = userSettings.usePremium3DLogo
    )
}

@Immutable
data class ExtractedArtworkColors(
    val dominant: Color,
    val vibrant: Color,
    val darkMuted: Color
)

/**
 * Fast, low-memory adaptive color extractor for movie/series posters and backdrops.
 * Samples a tiny 24x24 downscaled bitmap on [Dispatchers.Default] and caches results
 * in an [LruCache] so 60Hz/120Hz scrolling never allocates or blocks the main thread.
 */
object ArtworkColorExtractor {
    private val cache = LruCache<String, ExtractedArtworkColors>(48)

    fun getCached(url: String?): ExtractedArtworkColors? {
        if (url.isNullOrBlank()) return null
        return cache.get(url)
    }

    suspend fun extractFromUrl(
        context: Context,
        imageUrl: String?,
        fallbackColor: Color
    ): ExtractedArtworkColors {
        val fallback = ExtractedArtworkColors(
            dominant = fallbackColor,
            vibrant = fallbackColor,
            darkMuted = darken(fallbackColor, 0.35f)
        )
        if (imageUrl.isNullOrBlank()) return fallback
        cache.get(imageUrl)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .size(48, 48)
                    .precision(Precision.INEXACT)
                    .allowHardware(false)
                    .build()
                val result = ImageLoader(context).execute(request)
                val bitmap = (result as? SuccessResult)?.drawable?.let { drawable ->
                    (drawable as? BitmapDrawable)?.bitmap
                } ?: return@withContext fallback

                val extracted = withContext(Dispatchers.Default) {
                    extractFromBitmap(bitmap, fallbackColor)
                }
                cache.put(imageUrl, extracted)
                extracted
            } catch (_: Exception) {
                fallback
            }
        }
    }

    fun extractFromBitmap(bitmap: Bitmap, fallbackColor: Color): ExtractedArtworkColors {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            return ExtractedArtworkColors(fallbackColor, fallbackColor, darken(fallbackColor, 0.35f))
        }

        val stepX = max(1, width / 16)
        val stepY = max(1, height / 16)

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0L

        var bestVibrantScore = -1f
        var bestVibrantArgb = fallbackColor.toArgb()

        val hsv = FloatArray(3)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = (pixel ushr 24) and 0xFF
                if (alpha >= 180) {
                    val r = (pixel ushr 16) and 0xFF
                    val g = (pixel ushr 8) and 0xFF
                    val b = pixel and 0xFF
                    sumR += r
                    sumG += g
                    sumB += b
                    count++

                    android.graphics.Color.RGBToHSV(r, g, b, hsv)
                    val sat = hsv[1]
                    val value = hsv[2]
                    // Favor rich cinematic colors (avoid pure black or washed-out white)
                    if (value in 0.18f..0.92f && sat >= 0.22f) {
                        val score = sat * 1.4f + (1f - kotlin.math.abs(value - 0.55f))
                        if (score > bestVibrantScore) {
                            bestVibrantScore = score
                            bestVibrantArgb = pixel
                        }
                    }
                }
                x += stepX
            }
            y += stepY
        }

        val dominant = if (count > 0L) {
            Color(
                red = ((sumR / count).toInt().coerceIn(0, 255)) / 255f,
                green = ((sumG / count).toInt().coerceIn(0, 255)) / 255f,
                blue = ((sumB / count).toInt().coerceIn(0, 255)) / 255f,
                alpha = 1f
            )
        } else {
            fallbackColor
        }

        val vibrant = if (bestVibrantScore > 0f) Color(bestVibrantArgb) else dominant
        return ExtractedArtworkColors(
            dominant = clampLuminance(dominant, minLum = 0.08f, maxLum = 0.45f),
            vibrant = clampLuminance(vibrant, minLum = 0.16f, maxLum = 0.62f),
            darkMuted = darken(dominant, 0.28f)
        )
    }

    private fun darken(color: Color, factor: Float): Color {
        val f = factor.coerceIn(0f, 1f)
        return Color(
            red = (color.red * f).coerceIn(0f, 1f),
            green = (color.green * f).coerceIn(0f, 1f),
            blue = (color.blue * f).coerceIn(0f, 1f),
            alpha = 1f
        )
    }

    private fun clampLuminance(color: Color, minLum: Float, maxLum: Float): Color {
        val maxC = max(color.red, max(color.green, color.blue))
        val minC = min(color.red, min(color.green, color.blue))
        val lum = (maxC + minC) * 0.5f
        if (lum < 0.001f) return color
        val target = lum.coerceIn(minLum, maxLum)
        val scale = target / lum
        return Color(
            red = (color.red * scale).coerceIn(0f, 1f),
            green = (color.green * scale).coerceIn(0f, 1f),
            blue = (color.blue * scale).coerceIn(0f, 1f),
            alpha = 1f
        )
    }

    fun supportsRealTimeBlur(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}
