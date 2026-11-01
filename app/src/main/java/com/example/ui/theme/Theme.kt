package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
    content: @Composable () -> Unit
) {
    val dynamicScheme = KrasDarkColorScheme.copy(
        primary = accentColor,
        secondary = if (accentColor == KrasRed) KrasRedBright else accentColor
    )

    MaterialTheme(
        colorScheme = dynamicScheme,
        typography = Typography,
        content = content
    )
}
