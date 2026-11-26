package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

import com.example.data.model.KrasPremiumTheme
import com.example.ui.premium.KrasPremiumPalettes

private val KrasDarkColorScheme = darkColorScheme(
    primary = KrasRed,
    onPrimary = Color.White,
    primaryContainer = KrasRedDark,
    onPrimaryContainer = Color.White,
    secondary = KrasRedBright,
    onSecondary = Color.White,
    tertiary = RatingYellow,
    onTertiary = Color.Black,
    background = CinemaBlack,
    onBackground = TextPrimary,
    surface = CinemaDark,
    onSurface = TextPrimary,
    surfaceVariant = CinemaCard,
    onSurfaceVariant = TextSecondary,
    outline = GlassBorder
)

@Composable
fun KrasTvTheme(
    accentColor: Color = KrasRed,
    isPremiumStyle: Boolean = false,
    premiumTheme: KrasPremiumTheme = KrasPremiumTheme.MIDNIGHT_BLACK,
    content: @Composable () -> Unit
) {
    val dynamicScheme = if (!isPremiumStyle) {
        KrasDarkColorScheme.copy(
            primary = accentColor,
            secondary = if (accentColor == KrasRed) KrasRedBright else accentColor
        )
    } else {
        val palette = KrasPremiumPalettes.forTheme(premiumTheme)
        darkColorScheme(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            primaryContainer = palette.primaryDark,
            onPrimaryContainer = Color.White,
            secondary = palette.primaryBright,
            onSecondary = palette.onPrimary,
            tertiary = RatingYellow,
            onTertiary = Color.Black,
            background = palette.background,
            onBackground = TextPrimary,
            surface = palette.surface,
            onSurface = TextPrimary,
            surfaceVariant = palette.surfaceElevated,
            onSurfaceVariant = TextSecondary,
            outline = palette.glassBorder
        )
    }

    MaterialTheme(
        colorScheme = dynamicScheme,
        typography = Typography,
        content = content
    )
}
